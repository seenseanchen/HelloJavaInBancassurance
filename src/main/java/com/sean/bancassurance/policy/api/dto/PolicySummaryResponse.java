package com.sean.bancassurance.policy.api.dto;

import com.sean.bancassurance.policy.domain.Policy;
import com.sean.bancassurance.policy.domain.PolicyStatus;
import com.sean.bancassurance.underwriting.domain.Channel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 保單「清單頁」DTO — 不含受益人，避免 N+1。
 *
 *  使用時機：GET /api/policies?... (分頁清單)
 *
 *  為什麼跟 PolicyResponse 分兩個 DTO？
 *    1. 列表需要的欄位少 (前端 table 顯示)，把 beneficiaries 撈出來毫無意義
 *    2. 強制 Service 走「不 fetch」的路徑 → 避免不小心觸發 N+1
 *    3. 對外契約清楚：list 跟 detail API 回傳結構不同是常態，分 DTO 反而更穩定
 *
 *  (面試題 / 中級)：「DTO 數量會不會爆炸？怎麼管理？」
 *    答：以「使用情境」分而非「Entity」分。常見命名：
 *        - XxxResponse        : 單筆完整回傳
 *        - XxxSummaryResponse : 列表頁用、欄位精簡
 *        - XxxCreateRequest   : 建立用
 *        - XxxUpdateRequest   : 變更用 (M5)
 */
public record PolicySummaryResponse(
        UUID id,
        String policyNumber,
        String productCode,
        String holderName,
        BigDecimal coverageAmount,
        Channel channel,
        PolicyStatus status,
        LocalDate effectiveDate,
        LocalDate expiryDate
) {
    public static PolicySummaryResponse from(Policy p) {
        return new PolicySummaryResponse(
                p.getId(),
                p.getPolicyNumber(),
                p.getProductCode(),
                p.getHolderName(),
                p.getCoverageAmount(),
                p.getChannel(),
                p.getStatus(),
                p.getEffectiveDate(),
                p.getExpiryDate()
        );
    }
}
