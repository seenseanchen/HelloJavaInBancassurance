package com.sean.bancassurance.policy.api.dto;

import com.sean.bancassurance.policy.domain.BeneficiaryRelationship;
import io.swagger.v3.oas.annotations.media.Schema;
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
 * 全量替換策略：client 送一份完整新清單，server 全刪再全插。
 * 配合 orphanRemoval=true：從 beneficiaries list 移除的元素會自動 DELETE。
 *
 * BigDecimal 而非 double 原因：浮點精度在金融業不可接受。
 * @Digits(integer=3, fraction=2)：對應 DB 的 NUMERIC(5,2)。
 */
@Schema(description = "受益人資料（全量替換清單中的單一筆）")
public record BeneficiaryUpsert(

        @Schema(description = "受益人姓名", example = "陳大美")
        @NotBlank(message = "name is required")
        @Size(max = 64, message = "name must be <= 64 characters")
        String name,

        @Schema(description = "受益人身分證號（台灣格式：1 英文字母 + 9 數字）", example = "B234567890")
        @NotBlank(message = "idNumber is required")
        @Pattern(
                regexp = "^[A-Z][0-9]{9}$",
                message = "idNumber must match Taiwan ID pattern (1 letter + 9 digits)"
        )
        String idNumber,

        @Schema(description = "與要保人之關係",
                example = "SPOUSE",
                allowableValues = {"SPOUSE", "CHILD", "PARENT", "SIBLING", "OTHER"})
        @NotNull(message = "relationship is required")
        BeneficiaryRelationship relationship,

        @Schema(description = "分配比例（%），所有受益人加總必須 = 100.00",
                example = "60.00",
                minimum = "0.01", maximum = "100.00")
        @NotNull(message = "allocationPercentage is required")
        @DecimalMin(value = "0.01", message = "allocationPercentage must be > 0")
        @DecimalMax(value = "100.00", message = "allocationPercentage must be <= 100")
        @Digits(integer = 3, fraction = 2)
        BigDecimal allocationPercentage,

        @Schema(description = "受益順位（1 = 第一順位，數字越小越優先）。至少一位必須為 1。",
                example = "1",
                minimum = "1")
        @NotNull(message = "priority is required")
        @Min(value = 1, message = "priority must be >= 1")
        Integer priority

) {}
