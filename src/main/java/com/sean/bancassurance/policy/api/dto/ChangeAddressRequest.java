package com.sean.bancassurance.policy.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 變更地址請求 — 最簡單的 PATCH，只動一個 column。
 *
 * 為什麼欄位設計成這樣？
 *
 *  expectedVersion (Long, required)
 *      樂觀鎖前置檢查 — client 必須告訴 server「我看到的版本是 N」。
 *      Service 比對 N == policy.version：
 *        相符 → 繼續變更，commit 後 version 變 N+1
 *        不符 → 拋 PreconditionFailedException → 412
 *
 *      ★ 為什麼用 body 不用 header (If-Match)？
 *        兩者都做 — header 可選。body 比較直觀，header 是 RFC 標準。
 *        Service 層會優先讀 header；header 沒帶才看 body。
 *
 *  newAddress (String, required, 1-255 chars)
 *      新地址。@NotBlank：null / 空字串 / 純空白都拒。
 *
 *  reason (String, optional, max 500 chars)
 *      變更原因 — 寫進 PolicyChangeLog。client 可不填，但金融業實務上強烈建議填。
 *
 *  ── @NotNull vs @NotBlank vs @NotEmpty ─────────────────────────────
 *    @NotNull  ：物件不為 null。空字串 "" 算合法
 *    @NotEmpty ：物件不為 null 且 length > 0
 *    @NotBlank ：物件不為 null 且 trim 後 length > 0 (字串專用)
 *    Long 用 @NotNull；String 用 @NotBlank。
 */
public record ChangeAddressRequest(

        @NotNull(message = "expectedVersion is required for optimistic locking")
        @Min(value = 0, message = "expectedVersion must be >= 0")
        Long expectedVersion,

        @NotBlank(message = "newAddress is required")
        @Size(max = 255, message = "newAddress must be <= 255 characters")
        String newAddress,

        @Size(max = 500, message = "reason must be <= 500 characters")
        String reason
) {
}
