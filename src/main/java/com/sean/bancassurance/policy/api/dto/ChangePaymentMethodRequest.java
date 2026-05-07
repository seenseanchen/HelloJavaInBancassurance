package com.sean.bancassurance.policy.api.dto;

import com.sean.bancassurance.policy.domain.PremiumPaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 變更繳費方式請求。
 *
 * 業務規則（由 Service 層檢查）：
 *   - 不允許將繳費方式改為 SINGLE_PAY（躉繳只能在新單投保時選擇）
 *   - 其他 MONTHLY / QUARTERLY / SEMI_ANNUAL / ANNUAL 可互換
 *
 * Jackson enum 反序列化：預設區分大小寫，需與 enum constant 名稱完全相符。
 * 不認識的值 → InvalidFormatException → GlobalExceptionHandler → 400 MALFORMED_REQUEST
 */
@Schema(description = "變更繳費方式請求")
public record ChangePaymentMethodRequest(

        @Schema(description = "樂觀鎖版本號（從 GET 單筆 ETag 或 response.version 取得）",
                example = "0",
                minimum = "0")
        @NotNull(message = "expectedVersion is required")
        @Min(value = 0, message = "expectedVersion must be >= 0")
        Long expectedVersion,

        @Schema(description = """
                新繳費方式。

                | 值 | 說明 |
                |---|---|
                | `MONTHLY` | 月繳 |
                | `QUARTERLY` | 季繳 |
                | `SEMI_ANNUAL` | 半年繳 |
                | `ANNUAL` | 年繳 |
                | `SINGLE_PAY` | 躉繳（**禁止**透過變更申請，僅限新單）|
                """,
                example = "ANNUAL",
                allowableValues = {"MONTHLY", "QUARTERLY", "SEMI_ANNUAL", "ANNUAL"})
        @NotNull(message = "newPaymentMethod is required")
        PremiumPaymentMethod newPaymentMethod,

        @Schema(description = "變更原因（選填）",
                example = "客戶申請改為年繳，減少扣款次數",
                nullable = true)
        @Size(max = 500, message = "reason must be <= 500 characters")
        String reason

) {}
