package com.sean.bancassurance.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * TraceId 注入 Filter — MDC 整合。
 *
 * ── 為什麼繼承 OncePerRequestFilter？─────────────────────────────────
 *   Servlet Filter 理論上一個請求可能被同一個 filter 呼叫多次 (例如 forward / include)。
 *   OncePerRequestFilter 用 request attribute 標記，保證每個請求只執行一次，
 *   避免 MDC 被覆寫或 traceId 不一致。
 *
 * ── TraceId 的生命週期 ───────────────────────────────────────────────
 *   Request 進來 → 產生 / 提取 traceId → 寫入 MDC
 *                              │
 *                              ▼
 *               所有 log 自動帶 [traceId=...]
 *                              │
 *                              ▼
 *               Response header X-Trace-Id: ...
 *                              │
 *                              ▼ (finally 確保一定執行)
 *               MDC 清除 (避免 thread pool 中的 thread 污染下一個 request)
 *
 * ── 為什麼要清除 MDC？────────────────────────────────────────────────
 *   Spring 使用 thread pool (Tomcat NIO thread pool) 處理請求。
 *   線程用完後歸還 pool，下一個請求可能拿到同一個 thread。
 *   如果沒清 MDC，舊 traceId 會殘留，導致 log 串到錯誤請求 → 線上排查噩夢。
 *
 * ── @Order(Ordered.HIGHEST_PRECEDENCE) 的意思 ────────────────────────
 *   Spring Security 等 filter 也在 chain 裡；讓 TraceId filter 第一個跑，
 *   Security 的 log 也能帶上 traceId。
 *
 * ── X-Trace-Id header 傳遞慣例 ───────────────────────────────────────
 *   API Gateway (e.g., Kong, AWS ALB) 通常會在 upstream 呼叫時帶 X-Trace-Id。
 *   這個 filter 優先複用外部 traceId，讓整條調用鏈 traceId 一致 (Distributed Tracing 基礎)。
 *   若沒有則自行生成。
 *
 * (面試題 / 中級)：「線上怎麼做跨服務的請求追蹤？」
 *   答：
 *     1. 每個服務讀進 X-Trace-Id (或 W3C TraceContext 的 traceparent)，寫入 MDC。
 *     2. 呼叫下游服務時帶上同一個 traceId header (用 RestTemplate interceptor 或 HttpClient filter)。
 *     3. 所有服務的 log 都有這個 id → ELK / Grafana 一筆 query 就能看完整鏈路。
 *     進階：用 Micrometer Tracing (整合 Zipkin / Tempo) 自動處理，不用手寫。
 *
 * (面試題 / 資深)：「MDC 跟 ThreadLocal 有什麼關係？」
 *   答：MDC 內部就是 ThreadLocal<Map<String, String>>。
 *       用 virtual thread (Java 21 Loom) 時要注意：每條 virtual thread 有自己的 MDC，
 *       但 carrier thread 切換時 MDC 不會自動傳遞 (Logback 4.x 已修復，需確認版本)。
 */
@Component
@Order(Integer.MIN_VALUE)   // 比 Security 更早執行 (等同 Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC key 名稱，也用於 request / response header。*/
    public static final String TRACE_ID_KEY   = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // ① 優先使用外部傳入的 traceId (e.g., API Gateway / 前置服務帶入)
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        try {
            // ② 寫入 MDC — 後續所有 log 都會帶上這個值
            MDC.put(TRACE_ID_KEY, traceId);

            // ③ 放進 response header — client 可以用這個值跟客服回報問題
            response.setHeader(TRACE_ID_HEADER, traceId);

            // ④ 繼續執行 filter chain (實際業務邏輯在這裡)
            filterChain.doFilter(request, response);

        } finally {
            // ⑤ 一定要清除 MDC，防止 thread pool 中線程污染下一個請求
            MDC.remove(TRACE_ID_KEY);
        }
    }
}
