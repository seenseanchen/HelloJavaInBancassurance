package com.sean.bancassurance.common.exception;

import java.time.Instant;
import java.util.List;

/**
 * 統一錯誤回應格式 (Error Response Contract)。
 *
 * 業界常見作法：
 *  - status: HTTP 狀態碼
 *  - error : HTTP reason phrase
 *  - code  : 業務錯誤碼 (供前端寫對照表，例如 "RESOURCE_NOT_FOUND")
 *  - message: 給工程師看的訊息
 *  - path  : 請求路徑，方便排查
 *  - timestamp: 發生時間 (UTC Instant)
 *  - details: 詳細欄位錯誤 (Bean Validation 失敗時用)
 *
 * (面試題 / 中級)：你怎麼設計 API 的錯誤格式？
 *  答：參考 RFC 7807 (Problem Details for HTTP APIs)，但金融業多半自訂 schema 以便對接 SIEM。
 */
public record ApiError(
        int status,
        String error,
        String code,
        String message,
        String path,
        Instant timestamp,
        List<FieldError> details
) {
    public record FieldError(String field, String message) {}
}
