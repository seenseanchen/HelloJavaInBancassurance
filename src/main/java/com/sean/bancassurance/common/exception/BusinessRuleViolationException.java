package com.sean.bancassurance.common.exception;

/**
 * 業務規則違反 → HTTP 422 (Unprocessable Entity)。
 *
 * 跟 400 / 409 的差別：
 *   400：JSON 語法壞、缺必填欄位 — request 連解析都過不了
 *   409：跟 server 當前狀態衝突 (狀態機 / 樂觀鎖)
 *   422：request 語法 OK、欄位都有，但「組合起來不符合業務規則」
 *
 * 典型案例：
 *   - 受益人比例加總 ≠ 100
 *   - 沒有任何第一順位受益人
 *   - 變更後缺少受益人 (M5 至少要保留 1 位)
 *   - 同 Idempotency-Key 但 body hash 不同 (key reuse 攻擊)
 *
 * (面試題 / 中級)：「Bean Validation 跟業務規則檢查放哪？」
 *   答：
 *    - Bean Validation (@NotBlank、@Min、@Pattern) 放 DTO — 框架自動跑、回 400
 *    - 業務規則 (受益人加總 = 100) 放 service — 需要看資料才能驗，回 422
 *    - 不要把業務規則塞進 ConstraintValidator (除非很簡單) — 會把 service 邏輯
 *      拖進 validation framework，難測也難重用
 */
public class BusinessRuleViolationException extends RuntimeException {

    private final String code;

    public BusinessRuleViolationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
