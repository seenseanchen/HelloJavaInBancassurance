package com.sean.bancassurance.auth.config;

import com.sean.bancassurance.auth.filter.JwtAuthenticationFilter;
import com.sean.bancassurance.auth.web.RestAccessDeniedHandler;
import com.sean.bancassurance.auth.web.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全設定 — M9 完整版。
 *
 *  ── SecurityFilterChain 的全景 ────────────────────────────────────────
 *
 *  Spring Security 6+ 棄用 WebSecurityConfigurerAdapter，改用 SecurityFilterChain bean
 *  + lambda DSL 風格。我們這個 bean 等於告訴 Spring：「整套 Servlet Filter 鏈長這樣」。
 *
 *  Filter 順序 (簡化版，實際還有十幾個)：
 *      DisableEncodeUrlFilter
 *      WebAsyncManagerIntegrationFilter
 *      SecurityContextHolderFilter         ← 載入 SecurityContext (stateless 模式下空的)
 *      HeaderWriterFilter                   ← 加 X-Content-Type-Options 等安全 header
 *      CsrfFilter                           ← 我們關掉
 *      LogoutFilter
 *      JwtAuthenticationFilter ★            ← 我們的 filter，set SecurityContext
 *      UsernamePasswordAuthenticationFilter ← 預設 form login，我們不用，但 addFilterBefore
 *                                             以它當錨點 (它的順序是固定的)
 *      ...
 *      ExceptionTranslationFilter           ← 把 AuthenticationException / AccessDeniedException
 *                                             轉到 entry point / access denied handler
 *      AuthorizationFilter                  ← 比對 .requestMatchers() 規則決定 200/401/403
 *      → DispatcherServlet → @Controller
 *
 *  ── 設定的關鍵決策 ────────────────────────────────────────────────────
 *
 *  (1) CSRF disable
 *      CSRF (Cross-Site Request Forgery) 是攻擊「瀏覽器自動帶 cookie 登入」的場景。
 *      我們是 stateless JWT — 沒有 cookie session、token 必須 client 主動放在
 *      Authorization header (瀏覽器不會自動帶)，根本沒 CSRF 攻擊面。
 *      Spring Security 預設啟 CSRF (預設 form-login 流程需要)，REST API 一律關掉。
 *
 *      (面試題 / 中級)：「為什麼 stateless API 不需要 CSRF protection？」
 *        答：CSRF 攻擊本質是「利用瀏覽器自動帶 cookie」。JWT 走 Authorization header，
 *           瀏覽器不會自動加 → 攻擊者沒法在受害者瀏覽器上偽造帶 token 的請求。
 *
 *  (2) sessionManagement → STATELESS
 *      不創建 HttpSession、不從 session 讀寫 SecurityContext。
 *      每個 request 都是獨立的 → JWT filter 解 token 重新建 SecurityContext。
 *      預設策略 ALWAYS / IF_REQUIRED / NEVER / STATELESS 四種；REST API 一律 STATELESS。
 *
 *  (3) authorizeHttpRequests
 *      路徑授權的「粗粒度」規則。我們分四組：
 *
 *        permitAll：
 *          POST /api/auth/login              ← 登入本身不能要登入 (廢話)
 *          /swagger-ui/** + /api-docs/**     ← API 文件給開發者參考，不擋
 *          /actuator/health + /actuator/info ← LB / k8s probe 必須能無認證訪問
 *          /error                            ← Spring Boot 內部錯誤頁
 *
 *        其他 → authenticated() 兜底，所有業務 endpoint 都要登入。
 *
 *      M9.5 會用 @PreAuthorize 在 method 層加細粒度規則 (UNDERWRITER 才能 approve 等)，
 *      所以這層只擋「沒登入」即可，不細分角色。
 *
 *  (4) exceptionHandling
 *      把我們的 RestAuthenticationEntryPoint / RestAccessDeniedHandler 接上去。
 *      預設行為 (HTML 頁 / 403) 對 REST client 不友善。
 *
 *  (5) addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
 *      把我們的 filter 插在 UsernamePasswordAuthenticationFilter 之前。
 *      實際上 UPAF 在 stateless + form-login disabled 模式下根本沒動作，
 *      但用它當「錨點」是 Spring Security 慣例 (順序穩定，文件範例都這樣寫)。
 *
 *  (面試題 / 中級)：「Spring Security 5.7+ 為什麼不用 WebSecurityConfigurerAdapter？」
 *    答：lambda DSL 比 override method 更具組合性 (composability)，
 *        且 SecurityFilterChain bean 可以多個共存 (不同 chain 對不同路徑 prefix)，
 *        Adapter 是單例難以拆分。Spring Security 5.7 deprecate / 6.0 移除。
 */
