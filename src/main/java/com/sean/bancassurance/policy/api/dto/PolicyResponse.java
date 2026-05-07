package com.sean.bancassurance.policy.api.dto;

import com.sean.bancassurance.policy.domain.Policy;
import com.sean.bancassurance.policy.domain.PolicyStatus;
import com.sean.bancassurance.policy.domain.PremiumPaymentMethod;
import com.sean.bancassurance.underwriting.domain.Channel;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 保單「單筆查詢」DTO — 回傳完整資訊，包含受益人清單。
 *
 *  使用時機：GET /api/policies/{id}、GET /by-number/{policyNumber}
 *
 *  ★ 注意：呼叫 from(...) 之前，Service 必須確保 beneficiaries 已被 fetch
 *    (用 findByIdWithBeneficiaries 而非 findById)。否則交易結束後再 .getBeneficiaries()
 *    會丟 LazyInitializationException。
 *
 *  為什麼把 version 也回出去？
 *    M5 的 PATCH 變更 API 會要求 client 帶上 version (或 If-Match: ETag)，
 *    所以 client 需要看得到目前版本號。
 *    這跟 UnderwritingCaseResponse 不同 — 那邊 M2 沒做變更，version 暫時不外露。
 */
public record PolicyResponse(
        UUID id,
        String policyNumber,
        String productCode,
        UUID underwritingCaseId,

        // 要保人
        String holderName,
        String maskedHolderIdNumber,

        // 被保險人
        String insuredName,
        String maskedInsuredIdNumber,

        // 金額
        BigDecimal coverageAmount,
        BigDecimal premium,
        PremiumPaymentMethod premiumPaymentMethod,

        // 通路 / 狀態
        Channel channel,
        PolicyStatus status,

        // 業務日期
        LocalDate effectiveDate,
        LocalDate expiryDate,

        String billingAddress,

        // 受益人
        List<BeneficiaryResponse> beneficiaries,

        // 給 M5 的樂觀鎖版本號
        Long version,

        // 稽核
        Instant createdAt,
        Instant updatedAt
) {
    public static PolicyResponse from(Policy p) {
        return new PolicyResponse(
                p.getId(),
                p.getPolicyNumber(),
                p.getProductCode(),
                p.getUnderwritingCaseId(),
                p.getHolderName(),
                mask(p.getHolderIdNumber()),
                p.getInsuredName(),
                mask(p.getInsuredIdNumber()),
                p.getCoverageAmount(),
                p.getPremium(),
                p.getPremiumPaymentMethod(),
                p.getChannel(),
                p.getStatus(),
                p.getEffectiveDate(),
                p.getExpiryDate(),
                p.getBillingAddress(),
                p.getBeneficiaries().stream().map(BeneficiaryResponse::from).toList(),
                p.getVersion(),
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }

    private static String mask(String idNumber) {
        if (idNumber == null || idNumber.length() < 6) return "***";
        int len = idNumber.length();
        return idNumber.substring(0, 3) + "*".repeat(len - 6) + idNumber.substring(len - 3);
    }
}
