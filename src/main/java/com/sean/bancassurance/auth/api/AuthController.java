package com.sean.bancassurance.auth.api;

import com.sean.bancassurance.auth.api.dto.LoginRequest;
import com.sean.bancassurance.auth.api.dto.LoginResponse;
import com.sean.bancassurance.auth.domain.AppUserPrincipal;
import com.sean.bancassurance.auth.service.JwtService;
import com.sean.bancassurance.common.exception.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 認證 Controller — POST /api/auth/login。
 *
 *  ── 為什麼不用 Spring Security 的 AuthenticationManager？──────────────────
 *
 *  Spring 標準做法：
 *      AuthenticationManager.authenticate(new UsernamePasswordAuthenticationToken(...))
 *      → DaoAuthenticationProvider 內部呼叫 UserDetailsService + PasswordEncoder
 *      → 回 Authentication 物件
 *
 *  我們這裡「手動」呼叫 UserDetailsService + PasswordEncoder。理由：
 *    1. 教學透明：每一步在做什麼一眼看穿，不用追兩層 framework 抽象
 *    2. 不必暴露 AuthenticationManager bean (M9.4 才寫 SecurityFilterChain)
 *    3. 自己控制例外型別 — 遇到 BadCredentialsException 統一回，不必處理
 *       AuthenticationManager 五花八門的子類例外
 *
 *  Production 寫法 (面試會問)：
 *      用 AuthenticationManager 比較標準，因為它支援多種 Provider 鏈式驗證
 *      (LDAP + DB + OAuth2 同時掛)。本案只接 DB，手寫沒差。
 *
 *  (面試題 / 資深)：「用 AuthenticationManager 跟自己手動驗 password，差在哪？」
 *    答：(1) AuthenticationManager 支援多 Provider 鏈，可橫向擴充
 *        (2) AuthenticationManager 整合 AuthenticationEvent (登入成功/失敗事件)，
 *            方便接 audit / metrics / 風控
 *        (3) 框架提供的 AccountStatusUserDetailsChecker 自動檢查
 *            disabled / locked / expired 等狀態
 *        手動驗就是把這些都自己刻一遍，code 量小但失去擴充性。
 *
 *  ── 安全細節：訊息要統一 ─────────────────────────────────────────────
 *
 *  不論「user 不存在」、「密碼錯」、「帳號 disabled」，全部回同一句
 *  「Bad credentials」 + 401。
 *
 *  ❌ 錯誤示範：「帳號不存在」(401) vs 「密碼錯誤」(401) — 攻擊者能列舉 user
 *  ❌ 錯誤示範：「帳號未啟用」洩漏「這個 user 真實存在但被停用」
 *  ✔ 統一訊息：對外不洩漏任何「為什麼登入失敗」的資訊；後台 log 記錄細節 debug 用
 */
@Slf4j
@Tag(
    name = "認證 (Authentication)",
    description = "登入取得 JWT access token。後續所有受保護 endpoint 須帶 `Authorization: Bearer <token>` header。"
)
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * POST /api/auth/login
     *
     *  流程：
     *    1. UserDetailsService.loadUserByUsername(...)：找不到 → UsernameNotFoundException
     *    2. PasswordEncoder.matches(rawPwd, hashedPwd)：不對 → 我們自己丟 BadCredentialsException
     *    3. UserDetails.isEnabled()：false → BadCredentialsException
     *    4. JwtService.issueToken(...)：簽 JWT
     *    5. 回 LoginResponse
     */
    @Operation(
        summary = "登入取得 JWT",
        description = """
            驗證 username + password，成功時回傳 JWT access token。

            **DEV 種子帳號** (M9.6 SMOKE_TEST 詳列)：
            - `admin` / `admin123` (ADMIN + UNDERWRITER + CSR)
            - `underwriter01` / `uw123` (UNDERWRITER)
            - `csr01` / `csr123` (CSR)

            **驗證失敗統一回 401 BAD_CREDENTIALS**，不論是 username 不存在、密碼錯、
            還是帳號被停用 — 對外不洩漏「為什麼失敗」(防使用者列舉攻擊)。
            """
    )
    @ApiResponse(
        responseCode = "200",
        description = "登入成功，回傳 JWT + 使用者資訊",
        content = @Content(
            schema = @Schema(implementation = LoginResponse.class),
            examples = @ExampleObject(
                name = "成功範例",
                value = """
                    {
                      "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOi...",
                      "tokenType": "Bearer",
                      "expiresIn": 3600,
                      "username": "underwriter01",
                      "displayName": "王核保",
                      "roles": ["ROLE_UNDERWRITER"]
                    }
                    """
            )
        )
    )
    @ApiResponse(
        responseCode = "401",
        description = "帳號或密碼錯誤 / 帳號被停用",
        content = @Content(schema = @Schema(implementation = ApiError.class))
    )
    @ApiResponse(
        responseCode = "400",
        description = "username / password 為空或超長",
        content = @Content(schema = @Schema(implementation = ApiError.class))
    )
    @PostMapping("/login")
    // 把 login 從全域 SecurityRequirement 排除 — Swagger UI 顯示時不會在這個 endpoint 上
    // 帶鎖頭 icon、try-it-out 也不會強塞 Authorization header
    // (空陣列 = 不需要任何 security scheme。如果寫 @SecurityRequirement(name="") 反而會被當錯誤)
    @SecurityRequirements({})
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {

        UserDetails user = loadUserOrFail(request.username());

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            // 不要 log 出明文密碼，連 username 都謹慎 — info 級就好
            log.info("Login failed: bad password for username={}", request.username());
            throw new BadCredentialsException("Bad credentials");
        }

        if (!user.isEnabled()) {
            log.info("Login failed: account disabled, username={}", request.username());
            throw new BadCredentialsException("Bad credentials");
        }

        // 我們的 UserDetailsService 一定回 AppUserPrincipal — cast 安全
        AppUserPrincipal principal = (AppUserPrincipal) user;
        String token = jwtService.issueToken(principal);

        List<String> roles = principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        log.info("Login success: username={}, roles={}", principal.username(), roles);

        return LoginResponse.of(
                token,
                jwtService.getTtlSeconds(),
                principal.username(),
                principal.displayName(),
                roles
        );
    }

    /**
     * 抽出來當 helper，把 UsernameNotFoundException 翻成 BadCredentialsException —
     * 確保「user 不存在」的 401 訊息跟「密碼錯」一致。
     */
    private UserDetails loadUserOrFail(String username) {
        try {
            return userDetailsService.loadUserByUsername(username);
        } catch (UsernameNotFoundException e) {
            log.info("Login failed: unknown username={}", username);
            throw new BadCredentialsException("Bad credentials");
        }
    }
}
