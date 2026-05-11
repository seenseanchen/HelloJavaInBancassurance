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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * 「登入了但角色不夠」時的 403 回應。
 *
 *  跟 RestAuthenticationEntryPoint 是兩條互補路徑：
 *    AuthenticationEntryPoint  →  401 (尚未認證 — 沒登入或 token 無效)
 *    AccessDeniedHandler       →  403 (已認證但無權限 — 角色不夠)
 *
 *  ── 為什麼 AccessDeniedHandler 要自訂？─────────────────────────────────
 *
 *  Spring Security 預設遇到 AccessDeniedException：
 *    - SecurityFilterChain 內部 (路徑授權失敗) → 預設 AccessDeniedHandlerImpl 寫 403 HTML 頁
 *    - @PreAuthorize 失敗 (M9.5) → AuthorizationManagerBeforeMethodInterceptor 拋
 *      AuthorizationDeniedException，這個會被 GlobalExceptionHandler 接到 (因為發生在 method 層)
 *
 *  注意這兩條路：
 *    路徑授權 (filter 層) → 我們的 RestAccessDeniedHandler 接
 *    @PreAuthorize (method 層) → GlobalExceptionHandler.handleAccessDenied 接
 *  兩個 handler 內容一模一樣 (都產 ApiError 403)，只是「在哪一層攔截」不同。
 *
 *  能讓兩條都統一的做法：兩個 handler 都用 ObjectMapper 寫一致的 JSON。
 *
 *  (面試題 / 資深)：「filter chain 的 AccessDenied 跟 @PreAuthorize 的 AccessDenied 哪裡不同？」
 *    - filter chain：路徑層級規則 (.requestMatchers().hasRole(...))
 *    - @PreAuthorize：method 層級規則，可寫 SpEL 看 method 參數
 *      e.g. @PreAuthorize("authentication.principal.userId == #policy.holderId")
 *    - filter 失敗用 AccessDeniedHandler；method 失敗則拋例外給 ExceptionResolver
 *      (對 REST 來說會走 @RestControllerAdvice)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        String traceId = MDC.get(TraceIdFilter.TRACE_ID_KEY);

        // 403 在 audit 監控很重要：「合法 user 嘗試越權」可能是 client bug，也可能是內賊
        log.info("Access denied at uri={} (traceId={}): {}",
                request.getRequestURI(), traceId, accessDeniedException.getMessage());

        ApiError body = new ApiError(
                HttpStatus.FORBIDDEN.value(),
                HttpStatus.FORBIDDEN.getReasonPhrase(),
                "ACCESS_DENIED",
                "You do not have permission to perform this action",
                request.getRequestURI(),
                Instant.now(),
                traceId,
                List.of()
        );

        if (traceId != null) {
            response.setHeader(TraceIdFilter.TRACE_ID_HEADER, traceId);
        }

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
