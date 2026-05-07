package com.sean.bancassurance.policy.api;

import com.sean.bancassurance.policy.api.dto.ChangeAddressRequest;
import com.sean.bancassurance.policy.api.dto.ChangeBeneficiariesRequest;
import com.sean.bancassurance.policy.api.dto.ChangePaymentMethodRequest;
import com.sean.bancassurance.policy.api.dto.PolicyChangeLogResponse;
import com.sean.bancassurance.policy.api.dto.PolicyResponse;
import com.sean.bancassurance.policy.service.PolicyChangeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
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
 *
 *  ── @RequestHeader(required=false) ──────────────────────────────────
 *    這三個 header 都是「可選」— 不帶不會 400。Service 層自行決定怎麼處理 null。
 *    actor 預設 "system" 是教學妥協；正式系統一定要 enforce。
 *
 *  ── @RequestBody @Valid ─────────────────────────────────────────────
 *    @Valid 觸發 Bean Validation；失敗 → MethodArgumentNotValidException → 400 (M2 已寫 handler)
 *    重要：對 list element 內的驗證，DTO 內部的 list field 也要標 @Valid (見 ChangeBeneficiariesRequest)
 */
@RestController
@RequestMapping("/api/policies/{policyId}")
@RequiredArgsConstructor
public class PolicyChangeController {

    private final PolicyChangeService service;

    /**
     * PATCH /api/policies/{id}/address
     *
     * Headers:
     *   If-Match: "0"             (optional — 樂觀鎖前置檢查；body 也可)
     *   Idempotency-Key: <uuid>   (optional — 冪等鍵；最多重送 = 實際執行 1 次)
     *   X-Actor: alice            (optional — placeholder for auth；預設 "system")
     */
    @PatchMapping("/address")
    public ResponseEntity<PolicyResponse> changeAddress(
            @PathVariable UUID policyId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Actor", required = false, defaultValue = "system") String actor,
            @Valid @RequestBody ChangeAddressRequest req) {

        PolicyResponse response = service.changeAddress(policyId, ifMatch, idempotencyKey, req, actor);
        return ResponseEntity.ok()
                .eTag("\"" + response.version() + "\"")   // 帶上新版本 ETag 給 client 下次用
                .body(response);
    }

    /**
     * PATCH /api/policies/{id}/beneficiaries
     *
     * 重點：body.beneficiaries 是「全量替換」— 送一份完整的新 list，舊的會被全 DELETE。
     * 業務規則：比例加總必須 = 100；至少一位 priority=1。
     */
    @PatchMapping("/beneficiaries")
    public ResponseEntity<PolicyResponse> changeBeneficiaries(
            @PathVariable UUID policyId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Actor", required = false, defaultValue = "system") String actor,
            @Valid @RequestBody ChangeBeneficiariesRequest req) {

        PolicyResponse response = service.changeBeneficiaries(policyId, ifMatch, idempotencyKey, req, actor);
        return ResponseEntity.ok()
                .eTag("\"" + response.version() + "\"")
                .body(response);
    }

    /**
     * PATCH /api/policies/{id}/payment-method
     *
     * 業務規則：不允許換成 SINGLE_PAY (躉繳是新單時才能選)。
     */
    @PatchMapping("/payment-method")
    public ResponseEntity<PolicyResponse> changePaymentMethod(
            @PathVariable UUID policyId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Actor", required = false, defaultValue = "system") String actor,
            @Valid @RequestBody ChangePaymentMethodRequest req) {

        PolicyResponse response = service.changePaymentMethod(policyId, ifMatch, idempotencyKey, req, actor);
        return ResponseEntity.ok()
                .eTag("\"" + response.version() + "\"")
                .body(response);
    }

    /**
     * GET /api/policies/{id}/changes
     * 列出所有變更歷史，按 occurred_at desc。
     */
    @GetMapping("/changes")
    public List<PolicyChangeLogResponse> listChanges(@PathVariable UUID policyId) {
        return service.listChanges(policyId);
    }
}
