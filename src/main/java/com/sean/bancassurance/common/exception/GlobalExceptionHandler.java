package com.sean.bancassurance.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;

/**
 * 全域例外處理器。
 *
 *  @RestControllerAdvice
 *      = @ControllerAdvice + @ResponseBody
 *      → 攔截所有 @RestController 拋出的例外，集中處理。
 *      → 回傳值會被序列化為 JSON。
 *
 *  @ExceptionHandler(...)
 *      指定某個方法處理特定例外型別 (含子類)。
 *      Spring 會由「最具體型別」開始匹配。
 *
 * 設計重點：
 *  - 業務例外 (ResourceNotFoundException) → 4xx
 *  - 框架例外 (Validation, HttpMessageNotReadable) → 400
 *  - 未預期例外 → 500，且不要把 stack trace 直接給 client
 *
 * (面試題 / 資深)：如果有多個 ControllerAdvice，誰先被套用？
 *  - 用 @Order 控制；數字小的優先。沒設定就無序。
 *  - 多模組專案常見：core 模組一個 advice 處理通用例外，業務模組可覆寫特定例外。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 找不到資源 → 404
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            ResourceNotFoundException ex, HttpServletRequest req) {

        ApiError body = new ApiError(
                HttpStatus.NOT_FOUND.value(),
                HttpStatus.NOT_FOUND.getReasonPhrase(),
                "RESOURCE_NOT_FOUND",
                ex.getMessage(),
                req.getRequestURI(),
                Instant.now(),
                List.of()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * 業務狀態機：非法狀態跳轉 → 409 Conflict
     *
     * 為什麼回 409 而不是 400？(同一條面試題會被問兩次)
     *  - 400 Bad Request = client 送錯格式
     *  - 409 Conflict     = 請求格式對，但跟伺服器當前狀態衝突
     */
    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<ApiError> handleIllegalTransition(
            IllegalStateTransitionException ex, HttpServletRequest req) {

        ApiError body = new ApiError(
                HttpStatus.CONFLICT.value(),
                HttpStatus.CONFLICT.getReasonPhrase(),
                "INVALID_STATE_TRANSITION",
                ex.getMessage(),
                req.getRequestURI(),
                Instant.now(),
                List.of()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * 樂觀鎖衝突 → 409 Conflict
     *
     * 兩個交易同時改同一張案件，後送出的會在 UPDATE 時 WHERE version=N 找不到列，
     * Hibernate 拋 OptimisticLockingFailureException (Spring 翻譯後的型別)。
     * 跟 IllegalStateTransition 共用 409 status，但 code 不同，client 可分開處理。
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(
            OptimisticLockingFailureException ex, HttpServletRequest req) {

        log.warn("Optimistic lock conflict at [{}]: {}", req.getRequestURI(), ex.getMessage());

        ApiError body = new ApiError(
                HttpStatus.CONFLICT.value(),
                HttpStatus.CONFLICT.getReasonPhrase(),
                "OPTIMISTIC_LOCK_CONFLICT",
                "The resource was modified by another transaction. Please reload and retry.",
                req.getRequestURI(),
                Instant.now(),
                List.of()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Bean Validation (例如 @NotBlank, @DecimalMin) 失敗 → 400
     *
     * MethodArgumentNotValidException 是 Spring 包裝過的 BindingResult，
     * 用它的 getFieldErrors() 可以拿到「哪個欄位、為什麼失敗」。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest req) {

        List<ApiError.FieldError> details = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::toFieldError)
                .toList();

        ApiError body = new ApiError(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "VALIDATION_FAILED",
                "Request validation failed",
                req.getRequestURI(),
                Instant.now(),
                details
        );
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Query string / @PathVariable 型別轉失敗 → 400
     *
     * 觸發情境：
     *   - ?status=NOT_A_STATUS         (enum 找不到對應 constant)
     *   - ?effectiveDateFrom=2026/01/01 (LocalDate 解析失敗，要 ISO YYYY-MM-DD)
     *   - /policies/not-a-uuid          (UUID 解析失敗)
     *
     *  ★ 為什麼不被 MethodArgumentNotValidException 接走？
     *    - MethodArgumentNotValidException：是「Bean Validation 規則」失敗 (例如 @NotBlank、@Min)
     *      → 觸發前提：值已經成功反序列化成 Java 物件，再交給 validator 檢查
     *    - MethodArgumentTypeMismatchException：是「型別轉換」就失敗 (字串 → enum / Date / UUID)
     *      → 連 validator 都還沒輪到就炸了
     *
     *  (面試題 / 中級)：
     *    「@RequestParam Integer page=0，client 帶 page=abc 會怎樣？」
     *    答：MethodArgumentTypeMismatchException → 400。如果沒寫 handler 就掉 500。
     *
     *  Spring Boot 預設 (沒這個 handler)：DefaultErrorAttributes 會回 400，但 status code 對
     *  body 卻是 Spring 預設那包；統一回應格式時自己接更好。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest req) {

        String paramName = ex.getName();
        Object badValue = ex.getValue();
        Class<?> requiredType = ex.getRequiredType();
        String requiredTypeName = (requiredType != null) ? requiredType.getSimpleName() : "unknown";

        String message = "Parameter '%s' has invalid value '%s'; expected type %s"
                .formatted(paramName, badValue, requiredTypeName);

        ApiError body = new ApiError(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "VALIDATION_FAILED",
                message,
                req.getRequestURI(),
                Instant.now(),
                List.of(new ApiError.FieldError(paramName,
                        "expected " + requiredTypeName + ", got '" + badValue + "'"))
        );
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Request body 無法讀取 / 反序列化失敗 → 400
     *
     * 觸發情境：
     *   - POST 沒帶 Content-Type: application/json
     *   - JSON body 語法壞掉 (少一個括號、多一個逗號)
     *   - JSON body 有不認識的 enum 值，Jackson 從 InvalidFormatException 包裝上來
     *
     * 注意：Spring 6+ 把這個例外從 ServletException 改成 ErrorResponseException 體系；
     *       這裡仍然用 @ExceptionHandler 攔型別本身，行為一致。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest req) {

        // ex.getMessage() 可能很長且帶 stack trace；只取最頂層訊息或固定 placeholder
        String message = "Malformed request body or unrecognized value";

        ApiError body = new ApiError(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "MALFORMED_REQUEST",
                message,
                req.getRequestURI(),
                Instant.now(),
                List.of()
        );
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 必填的 @RequestParam 沒帶 → 400
     *
     * 觸發情境：@RequestParam(required = true) String foo，client 沒帶 ?foo=
     *   M4 上半的 PolicyController 過濾條件全部 required=false，所以不會踩到，
     *   但留著 handler 給未來的 M5 / M6 用。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest req) {

        ApiError body = new ApiError(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "VALIDATION_FAILED",
                "Required parameter '" + ex.getParameterName() + "' is missing",
                req.getRequestURI(),
                Instant.now(),
                List.of(new ApiError.FieldError(ex.getParameterName(), "is required"))
        );
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 其他未預期例外 → 500
     *
     * 注意：500 才適合 log.error 把 stack trace 印出來；
     *       4xx 多半是 client 自己錯，log 太多反而吵。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(
            Exception ex, HttpServletRequest req) {

        log.error("Unhandled exception at [{}]", req.getRequestURI(), ex);

        ApiError body = new ApiError(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "INTERNAL_ERROR",
                // 不洩漏內部訊息給 client (避免資訊洩漏漏洞)
                "An unexpected error occurred",
                req.getRequestURI(),
                Instant.now(),
                List.of()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ApiError.FieldError toFieldError(FieldError fe) {
        return new ApiError.FieldError(fe.getField(), fe.getDefaultMessage());
    }
}
