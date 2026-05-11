package com.sean.bancassurance.auth.filter;

import com.sean.bancassurance.auth.domain.AppUserPrincipal;
import com.sean.bancassurance.auth.service.JwtService;
import com.sean.bancassurance.auth.service.JwtService.InvalidJwtException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * JWT 認證過濾器 — 每個 request 進來時：
 *   1. 取 Authorization header
 *   2. 抽出 Bearer token
 *   3. JwtService 驗 token + 解 claims
 *   4. 用 claims 重建 Authentication 物件 → 放進 SecurityContext
 *   5. 繼續 filter chain
 *
 *  ── 為什麼 extends OncePerRequestFilter 而不是 implements Filter？───────
 *
 *  Servlet 容器在某些場景會「重新 dispatch」request：
 *    - forward (RequestDispatcher.forward())
 *    - error page 處理 (例如 500 → /error)
 *    - async dispatch
 *  若用裸 Filter 介面，這些 dispatch 會「再跑一次」整個 filter chain，
 *  造成 JWT 被重複解析 (浪費 CPU) 或 SecurityContext 被覆蓋。
 *
 *  OncePerRequestFilter 內部用 ServletRequest attribute 標記「跑過了沒」，
 *  保證一次 HTTP request 只 doFilterInternal 一次。Spring Security 自家的 filter
 *  幾乎全部都 extends 這個。
 *
 *  (面試題 / 中級)：「OncePerRequestFilter 為什麼要 once？」
 *    答：避免 forward / dispatch 重複跑。沒有「once」會出現「同一 request 兩次認證」、
 *        「同一 request 兩條 audit log」這類詭異 bug。
 *
 *  ── 為什麼 token 驗證失敗「不丟例外」而是放行？──────────────────────────
 *
 *  Filter 在 DispatcherServlet 之前，丟 RuntimeException 不會被 @RestControllerAdvice
 *  接到，會直接掉到 Servlet container 預設 error page。
 *
 *  Spring Security 的標準做法：
 *    - Filter 不丟，而是「不 set SecurityContext」(或 set 成 anonymous)
 *    - 後面的 AuthorizationFilter 發現「需要 auth 但沒 auth」→ 觸發
 *      AuthenticationEntryPoint.commence(...) → 由 entry point 寫 401 JSON
 *
 *  我們的 RestAuthenticationEntryPoint 就是接這個 callback 的。
 *
 *  ── 為什麼不從 DB 重撈 AppUser？──────────────────────────────────────
 *
 *  JWT 的核心優勢就是 stateless — token 自帶 username + roles，filter 不必查 DB。
 *  代價：role 變更要等 token 過期才生效 (見 JwtService 註解的「JWT 怎麼登出」一段)。
 *
 *  本案選擇純 stateless：filter 從 claims 重建 AppUserPrincipal，缺哪個欄位
 *  (例如 displayName) 就先 null/空 — controller 真要顯示時再從 DB 撈。
 *  M9.5 把 X-Actor 換成 SecurityContext 時，只需要 username + userId，足夠。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String token = extractBearerToken(request);

        if (token != null) {
            try {
                Claims claims = jwtService.parseAndValidate(token);
                Authentication auth = buildAuthentication(claims, request);
                // ★ 設 SecurityContext —— 後面的 AuthorizationFilter / @PreAuthorize 才看得到
                SecurityContextHolder.getContext().setAuthentication(auth);

                if (log.isDebugEnabled()) {
                    log.debug("JWT auth ok: user={}, authorities={}, uri={}",
                            auth.getName(), auth.getAuthorities(), request.getRequestURI());
                }
            } catch (InvalidJwtException e) {
                // 不丟例外、不 set Context → 後面 entry point 會給 401
                // 但要清空，避免上一個 request 殘留 (理論上 SecurityContextHolder 是
                // ThreadLocal + Spring 自動 clear，但 stateless 模式下保險起見)
                SecurityContextHolder.clearContext();
                log.debug("JWT auth failed at uri={}: {}", request.getRequestURI(), e.getMessage());
            }
        }
        // 不論有沒有 token、token 對不對，都繼續 chain。最後由 AuthorizationFilter
        // 配合 SecurityFilterChain 規則決定要不要擋下來。
        filterChain.doFilter(request, response);
    }

    /**
     * 取 Authorization header 的 Bearer token。
     *
     *  RFC 6750: Authorization: Bearer <token>
     *  若：
     *    - header 不存在
     *    - 不以 "Bearer " 開頭
     *    - 後面內容空白
     *  → 回 null，caller 視為「沒帶 token」
     */
    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * 從 claims 重建 Authentication。
     *
     *  我們建的是 UsernamePasswordAuthenticationToken — Spring Security 最常用的型別。
     *  它有兩個 constructor：
     *    new UPAT(principal, credentials)               ← 「未認證」狀態
     *    new UPAT(principal, credentials, authorities) ← 「已認證」狀態 (帶 authorities)
     *
     *  ★ 一定要用第二個 (帶 authorities)！第一個版本內部會把 authenticated=false。
     *
     *  principal 我們塞 AppUserPrincipal — 跟 login 流程一致，下游 (@PreAuthorize 的 SpEL、
     *  controller 用 @AuthenticationPrincipal 注入) 拿到的物件型別都統一。
     *  缺：filter 不查 DB，所以 displayName / passwordHash / enabled 是「token 簽發時的快照」。
     */
    private Authentication buildAuthentication(Claims claims, HttpServletRequest request) {
        String username = claims.getSubject();
        UUID userId = jwtService.extractUserId(claims);
        List<String> roleStrings = jwtService.extractRoles(claims);

        List<GrantedAuthority> authorities = roleStrings.stream()
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();

        // 重建 principal — 注意 token 沒有 displayName / passwordHash，這裡塞 placeholder
        // 真要顯示 displayName 的 controller 應該從 DB 撈最新值
        AppUserPrincipal principal = new AppUserPrincipal(
                userId,
                username,
                "",                     // passwordHash 不放進 principal (token 內也沒有)
                username,               // displayName 暫用 username 替代；要精確顯示請查 DB
                true,                   // enabled — token 簽發時是 enabled，過期前都當 enabled
                authorities
        );

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);

        // WebAuthenticationDetails 帶 remoteIp / sessionId 進 auth 物件，
        // audit log / 監控可從 SecurityContext 取得 client IP，不必再傳 HttpServletRequest
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        return auth;
    }
}
