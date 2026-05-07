package com.sean.bancassurance.policy.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 變更受益人請求 — 集合「全量替換」策略。
 *
 * @Valid 在 list 欄位上的必要性：
 *   若沒有 @Valid，list element (BeneficiaryUpsert) 內部的
 *   @NotBlank / @Min 等 annotation 不會被觸發，只檢查 list 本身的 @NotEmpty / @Size。
 *   @Valid 是「遞迴向下驗證」的開關。
 */
@Schema(description = "變更受益人請求（全量替換：送入完整新清單，舊清單全部刪除）")
public record ChangeBeneficiariesRequest(

        @Schema(description = "樂觀鎖版本號（從 GET 單筆 ETag 或 response.version 取得）",
                example = "0",
                minimum = "0")
        @NotNull(message = "expectedVersion is required")
        @Min(value = 0, message = "expectedVersion must be >= 0")
        Long expectedVersion,

        @Schema(description = """
                受益人完整清單（全量替換，舊清單全部刪除再重建）。

                業務規則：
                - `allocationPercentage` 加總必須 = **100.00**
                - 至少一筆 `priority` = **1**（第一順位受益人）
                - 最多 10 筆
                """)
        @NotEmpty(message = "beneficiaries cannot be empty (at least one required)")
        @Size(max = 10, message = "beneficiaries cannot exceed 10")
        @Valid
        List<BeneficiaryUpsert> beneficiaries,

        @Schema(description = "變更原因（選填，建議填寫以利稽核）",
                example = "結婚，新增配偶為受益人",
                nullable = true)
        @Size(max = 500, message = "reason must be <= 500 characters")
        String reason

) {}
