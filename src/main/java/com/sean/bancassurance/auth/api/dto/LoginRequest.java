package com.sean.bancassurance.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登入請求。
 *
 *  ── 為什麼用 record？─────────────────────────────────────────────────
 *  Java 16+ record 對 immutable DTO 是天然解：
 *    - 自動生 constructor / accessor / equals / hashCode / toString
 *    - 不可變 → thread-safe，跨 controller / service 傳遞無風險
 *    - 跟 Lombok @Value 等價，但是「語言原生」不依賴 annotation processor
 *
 *  ── 為什麼欄位叫 password 不叫 passwordHash？─────────────────────────
 *  這是 client 送來的「明碼密碼」，還沒雜湊。
 *  AppUser.passwordHash 是「DB 裡存的 BCrypt 雜湊」。
 *  名稱清楚分開，避免混淆。
 *
 *  ── 安全細節：record 的 toString 會洩漏 password ────────────────────
 *
 *  Java record 自動生的 toString() 是「全欄位印出」，含 password。
 *  如果 controller 出錯誤 log 印 request，password 會明文落到 log file。
 *
 *  我們的防護：
 *    1. ApiResponseWrapper / GlobalExceptionHandler 不會 toString request body
 *    2. controller 自己也不要 log.info(req)
 *    3. 還是要小心 — 如果未來有人想 log，要主動覆寫 toString()
 *
 *  另一個正規做法：用 Lombok @ToString.Exclude 配合 class，但 record 不支援。
 *  最徹底的解法：自訂 toString()。本案因為 controller 無 log，先不做，
 *  M9_SMOKE_TEST.md 會把這個列在 follow-up。
 */
@Schema(description = "登入請求")
public record LoginRequest(

        @Schema(description = "登入帳號", example = "underwriter01")
        @NotBlank(message = "username must not be blank")
        @Size(max = 64, message = "username length must be <= 64")
        String username,

        @Schema(description = "明文密碼，伺服器端會用 BCrypt 比對", example = "uw123")
        @NotBlank(message = "password must not be blank")
        @Size(max = 128, message = "password length must be <= 128")
        String password
) {
}
