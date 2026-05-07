package com.sean.bancassurance.common.response;

import org.slf4j.MDC;

/**
 * 統一成功回應包裝 (Success Response Contract)。
 *
 * 業界標準做法：所有 API 成功回應用同一個 envelope，
 * 讓前端 / 行動裝置 / 對接系統只需要寫一個通用的 response handler。
 *
 *  欄位說明：
 *   code     — 業務結果碼，"SUCCESS" 代表操作成功。未來可擴充 "PARTIAL_SUCCESS" 等。
 *   message  — 給工程師看的簡短說明。
 *   data     — 實際 payload，型別由泛型 T 決定。
 *   traceId  — 本次請求的追蹤 ID，由 TraceIdFilter 生成並寫入 MDC。
 *              發現問題時，只需用 traceId grep log 就能撈出本次請求完整 log。
 *              (面試常問)：「線上 API 出錯怎麼定位？」→ 靠 traceId 搜 log。
 *
 * 為什麼用 record 而不是 class？
 *   record 是 Java 16 正式版、Java 21 常用的「不可變資料載體」語法糖。
 *   等同於一個所有欄位都是 final 且有 canonical constructor 的 class。
 *   很適合 DTO：不可變、equals/hashCode/toString 自動實作。
 *
 * 為什麼 traceId 從 MDC 取而不是當參數傳？
 *   - MDC 是 thread-local，同一條 request thread 全程可見，
 *     不需要把 traceId 一層一層傳進每個 method。
 *   - 工廠方法呼叫時自動讀 MDC，讓呼叫方不用感知 traceId 的存在。
 *
 * (面試題 / 中級)：
 *   「`ApiResponse<Page<PolicySummaryResponse>>` 序列化後長什麼樣？」
 *    答：data 欄位裡面是 Spring 的 Page 物件，含 content / pageable / totalElements 等。
 *       對外可能太囉嗦；進階做法是自訂 PageResponse DTO 只保留 content / page / size / total。
 */
public record ApiResponse<T>(
        String code,
        String message,
        T data,
        String traceId
) {

    /**
     * 最常用：200 OK 操作成功。
     *
     * 使用範例 (在 ResponseBodyAdvice 或 Controller 裡)：
     *   return ApiResponse.ok(policyResponse);
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("SUCCESS", "OK", data, MDC.get("traceId"));
    }

    /**
     * 201 Created：建立資源成功。
     *
     * HTTP 201 代表「請求已完成且建立了新資源」。
     * 注意：HTTP status code 本身由 Controller 的 @ResponseStatus / ResponseEntity 決定，
     *       ApiResponse 的 message 欄位只是語意補充，不是用來傳遞 HTTP 狀態的。
     */
    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>("SUCCESS", "Created", data, MDC.get("traceId"));
    }
}
