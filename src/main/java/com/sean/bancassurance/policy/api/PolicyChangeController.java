package com.sean.bancassurance.policy.api;

import com.sean.bancassurance.common.exception.ApiError;
import com.sean.bancassurance.policy.api.dto.ChangeAddressRequest;
import com.sean.bancassurance.policy.api.dto.ChangeBeneficiariesRequest;
import com.sean.bancassurance.policy.api.dto.ChangePaymentMethodRequest;
import com.sean.bancassurance.policy.api.dto.PolicyChangeLogResponse;
import com.sean.bancassurance.policy.api.dto.PolicyResponse;
import com.sean.bancassurance.policy.service.PolicyChangeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 保單變更 REST Controller — M5。
 *
 *  路由設計：
 *    PATCH /api/policies/{id}/address          ← 變更通訊地址
 *    PATCH /api/policies/{id}/beneficiaries    ← 變更受益人 (集合替換)
 *    PATCH /api/policies/{id}/payment-method   ← 變更繳費方式
 *    GET   /api/policies/{id}/changes          ← 列出變更歷史
 *
 *  ── 為什麼用 PATCH 不用 PUT？─────────────────────────────────────────
 *    PUT「替換整個資源」的語意 → client 要送整張保單；對細粒度變更不友善
 *    PATCH「部分更新」的語意 → 跟我們做的「只改某些欄位」對得上
 *
 *  ── 為什麼每個變更類型一個 sub-resource (/address, /beneficiaries) ？──
 *    替代寫法：PATCH /api/policies/{id} body 含 changeType + payload
 *    我們選 sub-resource 的理由：
 *      1. URL 語意自帶「我要改什麼」，不必看 body 才知道
 *      2. 不同 sub-resource 有不同 DTO，spring 自動驗證型別 (避免 polymorphic body)
 *      3. 監控 / RBAC 比較好做：「這個 actor 只能打 /address 不能打 /beneficiaries」
 *
 *  ── HTTP Headers ─────────────────────────────────────────────────────
 *    If-Match: "0"            — RFC 7232 標準。樂觀鎖前置檢查，比對失敗 → 412
 *    Idempotency-Key: <uuid>  — 冪等性。重送相同 key + 相同 body → replay 上次回應
 *    X-Actor: alice           — 變更發起人 (placeholder；M9 換 SecurityContext)
 */
@Tag(
    name = "保單變更 (Policy Endorsement)",
    description = """
        保單資料變更申請，包含通訊地址、受益人、繳費方式。

        **樂觀鎖流程：**
        1. `GET /api/policies/{id}` 取回保單，記錄 response header 的 `ETag: "N"`
        2. `PATCH .../xxx` 帶 header `If-Match: "N"`
        3. 若期間有其他人改過 → `412 Precondition Failed`，請重新 GET 後再試

        **冪等性：**
        帶 `Idempotency-Key: <uuid>`，相同 key + 相同 body 重送會回放上次結果（不重複執行）。
        """
)
@RestController
@RequestMapping("/api/policies/{policyId}")
@RequiredArgsConstructor
public class PolicyChangeController {

    private final PolicyChangeService service;

    /**
     * PATCH /api/policies/{id}/address
     */
    @Operation(
        summary = "變更通訊地址",
        description = "更新保單持有人的通訊地址。支援樂觀鎖（If-Match）與冪等性（Idempotency-Key）。"
    )
    @ApiResponse(responseCode = "200", description = "變更成功，回傳更新後保單",
        headers = @Header(name = "ETag", description = "新版本號",
                          schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "409", description = "樂觀鎖衝突（已被他人修改）",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "412", description = "If-Match 比對失敗（版本過舊）",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PatchMapping("/address")
    public ResponseEntity<PolicyResponse> changeAddress(
            @Parameter(description = "保單 UUID") @PathVariable UUID policyId,
            @Parameter(description = "RFC 7232 樂觀鎖版本，例如 \"0\"")
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Parameter(description = "冪等鍵（UUID），重送相同 key+body 會回放結果")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Parameter(description = "操作者（M9 前用 header 傳入）")
            @RequestHeader(value = "X-Actor", required = false, defaultValue = "system") String actor,
            @Valid @org.springframework.web.bind.annotation.RequestBody ChangeAddressRequest req) {

        PolicyResponse response = service.changeAddress(policyId, ifMatch, idempotencyKey, req, actor);
        return ResponseEntity.ok()
                .eTag("\"" + response.version() + "\"")
                .body(response);
    }

    /**
     * PATCH /api/policies/{id}/beneficiaries
     */
    @Operation(
        summary = "變更受益人",
        description = """
            **全量替換**：送入完整新受益人 list，舊的全部刪除再重建。

            業務規則：
            - 所有受益人的 `sharePercent` 加總必須等於 100
            - 至少一位受益人的 `priority` = 1（第一順位）
            """
    )
    @ApiResponse(responseCode = "200", description = "變更成功")
    @ApiResponse(responseCode = "409", description = "樂觀鎖衝突",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "412", description = "If-Match 比對失敗",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "業務規則違反（受益人比例加總 ≠ 100）",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PatchMapping("/beneficiaries")
    public ResponseEntity<PolicyResponse> changeBeneficiaries(
            @PathVariable UUID policyId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Actor", required = false, defaultValue = "system") String actor,
            @Valid @org.springframework.web.bind.annotation.RequestBody ChangeBeneficiariesRequest req) {

        PolicyResponse response = service.changeBeneficiaries(policyId, ifMatch, idempotencyKey, req, actor);
        return ResponseEntity.ok()
                .eTag("\"" + response.version() + "\"")
                .body(response);
    }

    /**
     * PATCH /api/policies/{id}/payment-method
     */
    @Operation(
        summary = "變更繳費方式",
        description = "允許切換 `MONTHLY`、`QUARTERLY`、`SEMI_ANNUAL`、`ANNUAL`。" +
                      "**禁止**切換為 `SINGLE_PAY`（躉繳只能在新單時選擇）。"
    )
    @ApiResponse(responseCode = "200", description = "變更成功")
    @ApiResponse(responseCode = "422", description = "業務規則違反（不允許改為 SINGLE_PAY）",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PatchMapping("/payment-method")
    public ResponseEntity<PolicyResponse> changePaymentMethod(
            @PathVariable UUID policyId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Actor", required = false, defaultValue = "system") String actor,
            @Valid @org.springframework.web.bind.annotation.RequestBody ChangePaymentMethodRequest req) {

        PolicyResponse response = service.changePaymentMethod(policyId, ifMatch, idempotencyKey, req, actor);
        return ResponseEntity.ok()
                .eTag("\"" + response.version() + "\"")
                .body(response);
    }

    /**
     * GET /api/policies/{id}/changes
     */
    @Operation(
        summary = "查保單變更歷史",
        description = "列出所有已套用的變更記錄，含 before/after JSON snapshot，依時間降冪排序。"
    )
    @GetMapping("/changes")
    public List<PolicyChangeLogResponse> listChanges(
            @Parameter(description = "保單 UUID") @PathVariable UUID policyId) {
        return service.listChanges(policyId);
    }
}
