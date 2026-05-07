package com.sean.bancassurance.common.web;

import com.sean.bancassurance.common.exception.ApiError;
import com.sean.bancassurance.common.response.ApiResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 統一回應包裝 — ResponseBodyAdvice。
 *
 * ── 運作原理 ─────────────────────────────────────────────────────────
 *   Spring MVC 處理完 Controller method，準備把回傳值序列化為 JSON 之前，
 *   會呼叫 ResponseBodyAdvice.beforeBodyWrite()。
 *   此處就是插入「包裝邏輯」的最佳時機。
 *
 *   Flow：
 *     Controller 回傳 PolicyResponse
 *          │
 *          ▼
 *     ApiResponseWrapper.beforeBodyWrite()
 *          │  (wrap)
 *          ▼
 *     ApiResponse<PolicyResponse> { code:"SUCCESS", data: {...}, traceId:"..." }
 *          │
 *          ▼
 *     Jackson 序列化 → HTTP response body
 *
 * ── 與 @ControllerAdvice 的關係 ────────────────────────────────────
 *   @RestControllerAdvice = @ControllerAdvice + @ResponseBody；
 *   ResponseBodyAdvice 使用 @ControllerAdvice 就足夠（它本身回傳值已在 beforeBodyWrite 裡處理）。
 *   實務上兩個都可以，但 @ControllerAdvice 語意更精確 (這裡只做 advice，不做 @ResponseBody)。
 *
 * ── 跳過的情況 ────────────────────────────────────────────────────────
 *   (1) body 本身就是 ApiResponse → 已包過，不要雙重包。
 *   (2) body 是 ApiError          → 錯誤回應有自己的合約，不包。
 *   (3) body 是 null              → 不包 (例如 204 No Content)。
 *   (4) body 是 String            → StringHttpMessageConverter 優先被選中，
 *                                   包完後型別不符會 ClassCastException，跳過。
 *                                   (本專案 Controller 都回 DTO，不會觸發，但防禦性加)
 *
 * ── 為什麼不用 @AfterReturning AOP？──────────────────────────────────
 *   AOP Around 在 proxy 外層，拿到的回傳值是「呼叫 controller 前後」，
 *   不是「寫入 HTTP body 前」；如果 Controller 回 ResponseEntity，
 *   你改的是 entity 本身，header (如 ETag) 不受影響。
 *   ResponseBodyAdvice 在 HTTP message conversion 前介入，
 *   只改 body，不動 header，是更精準的切入點。
 *
 * (面試題 / 資深)：「Controller 裡有一個 endpoint 回傳 ResponseEntity，
 *                   ResponseBodyAdvice 拿到的 body 是 ResponseEntity 還是裡面的 DTO？」
 *   答：是裡面的 DTO。Spring 在呼叫 beforeBodyWrite 前已經把 ResponseEntity 拆開，
 *      body 就是 DTO，response status / header 已經設好了。
 *      因此 ETag header (由 ResponseEntity.eTag() 設的) 完全不受影響。
 *
 * (面試題 / 中級)：「如果我有 10 個 @ControllerAdvice，Spring 怎麼決定順序？」
 *   答：用 @Order 或 Ordered 介面控制，數字小的優先。
 *       沒設定就依 bean 定義順序，不可預期，多模組專案一定要設。
 */
@RestControllerAdvice
public class ApiResponseWrapper implements ResponseBodyAdvice<Object> {

    /**
     * 決定這個 advice 是否要介入。
     *
     * 這裡回 true 代表「全部都可能介入」，實際是否包裝在 beforeBodyWrite 裡判斷。
     * 也可以在這裡用 converterType 過濾只支援 MappingJackson2HttpMessageConverter，
     * 但為了簡潔，把判斷集中在 beforeBodyWrite。
     */
    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    /**
     * 真正的包裝邏輯。
     * Spring 把 body 序列化之前來這裡「改造」它。
     */
    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {

        // 已包過 → 直接放行
        if (body instanceof ApiResponse<?>) {
            return body;
        }

        // 錯誤回應有自己的格式 → 直接放行
        if (body instanceof ApiError) {
            return body;
        }

        // null body (204 No Content 等) → 不包，直接放行
        if (body == null) {
            return null;
        }

        // String 由 StringHttpMessageConverter 處理，型別不相容，不包
        // (本專案 controller 都回 DTO，不會觸發，純防禦)
        if (body instanceof String) {
            return body;
        }

        // ✓ 正常情況：包成 ApiResponse，traceId 自動從 MDC 取
        return ApiResponse.ok(body);
    }
}
