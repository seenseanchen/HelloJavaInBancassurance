package com.sean.bancassurance.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 登入成功回應。
 *
 *  ── 為什麼這樣設計欄位？──────────────────────────────────────────────
 *
 *  accessToken / tokenType / expiresIn 這三個欄位是 OAuth 2.0 RFC 6749 的標準格式
 *  (Section 5.1 — "Successful Response")，雖然我們沒做 OAuth2 完整 flow，仍沿用
 *  這個約定 → 跟業界對齊，client lib 都認得這套：
 *
 *      {
 *        "access_token": "eyJ...",
 *        "token_type": "Bearer",
 *        "expires_in": 3600
 *      }
 *
 *  我們用 camelCase (accessToken / tokenType / expiresIn) 因為 Jackson 預設策略，
 *  且整個 API 風格一致。若要 100% 對齊 OAuth2 (snake_case)，可在 LoginResponse
 *  上加 @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)。
 *
 *  username / displayName / roles 是給 client UI 用：登入後顯示「歡迎 王核保」、
 *  根據 roles 隱藏不該看到的選單。這些資料 token 內也有 (parse 出來就有)，
 *  但讓 client 直接從 response 拿比較方便，不必每次都解 token。
 *
 *  ── 為什麼回 roles？─────────────────────────────────────────────────
 *  前端 UI 常需要做「條件渲染」(角色不對的按鈕灰掉 / 隱藏)。
 *  Server 仍是最後防線：API 層的 @PreAuthorize 才是真權限控制。
 *  前端 roles 只是 UX 友善，被 client 改也沒關係 — 改了打 API 還是會被 server 擋。
 *
 *  (面試題 / 中級)：「前端拿到 roles 之後，client 偷改 roles 能繞過權限嗎？」
 *    答：不行。Server 每次 request 用的是 token 裡的 roles (signature 防竄改) +
 *        @PreAuthorize 在 backend 真實檢查。client 改自己看到的 JS 變數沒用。
 */
@Schema(description = "登入成功回應，含 JWT access token")
public record LoginResponse(

        @Schema(description = "JWT access token (HS256 簽發)",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOi...")
        String accessToken,

        @Schema(description = "Token 類型，永遠是 Bearer", example = "Bearer")
        String tokenType,

        @Schema(description = "Token 存活秒數 (從簽發時刻算起)", example = "3600")
        long expiresIn,

        @Schema(description = "登入帳號", example = "underwriter01")
        String username,

        @Schema(description = "顯示名稱 (UI 用)", example = "王核保")
        String displayName,

        @Schema(description = "角色清單 (Spring Security authority 格式)",
                example = "[\"ROLE_UNDERWRITER\"]")
        List<String> roles
) {

    /**
     * Factory：固定 tokenType = "Bearer"。
     */
    public static LoginResponse of(String accessToken, long ttlSeconds,
                                   String username, String displayName,
                                   List<String> roles) {
        return new LoginResponse(accessToken, "Bearer", ttlSeconds, username, displayName, roles);
    }
}
