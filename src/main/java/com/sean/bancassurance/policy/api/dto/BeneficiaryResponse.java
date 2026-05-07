package com.sean.bancassurance.policy.api.dto;

import com.sean.bancassurance.policy.domain.Beneficiary;
import com.sean.bancassurance.policy.domain.BeneficiaryRelationship;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 受益人對外 DTO。
 *
 * 為什麼不直接回 Entity？
 *   1. 序列化時 Hibernate proxy / lazy collection 會炸 (LazyInitializationException 或 stack overflow)
 *   2. 內部欄位 (version / isDeleted / 父端 reference) 不應外洩
 *   3. 身分證要遮罩
 */
public record BeneficiaryResponse(
        UUID id,
        String name,
        String maskedIdNumber,
        BeneficiaryRelationship relationship,
        BigDecimal allocationPercentage,
        Integer priority
) {
    public static BeneficiaryResponse from(Beneficiary b) {
        return new BeneficiaryResponse(
                b.getId(),
                b.getName(),
                mask(b.getIdNumber()),
                b.getRelationship(),
                b.getAllocationPercentage(),
                b.getPriority()
        );
    }

    private static String mask(String idNumber) {
        if (idNumber == null || idNumber.length() < 6) return "***";
        int len = idNumber.length();
        return idNumber.substring(0, 3) + "*".repeat(len - 6) + idNumber.substring(len - 3);
    }
}
