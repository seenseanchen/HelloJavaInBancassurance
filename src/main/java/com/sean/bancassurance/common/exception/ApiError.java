package com.sean.bancassurance.common.exception;

import java.time.Instant;
import java.util.List;

/**
 * 統一錯誤回應格式 (Error Response Contract)。
 *
 * 業界常見作法：
 *  - status   : HTTP 狀態碼
 *  - error    : HTTP reason phrase (例如 "Not Found")
 *  - code     : 業務錯誤碼，供前端寫對照表 (例如 "RESOURCE_NOT_FOUND")
 *  - message  : 給工程師看的訊息
 *  - path     : 請求路徑，方便排查
 *  - timestamp: 發生時間 (UTC Instant)
 *  - traceId  : 本次請求追蹤 ID，由 TraceIdFilter 寫入 MDC 並由 GlobalExceptionHandler 填入
 *               (M6 新增) 方便用同一個 id 搜尋 log 找到完整錯誤堆疊
 *  - details  : 詳細欄位錯誤 (Bean Validation 失敗時用)
 *
 * 跟 ApiResponse 的差別：
 *   ApiResponse<T> → 成功回應的 envelope
 *   ApiError       → 失敗回應，不使用 envelope 包裝 (GlobalExceptionHandler 直接回 ApiError)
 *
 * (面試題 / 中級)：你怎麼設計 API 的錯誤格式？
 *  答：參考 RFC 7807 (Problem Details for HTTP APIs)，但金融業多半自訂 schema 以便對接 SIEM。
 *      核心是「業務錯誤碼 (code) 跟 HTTP status 分開」，
 *      因為同一個 HTTP 422 可能是「受益人加總不等於 100」或「保單號格式錯誤」，
 *      code 讓前端知道是哪種，不用解析 message 字串。
 */
public record ApiError(
        int status,
        String error,
        String code,
        String message,
        String path,
        Instant timestamp,
        String traceId,       // M6 新增：從 MDC 取，方便跨服務追蹤
        List<FieldError> details
) {
    public record FieldError(String field, String message) {}
}
