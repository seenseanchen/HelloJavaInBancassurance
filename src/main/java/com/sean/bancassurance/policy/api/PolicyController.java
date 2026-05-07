package com.sean.bancassurance.policy.api;

import com.sean.bancassurance.policy.api.dto.PolicyResponse;
import com.sean.bancassurance.policy.api.dto.PolicySummaryResponse;
import com.sean.bancassurance.policy.domain.PolicyStatus;
import com.sean.bancassurance.policy.service.PolicySearchCriteria;
import com.sean.bancassurance.policy.service.PolicyService;
import com.sean.bancassurance.underwriting.domain.Channel;
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
 *
 *  (面試題 / 中級)：「Spring 怎麼處理日期時間參數的反序列化？」
 *    答：- application.yml 的 spring.jackson.date-format / spring.mvc.format
 *        - 各 controller method 級別的 @DateTimeFormat
 *        - 自訂 Converter<String, LocalDate> 並 register 到 ConversionService
 *
 *  (面試題 / 資深)：「分頁參數 ?page=0&size=20&sort=field,desc 是 Spring 慣例還是標準？」
 *    答：是 Spring Data 慣例 (HandlerMethodArgumentResolver)，不是 HTTP / RFC 標準。
 *        對外公開 API 通常會包成自家 ApiPageRequest 避免「換框架就壞 API」。
 */
@RestController
@RequestMapping("/api/policies")
@RequiredArgsConstructor
public class PolicyController {

    private final PolicyService service;

    /**
     * GET /api/policies/{id}
     * 用內部 UUID 查 — 通常是其他系統（例如 M5 變更前）用 ID 直接定位。
     *
     * (M5 加) 回應帶 ETag header — 對應 PATCH 時的 If-Match。
     * 流程：client GET 拿到 ETag: "0" → 之後 PATCH 帶 If-Match: "0"
     * server 比對版本不符 → 412 Precondition Failed。
     *
     * RFC 7232 §2.3 規定 ETag 是 quoted-string；可選 W/ 前綴代表 "weak"。
     * 我們的版本號是 strong (DB 自增) → 直接 strong ETag。
     */
    @GetMapping("/{id}")
    public ResponseEntity<PolicyResponse> getById(@PathVariable UUID id) {
        PolicyResponse response = service.getById(id);
        return ResponseEntity.ok()
                .eTag("\"" + response.version() + "\"")
                .body(response);
    }

    /**
     * GET /api/policies/by-number/{policyNumber}
     * 客服對應客戶最常用：客戶報出保單號，客服查明細。
     */
    @GetMapping("/by-number/{policyNumber}")
    public ResponseEntity<PolicyResponse> getByPolicyNumber(@PathVariable String policyNumber) {
        PolicyResponse response = service.getByPolicyNumber(policyNumber);
        return ResponseEntity.ok()
                .eTag("\"" + response.version() + "\"")
                .body(response);
    }

    /**
     * GET /api/policies
     *   ?holderIdNumber=A123456789
     *   &status=IN_FORCE
     *   &productCode=LIFE-001
     *   &channel=BANCASSURANCE
     *   &effectiveDateFrom=2026-01-01
     *   &effectiveDateTo=2026-12-31
     *   &page=0&size=20&sort=effectiveDate,desc
     *
     * 所有過濾參數皆為選填；分頁有預設值。
     */
    @GetMapping
    public Page<PolicySummaryResponse> search(
            @RequestParam(required = false) String holderIdNumber,
            @RequestParam(required = false) PolicyStatus status,
            @RequestParam(required = false) String productCode,
            @RequestParam(required = false) Channel channel,
            @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDateFrom,
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
