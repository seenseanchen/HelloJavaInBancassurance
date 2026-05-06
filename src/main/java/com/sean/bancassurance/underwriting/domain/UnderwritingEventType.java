package com.sean.bancassurance.underwriting.domain;

/**
 * 核保事件型別。
 *
 * 為什麼跟 UnderwritingStatus 分開？
 *  - status 是「現在是什麼狀態」，是「名詞」。
 *  - eventType 是「發生了什麼動作」，是「動詞」。
 *  - 兩者不是 1:1：例如 WITHDRAWN 狀態可由「業務員撤件」或「客戶撤件」造成，
 *    動作不同但 to_status 相同。未來要區分時，event 表才有彈性。
 *
 * 命名慣例：CASE_<過去式動詞>，凸顯「事件已發生」(event sourcing 圈習慣)。
 */
public enum UnderwritingEventType {
    /** 案件被建立送件 */
    CASE_SUBMITTED,
    /** 核保員領件 */
    CASE_CLAIMED,
    /** 核保員要求補件 */
    INFO_REQUESTED,
    /** 業務員回覆補件，重新送審 */
    CASE_RESUBMITTED,
    /** 核准 */
    CASE_APPROVED,
    /** 退件 */
    CASE_REJECTED,
    /** 撤件 */
    CASE_WITHDRAWN
}
