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
 *   (1) body 本身就是 ApiResponse  → 已包過，不要雙重包。
 *   (2) body 是 ApiError           → 錯誤回應有自己的合約，不包。
 *   (3) body 是 null               → 不包 (例如 204 No Content)。
 *   (4) selectedContentType 不是 application/json
 *       → byte[] (springdoc /api-docs)、text/html (Swagger UI)、
 *         application/octet-stream 等非 JSON 回應一律放行。
 *       ★ 這是最根本的防護：ByteArrayHttpMessageConverter 接手 byte[]，
 *         若我們把 byte[] 包進 ApiResponse<byte[]> 再讓它去 cast 就會 ClassCastException。
 *         只攔 JSON，其他通通不動。
 *   (5) body 是 String             → StringHttpMessageConverter 優先，型別不符，跳過。
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

        // ★ 框架內部端點（springdoc / actuator 等）→ 一律放行
        //
        // 問題根源：ResponseBodyAdvice 攔截「所有」@RestController 回應，
        // 包含 springdoc 自己的內部端點：
        //
        //   /api-docs               → byte[]（OpenAPI spec 預先序列化，跳過 Jackson）
        //   /api-docs/swagger-config→ SwaggerUiConfigParameters 物件（Swagger UI 初始化用）
        //
        // 如果把 swagger-config 包成 ApiResponse<SwaggerUiConfigParameters>，
        // Swagger UI 收到 { "code":"SUCCESS", "data": { "configUrl":... } }，
        // 解析不到 top-level 的 configUrl，就顯示 "No API definition provided."。
        //
        // 最乾淨的解法：用請求路徑過濾，把 /api-docs/** 整個排除。
        // 同理排除 /swagger-ui/**（雖然那些通常是 HTML/JS，但防禦性加上）。
        //
        // (面試題 / 資深)：「ResponseBodyAdvice 怎麼避免影響框架內部 endpoint？」
        //   答：在 beforeBodyWrite() 用 request.getURI().getPath() 過濾路徑，
        //       或在 supports() 只對自己的 Controller package 生效（用 returnType.getDeclaringClass() 判斷）。
        String path = request.getURI().getPath();
        if (path.startsWith("/api-docs") || path.startsWith("/swagger-ui")) {
            return body;
        }

        // ★ byte[] → 放行（springdoc spec、actuator binary 等）
        // 即使路徑過濾已擋掉 springdoc，保留這層防護以應對其他 byte[] 回應
        if (body instanceof byte[]) {
            return body;
        }

        // 非 JSON content-type → 放行（HTML redirect、text/plain、octet-stream 等）
        if (!MediaType.APPLICATION_JSON.isCompatibleWith(selectedContentType)) {
            return body;
        }

        // String 由 StringHttpMessageConverter 處理，型別不相容，不包（防禦性保留）
        if (body instanceof String) {
            return body;
        }

        // ✓ application/json + Java 物件：包成 ApiResponse，traceId 自動從 MDC 取
        return ApiResponse.ok(body);
    }
}
