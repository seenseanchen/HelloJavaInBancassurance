package com.sean.bancassurance.underwriting.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 把所有 transition 動作的 Request DTO 集中在一個檔案。
 *
 *  ── M9.5 變更 (breaking) ────────────────────────────────────────────────
 *
 *  原本每個 record 都有 `String actor` 欄位 (從 client 帶上來)。
 *  M9.5 接 Spring Security 後，actor 從 SecurityContext 取，不再由 client 自報。
 *
 *  原因：client 自報 actor 是「能信嗎？」的問題 — 攻擊者可以填別人 ID 操作別人帳戶。
 *  接 JWT 之後 actor 直接從 token 的 sub claim 取，server 認得是誰、誰打 API。
 *
 *  (面試題 / 中級)：「為什麼不能讓 client 在 body 帶 user_id？」
 *    答：信任邊界。client 提交的任何欄位都應該視為 untrusted —
 *        身份相關欄位 (actor / userId / tenantId) 一律從 server-side 認證上下文取，
 *        不從 client payload 取。這是 OWASP A01 (Broken Access Control) 的基本防護。
 *
 *  集中在一個檔案：都是 transition 系列、欄位都簡單 (現在只剩 0~1 個欄位)，
 *  散到 6 個檔案會讓 dto/ 資料夾爆炸。用「巢狀 record」清楚標示同族。
 *
 *  Java 21 record 特性：
 *   - 自動 immutable
 *   - 自動 equals/hashCode/toString
 *   - 可以加 compact constructor 做驗證 (我們改用 Bean Validation 處理)
 */
public final class TransitionRequests {

    private TransitionRequests() {
        // utility holder, 禁止實體化
    }

    /**
     * 領件 (SUBMITTED → UNDER_REVIEW)。
     * 沒有任何 body 欄位 — 純動作。controller 收 empty body 或 {} 都接受。
     */
    public record ClaimRequest() {}

    /** 要求補件 (UNDER_REVIEW → PENDING_INFO)。comment 必填，必須說清楚要補什麼。 */
    public record RequestInfoRequest(
            @NotBlank @Size(max = 2000) String comment
    ) {}

    /** 補件後重送 (PENDING_INFO → UNDER_REVIEW)。 */
    public record ResubmitRequest(
            @Size(max = 2000) String comment   // 可選：附上補件清單
    ) {}

    /** 核准 (UNDER_REVIEW → APPROVED)。 */
    public record ApproveRequest(
            @Size(max = 2000) String comment   // 可選：核准備註
    ) {}

    /** 退件 (UNDER_REVIEW → REJECTED)。退件理由必填，金融業稽核會看。 */
    public record RejectRequest(
            @NotBlank @Size(max = 2000) String comment
    ) {}

    /** 撤件。reason 必填。 */
    public record WithdrawRequest(
            @NotBlank @Size(max = 2000) String comment
    ) {}
}
