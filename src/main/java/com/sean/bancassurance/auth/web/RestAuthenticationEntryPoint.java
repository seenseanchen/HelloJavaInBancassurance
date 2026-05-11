package com.sean.bancassurance.auth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sean.bancassurance.common.exception.ApiError;
import com.sean.bancassurance.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * 「沒登入 / token 無效」時的 401 回應。
 *
 *  ── 為什麼要自訂 entry point？─────────────────────────────────────────
 *
 *  Spring Security 預設行為：
 *    - HTTP Basic 模式：丟 BasicAuthenticationEntryPoint，回 401 + WWW-Authenticate header
 *    - 預設 form login：丟 LoginUrlAuthenticationEntryPoint，redirect 302 到 /login 頁面
 *    - 沒設定時 (Spring Security 6.x)：回 403 而不是 401 — 對 REST API client 很怪
 *
 *  我們是純 REST API：
 *    - 不要 HTML redirect (client 是 mobile / SPA / Postman)
 *    - 不要 WWW-Authenticate Basic (我們用 Bearer)
 *    - 一律回 JSON ApiError (跟 GlobalExceptionHandler 格式一致)
 *
 *  這個 entry point 在 SecurityConfig 用 .exceptionHandling().authenticationEntryPoint(this)
 *  掛上去，就會被 AuthorizationFilter 在「需要登入但沒登入」時呼叫。
 *
 *  ── 為什麼不丟例外讓 GlobalExceptionHandler 接？──────────────────────────
 *
 *  AuthorizationFilter 在 DispatcherServlet 之前，丟 RuntimeException 不會經過
 *  @RestControllerAdvice。Spring Security 設計上就是「在 filter chain 內部解掉，
 *  不依賴 MVC 層」— entry point + access denied handler 就是 servlet-level 的 advice。
 *
 *  所以這個檔案要自己呼叫 ObjectMapper 寫 JSON 進 response — 略冗，但這是
 *  Spring Security filter 模型必須的代價。
 *
 *  (面試題 / 資深)：「為什麼 Spring Security 的例外處理跟 MVC 的 @ControllerAdvice 分離？」
 *    答：filter chain 在 servlet container 層級，比 DispatcherServlet 早。
 *        @ControllerAdvice 只接 DispatcherServlet 內的例外。
 *        所以 401/403 必須在 filter 層回 — 用 entry point + access denied handler，
 *        這跟 MVC 的「業務例外 → 4xx」路徑是兩條不交叉的線。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        String traceId = MDC.get(TraceIdFilter.TRACE_ID_KEY);

        log.info("Auth required at uri={} (traceId={}): {}",
                request.getRequestURI(), traceId, authException.getMessage());

        ApiError body = new ApiError(
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                "AUTHENTICATION_REQUIRED",
                "Authentication is required to access this resource",
                request.getRequestURI(),
                Instant.now(),
                traceId,
                List.of()
        );

        // 把 traceId 也放進 response header (跟 GlobalExceptionHandler 一致)
        if (traceId != null) {
            response.setHeader(TraceIdFilter.TRACE_ID_HEADER, traceId);
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
