package com.sean.bancassurance.underwriting.api.dto;

import com.sean.bancassurance.underwriting.domain.Channel;
import com.sean.bancassurance.underwriting.domain.UnderwritingCase;
import com.sean.bancassurance.underwriting.domain.UnderwritingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 對外回應的 DTO。
 *
 * 與 Request 對稱：保留靜態工廠方法 from(Entity)，
 * 把 Entity → DTO 的轉換集中在這個類別，避免到處散落。
 *
 * 為什麼用 static factory 而不是 MapStruct？
 *  - M2 階段欄位數不多，純手刻反而清楚
 *  - 之後欄位變多 (5 個 entity / 30 個欄位以上) 再考慮 MapStruct，
 *    它會在編譯期生成 mapping code，效能好且型別安全
 *
 * 注意敏感欄位處理：
 *  - 預設不回 applicantIdNumber (身分證遮罩)
 *  - 不回 isDeleted / version 等內部技術欄位 → 不洩漏實作細節
 */
public record UnderwritingCaseResponse(
        UUID id,
        String caseNumber,
        String applicantName,
        String maskedApplicantIdNumber,   // 遮罩後的身分證號
        String productCode,
        BigDecimal coverageAmount,
        BigDecimal premium,
        Channel channel,
        UnderwritingStatus status,
        String submittedBy,
        String reviewedBy,
        String reviewComment,
        Instant submittedAt,
        Instant reviewedAt,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * 將 Entity 轉成對外 DTO。
     * Entity ─▶ DTO 的轉換邏輯放這裡，Service 只要呼叫 from(...) 即可。
     */
    public static UnderwritingCaseResponse from(UnderwritingCase entity) {
        return new UnderwritingCaseResponse(
                entity.getId(),
                entity.getCaseNumber(),
                entity.getApplicantName(),
                maskIdNumber(entity.getApplicantIdNumber()),
                entity.getProductCode(),
                entity.getCoverageAmount(),
                entity.getPremium(),
                entity.getChannel(),
                entity.getStatus(),
                entity.getSubmittedBy(),
                entity.getReviewedBy(),
                entity.getReviewComment(),
                entity.getSubmittedAt(),
                entity.getReviewedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    /**
     * 身分證遮罩規則：保留前 3 後 3，中間打 *。
     * 例：A123456789 → A12***789
     * 真實系統會用 SecurityProperties 控制是否顯示 (依角色權限)。
     */
    private static String maskIdNumber(String idNumber) {
        if (idNumber == null || idNumber.length() < 6) {
            return "***";
        }
        int len = idNumber.length();
        return idNumber.substring(0, 3) + "*".repeat(len - 6) + idNumber.substring(len - 3);
    }
}
