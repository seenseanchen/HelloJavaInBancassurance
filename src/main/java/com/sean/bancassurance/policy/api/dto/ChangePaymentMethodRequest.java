package com.sean.bancassurance.policy.api.dto;

import com.sean.bancassurance.policy.domain.PremiumPaymentMethod;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 變更繳費方式請求 — enum 變更示範。
 *
 * Jackson 對 enum 的反序列化：
 *   - 預設只接「字面相符」的 constant 名 (大小寫敏感)
 *   - 不認識的值 → InvalidFormatException → GlobalExceptionHandler 接走
 *     (我們在 M3/M4 已寫了 HttpMessageNotReadableException handler，會回 400)
 *
 *  (面試題 / 中級)：「為什麼 enum 不用 Set<EnumX>，要用單值 + ConstraintValidator？」
 *    答：本欄位邏輯上「只能是其中一個值」用單值最自然。
 *        若要限制只允許某子集 (e.g. 不准選 SINGLE_PAY)，再寫 ConstraintValidator。
 *        本系統允許客戶換到任意一種繳費方式 (SINGLE_PAY 例外，由 service 額外擋)。
 */
public record ChangePaymentMethodRequest(

        @NotNull(message = "expectedVersion is required")
        @Min(value = 0, message = "expectedVersion must be >= 0")
        Long expectedVersion,

        @NotNull(message = "newPaymentMethod is required")
        PremiumPaymentMethod newPaymentMethod,

        @Size(max = 500, message = "reason must be <= 500 characters")
        String reason
) {
}
