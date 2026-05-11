package com.sean.bancassurance.common.exception;

import com.sean.bancassurance.auth.service.JwtService;
import com.sean.bancassurance.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
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
 * M6 新增：
 *  - 每個 ApiError 都帶 traceId (從 MDC 取)
 *  - response header 加 X-Trace-Id，讓 client 可以回報問題時帶上
 *
 * 注意：ApiError 不會被 ApiResponseWrapper 再包一層，因為
 *   ApiResponseWrapper.beforeBodyWrite() 遇到 ApiError 直接放行。
 *
 * (面試題 / 資深)：如果有多個 ControllerAdvice，誰先被套用？
 *  - 用 @Order 控制；數字小的優先。沒設定就無序。
 *  - 多模組專案常見：core 模組一個 advice 處理通用例外，業務模組可覆寫特定例外。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ────────────────────────────────────────────────────────────────────
    // 內部輔助方法：建立帶 traceId 的錯誤 body + 帶 X-Trace-Id header
    // ────────────────────────────────────────────────────────────────────

    private ApiError buildError(HttpStatus status, String code,
                                String message, String path,
                                List<ApiError.FieldError> details) {
        return new ApiError(
                status.value(),
                status.getReasonPhrase(),
                code,
                message,
                path,
                Instant.now(),
                MDC.get(TraceIdFilter.TRACE_ID_KEY),  // 從 MDC 取 traceId
                details
        );
    }

    /** 把 traceId 寫進 response header，方便 client 回報問題。 */
    private HttpHeaders traceHeader() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_KEY);
        HttpHeaders headers = new HttpHeaders();
        if (traceId != null) {
            headers.set(TraceIdFilter.TRACE_ID_HEADER, traceId);
        }
        return headers;
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, ApiError body) {
        return ResponseEntity.status(status).headers(traceHeader()).body(body);
    }

    // ────────────────────────────────────────────────────────────────────
    // 業務例外
    // ────────────────────────────────────────────────────────────────────

    /**
     * 找不到資源 → 404
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            ResourceNotFoundException ex, HttpServletRequest req) {
        return respond(HttpStatus.NOT_FOUND,
                buildError(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                        ex.getMessage(), req.getRequestURI(), List.of()));
    }

    /**
     * 業務狀態機：非法狀態跳轉 → 409 Conflict
     *
     * 為什麼回 409 而不是 400？(面試常問)
     *  - 400 Bad Request = client 送錯格式
     *  - 409 Conflict     = 請求格式對，但跟伺服器當前狀態衝突
     */
    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<ApiError> handleIllegalTransition(
            IllegalStateTransitionException ex, HttpServletRequest req) {
        return respond(HttpStatus.CONFLICT,
                buildError(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                        ex.getMessage(), req.getRequestURI(), List.of()));
    }

    /**
     * 保單狀態不允許該操作 → 409 Conflict (M5)
     *
     * INVALID_POLICY_STATE = 保單目前狀態不允許該操作 (e.g. LAPSED 想改地址)
     */
    @ExceptionHandler(IllegalPolicyStateException.class)
    public ResponseEntity<ApiError> handleIllegalPolicyState(
            IllegalPolicyStateException ex, HttpServletRequest req) {
        return respond(HttpStatus.CONFLICT,
                buildError(HttpStatus.CONFLICT, "INVALID_POLICY_STATE",
                        ex.getMessage(), req.getRequestURI(), List.of()));
    }

    /**
     * 前置條件失敗 (If-Match / expectedVersion 比對失敗) → 412 (M5)
     *
     * RFC 7232：If-Match header 比對失敗應回 412
     */
    @ExceptionHandler(PreconditionFailedException.class)
    public ResponseEntity<ApiError> handlePreconditionFailed(
            PreconditionFailedException ex, HttpServletRequest req) {
        log.warn("[traceId={}] Precondition failed at [{}]: {}",
                MDC.get(TraceIdFilter.TRACE_ID_KEY), req.getRequestURI(), ex.getMessage());
        return respond(HttpStatus.PRECONDITION_FAILED,
                buildError(HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED",
                        ex.getMessage(), req.getRequestURI(), List.of()));
    }

    /**
     * 業務規則違反 → 422 Unprocessable Entity (M5)
     *
     * 422 「請求語法 OK，但語意不合業務」。
     * 常見：受益人加總 ≠ 100、Idempotency-Key 重用。
     */
    @ExceptionHandler(BusinessRuleViolationException.class)
    public ResponseEntity<ApiError> handleBusinessRule(
            BusinessRuleViolationException ex, HttpServletRequest req) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY,
                buildError(HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(),
                        ex.getMessage(), req.getRequestURI(), List.of()));
    }

    /**
     * 樂觀鎖衝突 → 409 Conflict
     *
     * 兩個交易同時改同一張資源，後送出的 UPDATE WHERE version=N 找不到列，
     * Hibernate 拋 OptimisticLockingFailureException。
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(
            OptimisticLockingFailureException ex, HttpServletRequest req) {
        log.warn("[traceId={}] Optimistic lock conflict at [{}]",
                MDC.get(TraceIdFilter.TRACE_ID_KEY), req.getRequestURI());
        return respond(HttpStatus.CONFLICT,
                buildError(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_CONFLICT",
                        "The resource was modified by another transaction. Please reload and retry.",
                        req.getRequestURI(), List.of()));
    }

    // ────────────────────────────────────────────────────────────────────
    // 認證 / 授權例外 (M9)
    // ────────────────────────────────────────────────────────────────────

    /**
     * 認證失敗 → 401 Unauthorized
     *
     *  觸發時機：
     *    - AuthController 找不到 user / 密碼錯 / 帳號 disabled (統一翻成 BadCredentialsException)
     *
     *  401 vs 403 (面試★必考)：
     *    401 Unauthorized = 「我不知道你是誰」(未提供有效身份)
     *    403 Forbidden    = 「我知道你是誰，但你不能做這件事」(身份合法但權限不足)
     *
     *    歷史包袱：HTTP 規範裡 401 的英文翻譯有點誤導 — 應該叫 "Unauthenticated"。
     *    很多人混用，但實作上一定要分清楚，自家 ELK 報表才看得出「攻擊者試密碼」
     *    跟「正常 user 想越權」這兩種情境。
     *
     *  訊息：對外永遠回 "Bad credentials"，不洩漏細節 (見 AuthController 註解)。
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(
            BadCredentialsException ex, HttpServletRequest req) {
        return respond(HttpStatus.UNAUTHORIZED,
                buildError(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS",
                        "Bad credentials", req.getRequestURI(), List.of()));
    }

    /**
     * Token 驗證失敗 → 401 Unauthorized (M9.4 之後 JwtAuthenticationFilter 才會丟)
     *
     *  觸發時機：
     *    - Token 簽名對不上 (被竄改 / 用錯 secret 簽)
     *    - Token 過期 (exp claim 已過)
     *    - Token issuer 不對 (拿其他系統的 token 來打)
     *    - Token 格式壞掉
     *
     *  M9.3 階段 AuthController 不會丟這個 — 但先把 handler 寫好，M9.4 接 filter
     *  時就直接生效。
     */
    @ExceptionHandler(JwtService.InvalidJwtException.class)
    public ResponseEntity<ApiError> handleInvalidJwt(
            JwtService.InvalidJwtException ex, HttpServletRequest req) {
        return respond(HttpStatus.UNAUTHORIZED,
                buildError(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
                        "Invalid or expired authentication token",
                        req.getRequestURI(), List.of()));
    }

    /**
     * 授權失敗 (角色 / 權限不足) → 403 Forbidden
     *
     *  觸發時機 (M9.5 之後)：
     *    - csr01 想打 POST /cases/{id}/approve (該 endpoint 標 @PreAuthorize hasRole(UNDERWRITER))
     *    - SecurityFilterChain 的路徑授權失敗
     *
     *  Spring Security 6+ 把 @PreAuthorize 失敗丟成 AuthorizationDeniedException
     *  (extends AccessDeniedException)，所以 catch 父類 AccessDeniedException 一網打盡。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest req) {
        log.info("[traceId={}] Access denied at [{}]: {}",
                MDC.get(TraceIdFilter.TRACE_ID_KEY), req.getRequestURI(), ex.getMessage());
        return respond(HttpStatus.FORBIDDEN,
                buildError(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                        "You do not have permission to perform this action",
                        req.getRequestURI(), List.of()));
    }

    // ────────────────────────────────────────────────────────────────────
    // 框架 / 輸入驗證例外
    // ────────────────────────────────────────────────────────────────────

    /**
     * Bean Validation (@NotBlank, @DecimalMin 等) 失敗 → 400
     *
     * MethodArgumentNotValidException 含 BindingResult，
     * 可細列「哪個欄位、為什麼失敗」。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest req) {

        List<ApiError.FieldError> details = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::toFieldError)
                .toList();

        return respond(HttpStatus.BAD_REQUEST,
                buildError(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                        "Request validation failed", req.getRequestURI(), details));
    }

    /**
     * Query string / @PathVariable 型別轉失敗 → 400
     *
     * 觸發情境：
     *   - ?status=NOT_A_STATUS         (enum 找不到對應 constant)
     *   - ?effectiveDateFrom=2026/01/01 (LocalDate 解析失敗，要 ISO YYYY-MM-DD)
     *   - /policies/not-a-uuid          (UUID 解析失敗)
     *
     * ★ 為什麼不被 MethodArgumentNotValidException 接走？
     *   MethodArgumentNotValidException：Bean Validation 規則失敗（值已反序列化）
     *   MethodArgumentTypeMismatchException：型別轉換就失敗（連 validator 都沒機會跑）
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

        List<ApiError.FieldError> details = List.of(
                new ApiError.FieldError(paramName,
                        "expected " + requiredTypeName + ", got '" + badValue + "'"));

        return respond(HttpStatus.BAD_REQUEST,
                buildError(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                        message, req.getRequestURI(), details));
    }

    /**
     * Request body 無法讀取 / 反序列化失敗 → 400
     *
     * 觸發情境：
     *   - POST 沒帶 Content-Type: application/json
     *   - JSON body 語法壞掉 (少括號、多逗號)
     *   - JSON body 有不認識的 enum 值
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest req) {
        return respond(HttpStatus.BAD_REQUEST,
                buildError(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                        "Malformed request body or unrecognized value",
                        req.getRequestURI(), List.of()));
    }

    /**
     * 必填的 @RequestParam 沒帶 → 400
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest req) {
        return respond(HttpStatus.BAD_REQUEST,
                buildError(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                        "Required parameter '" + ex.getParameterName() + "' is missing",
                        req.getRequestURI(),
                        List.of(new ApiError.FieldError(ex.getParameterName(), "is required"))));
    }

    /**
     * 其他未預期例外 → 500
     *
     * 注意：500 才適合 log.error 把 stack trace 印出來；
     *       4xx 多半是 client 自己錯，log 太多反而吵。
     *       traceId 寫進 log 讓維運人員可以定位：
     *       "請帶 traceId XXX 找 backend 工程師"
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(
            Exception ex, HttpServletRequest req) {
        log.error("[traceId={}] Unhandled exception at [{}]",
                MDC.get(TraceIdFilter.TRACE_ID_KEY), req.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR,
                buildError(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                        // 不洩漏內部訊息給 client (避免資訊洩漏漏洞)
                        "An unexpected error occurred",
                        req.getRequestURI(), List.of()));
    }

    // ────────────────────────────────────────────────────────────────────
    // 私有輔助方法
    // ────────────────────────────────────────────────────────────────────

    private ApiError.FieldError toFieldError(FieldError fe) {
        return new ApiError.FieldError(fe.getField(), fe.getDefaultMessage());
    }
}
