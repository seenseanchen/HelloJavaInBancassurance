package com.sean.bancassurance.policy.api.dto;

import com.sean.bancassurance.policy.domain.BeneficiaryRelationship;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 「待寫入 / 更新」的單一受益人 — 是 ChangeBeneficiariesRequest 的 list element。
 *
 * 為什麼叫 Upsert？
 *   M5 的策略是「全量替換」：client 送一份完整的 beneficiaries list，
 *   server 把舊的全部 DELETE、新的全部 INSERT (orphanRemoval=true 自動處理)。
 *   不像 PATCH 那種「只送變更項」— 那種需要 client 告訴你「這列 update / 那列 delete」，
 *   protocol 設計複雜很多。
 *
 *   全量替換的優點：
 *     - protocol 直觀：「給我新的完整清單」
 *     - server 不用 diff，邏輯簡單，bug 少
 *   缺點：
 *     - 如果客戶只想改一個受益人的比例，仍要送全部 — 客戶端負擔較重
 *     - 但每張保單通常 1-3 位受益人，payload 不大，OK
 *
 * 為什麼欄位用 BigDecimal 不用 double？(再強調一次)
 *   double 在 0.1 + 0.2 = 0.30000000000000004 這種地方會炸。金融業禁用 floating point。
 *   BigDecimal 是 arbitrary precision，scale 控制小數位。
 *
 * @Digits(integer=3, fraction=2)：整數最多 3 位 (0~999)、小數最多 2 位 — 對應 NUMERIC(5,2)。
 */
public record BeneficiaryUpsert(

        @NotBlank(message = "name is required")
        @Size(max = 64, message = "name must be <= 64 characters")
        String name,

        @NotBlank(message = "idNumber is required")
        @Pattern(
                regexp = "^[A-Z][0-9]{9}$",
                message = "idNumber must match Taiwan ID pattern (1 letter + 9 digits)"
        )
        String idNumber,

        @NotNull(message = "relationship is required")
        BeneficiaryRelationship relationship,

        @NotNull(message = "allocationPercentage is required")
        @DecimalMin(value = "0.01", message = "allocationPercentage must be > 0")
        @DecimalMax(value = "100.00", message = "allocationPercentage must be <= 100")
        @Digits(integer = 3, fraction = 2)
        BigDecimal allocationPercentage,

        @NotNull(message = "priority is required")
        @Min(value = 1, message = "priority must be >= 1")
        Integer priority
) {
}