/**
 *  ── @EnableMethodSecurity (M9.5) ───────────────────────────────────────
 *
 *  啟用 method 層級的權限檢查，讓 @PreAuthorize / @PostAuthorize / @Secured 生效。
 *
 *      prePostEnabled = true (預設) → @PreAuthorize / @PostAuthorize 生效
 *      securedEnabled = false (預設) → 舊版 @Secured 不啟用 (語法弱，不推薦)
 *      jsr250Enabled  = false (預設) → JSR-250 @RolesAllowed 不啟用 (Spring 自己的 @PreAuthorize 更強)
 *
 *  Spring Security 6 把舊的 @EnableGlobalMethodSecurity deprecate 掉，
 *  新版叫 @EnableMethodSecurity，內部用 AuthorizationManager 而非 AccessDecisionManager
 *  (架構簡化，效能好一些)。語意完全相同。
 *
 *  (面試題 / 中級)：「@PreAuthorize 跟 @Secured 差在哪？」
 *    @Secured("ROLE_ADMIN")        ：只能寫 role 字串
 *    @PreAuthorize("hasRole('X')") ：可寫任意 SpEL，支援
 *                                   - hasRole / hasAnyRole / hasAuthority
 *                                   - authentication.principal.id == #policy.holderId (參數比對)
 *                                   - @beanName.method(#arg)                          (呼叫 bean)
 *    結論：@PreAuthorize 表達力強得多，業界幾乎一面倒用它。
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    /**
     * BCrypt strength 沒設參數，預設 10 = 2^10 ≈ 100ms/次。
     * 細節說明見之前 M9.3 的 SecurityConfig 註解 (升級雜湊用 DelegatingPasswordEncoder)。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 主 SecurityFilterChain。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // 1. CSRF disable — REST API stateless，無 cookie 可被偽造
                .csrf(AbstractHttpConfigurer::disable)

                // 2. Stateless — 不開 HttpSession，每 request 獨立用 JWT 認證
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 3. CORS：暫時用預設 (Spring Boot 會檢查 @CrossOrigin)。
                //    M16 前端對接時若有跨域問題，這裡再寫 CorsConfigurationSource bean。
                //    注意：「.cors(Customizer.withDefaults())」會啟用 CORS filter。
                //    本案先不啟用，前端接通時再說。

                // 4. 路徑授權規則 — 粗粒度，method 層的 @PreAuthorize 是補刀
                .authorizeHttpRequests(auth -> auth
                        // 認證 endpoint：登入本身不能要登入
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()

                        // OpenAPI / Swagger UI：dev/staging 開放查看；prod 應用內網 IP 限制
                        .requestMatchers(
                                "/swagger-ui",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/api-docs",
                                "/api-docs/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**"
                        ).permitAll()

                        // Actuator：health / info 給 LB / k8s probe 用，不擋
                        // 其他 actuator endpoint (env / metrics / heapdump 等) 走 authenticated
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").authenticated()

                        // Spring Boot 內部 error 頁
                        .requestMatchers("/error").permitAll()

                        // 兜底：所有業務 endpoint 都需要登入
                        // M9.5 會在 method 層用 @PreAuthorize 加細粒度角色檢查
                        .anyRequest().authenticated()
                )

                // 5. 自訂 401 / 403 回應 (REST API 風格的 JSON ApiError)
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )

                // 6. ★ 把 JWT filter 插進 chain
                //    位置在 UsernamePasswordAuthenticationFilter 之前 (它在 AuthorizationFilter 之前，
                //    所以我們的 filter 也在 AuthorizationFilter 之前 — 這是 set SecurityContext 的時機)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

                .build();
    }
}
