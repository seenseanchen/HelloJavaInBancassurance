package com.sean.bancassurance.policy.api;

import com.sean.bancassurance.common.exception.ApiError;
import com.sean.bancassurance.policy.api.dto.PolicyResponse;
import com.sean.bancassurance.policy.api.dto.PolicySummaryResponse;
import com.sean.bancassurance.policy.domain.PolicyStatus;
import com.sean.bancassurance.policy.service.PolicySearchCriteria;
import com.sean.bancassurance.policy.service.PolicyService;
import com.sean.bancassurance.underwriting.domain.Channel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 保單 REST Controller — M4 上半「查詢」階段。
 *
 *  路由設計：
 *    GET /api/policies/{id}                  ← 用內部 UUID 查單筆 (含受益人)
 *    GET /api/policies/by-number/{number}    ← 用對外保單號查單筆 (含受益人)
 *    GET /api/policies                        ← 多條件清單查詢 (分頁，不含受益人)
 *
 *  ── @PageableDefault 預設值說明 ────────────────────────────────────
 *    sort = "effectiveDate", direction = DESC：未指定 ?sort= 時，
 *    依生效日由新到舊 — 跟 idx_policy_status_effective_date 索引方向一致，效能好。
 *
 *  ── @DateTimeFormat 為什麼必加？─────────────────────────────────────
 *    Spring 預設無法把 ?effectiveDateFrom=2026-01-01 → LocalDate；要嘛全域註冊
 *    Converter (推薦)，要嘛在 RequestParam 上加 @DateTimeFormat(iso = DATE)。
 *    這裡顯式加，讓你看到 ISO 8601 格式的接法。
 */
@Tag(
    name = "保單查詢 (Policy Query)",
    description = "查詢保單明細、受益人、繳費狀態。支援多條件動態過濾與分頁。" +
                  "單筆查詢回應帶 ETag header，可用於後續 PATCH 的 If-Match 樂觀鎖。"
)
@RestController
@RequestMapping("/api/policies")
@RequiredArgsConstructor
public class PolicyController {

    private final PolicyService service;

    /**
     * GET /api/policies/{id}
     */
    @Operation(
        summary = "查單筆保單（by UUID）",
        description = "回應含受益人明細。Response header 帶 `ETag: \"版本號\"`，" +
                      "供後續 PATCH 操作帶 `If-Match` header 做樂觀鎖驗證。"
    )
    @ApiResponse(
        responseCode = "200",
        description = "查詢成功",
        headers = @Header(name = "ETag", description = "保單版本號，例如 \"0\"",
                          schema = @Schema(type = "string"))
    )
    @ApiResponse(responseCode = "404", description = "保單不存在",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{id}")
    public ResponseEntity<PolicyResponse> getById(
            @Parameter(description = "保單內部 UUID") @PathVariable UUID id) {
        PolicyResponse response = service.getById(id);
        return ResponseEntity.ok()
                .eTag("\"" + response.version() + "\"")
                .body(response);
    }

    /**
     * GET /api/policies/by-number/{policyNumber}
     */
    @Operation(
        summary = "查單筆保單（by 保單號）",
        description = "客服最常用。客戶報出保單號，客服查明細。" +
                      "保單號格式：`BANK-LIFE-YYYYMMDD-NNNN`，例如 `BANK-LIFE-20260507-0001`"
    )
    @ApiResponse(responseCode = "200", description = "查詢成功",
        headers = @Header(name = "ETag", description = "保單版本號",
                          schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "404", description = "保單不存在",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/by-number/{policyNumber}")
    public ResponseEntity<PolicyResponse> getByPolicyNumber(
            @Parameter(description = "對外保單號，例如 BANK-LIFE-20260507-0001")
            @PathVariable String policyNumber) {
        PolicyResponse response = service.getByPolicyNumber(policyNumber);
        return ResponseEntity.ok()
                .eTag("\"" + response.version() + "\"")
                .body(response);
    }

    /**
     * GET /api/policies — 多條件清單查詢
     */
    @Operation(
        summary = "查保單清單（多條件過濾 + 分頁）",
        description = """
            所有過濾條件均為**選填**，可自由組合。預設依生效日降冪排序。

            | 參數 | 說明 | 範例 |
            |---|---|---|
            | `holderIdNumber` | 要保人身分證號（模糊搜尋，前四碼起） | `A123` |
            | `status` | 保單狀態 | `IN_FORCE` |
            | `productCode` | 商品代碼 | `LIFE-001` |
            | `channel` | 銷售通路 | `BANCASSURANCE` |
            | `effectiveDateFrom` | 生效日起（ISO 格式） | `2026-01-01` |
            | `effectiveDateTo` | 生效日迄（ISO 格式） | `2026-12-31` |

            回傳**清單精簡 DTO** (`PolicySummaryResponse`)，不含受益人明細（避免 N+1）。
            需要受益人請改用 `/api/policies/{id}`。
            """
    )
    @GetMapping
    public Page<PolicySummaryResponse> search(
            @Parameter(description = "要保人身分證號（部分符合）")
            @RequestParam(required = false) String holderIdNumber,
            @Parameter(description = "保單狀態")
            @RequestParam(required = false) PolicyStatus status,
            @Parameter(description = "商品代碼")
            @RequestParam(required = false) String productCode,
            @Parameter(description = "銷售通路")
            @RequestParam(required = false) Channel channel,
            @Parameter(description = "生效日起（YYYY-MM-DD）")
            @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDateFrom,
            @Parameter(description = "生效日迄（YYYY-MM-DD）")
            @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDateTo,
            @PageableDefault(size = 20, sort = "effectiveDate", direction = Sort.Direction.DESC)
                Pageable pageable) {

        PolicySearchCriteria criteria = new PolicySearchCriteria(
                holderIdNumber, status, productCode, channel,
                effectiveDateFrom, effectiveDateTo);

        return service.search(criteria, pageable);
    }
}
