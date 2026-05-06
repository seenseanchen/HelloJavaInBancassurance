package com.sean.bancassurance.underwriting.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 把所有 transition 動作的 Request DTO 集中在一個檔案。
 *
 * 為什麼集中？
 *  - 都是 transition 系列、欄位都簡單 (1~3 個欄位)
 *  - 散到 6 個檔案會讓 dto/ 資料夾爆炸
 *  - 用「巢狀 record」(nested record) 清楚標示這些 DTO 屬於同一個族
 *
 * Java 21 record 特性回顧：
 *  - 自動 immutable
 *  - 自動 equals/hashCode/toString
 *  - 可以加 compact constructor 做驗證 (我們改用 Bean Validation 處理)
 */
public final class TransitionRequests {

    private TransitionRequests() {
        // utility holder, 禁止實體化
    }

    /** 領件 (SUBMITTED → UNDER_REVIEW)。actor 是核保員 login id。 */
    public record ClaimRequest(
            @NotBlank @Size(max = 64) String actor
    ) {}

    /** 要求補件 (UNDER_REVIEW → PENDING_INFO)。comment 必填，必須說清楚要補什麼。 */
    public record RequestInfoRequest(
            @NotBlank @Size(max = 64) String actor,
            @NotBlank @Size(max = 2000) String comment
    ) {}

    /** 補件後重送 (PENDING_INFO → UNDER_REVIEW)。actor 通常是業務員。 */
    public record ResubmitRequest(
            @NotBlank @Size(max = 64) String actor,
            @Size(max = 2000) String comment   // 可選：附上補件清單
    ) {}

    /** 核准 (UNDER_REVIEW → APPROVED)。 */
    public record ApproveRequest(
            @NotBlank @Size(max = 64) String actor,
            @Size(max = 2000) String comment   // 可選：核准備註
    ) {}

    /** 退件 (UNDER_REVIEW → REJECTED)。退件理由必填，金融業稽核會看。 */
    public record RejectRequest(
            @NotBlank @Size(max = 64) String actor,
            @NotBlank @Size(max = 2000) String comment
    ) {}

    /** 撤件。reason 必填。 */
    public record WithdrawRequest(
            @NotBlank @Size(max = 64) String actor,
            @NotBlank @Size(max = 2000) String comment
    ) {}
}
