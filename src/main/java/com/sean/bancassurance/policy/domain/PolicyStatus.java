package com.sean.bancassurance.policy.domain;

/**
 * 保單狀態。
 *
 * 跟 UnderwritingStatus 不同，保單狀態主要是「生命週期」(lifecycle) 而非「審查流程」。
 * 變更頻率低 — 多數保單從 IN_FORCE 直到 MATURED；少部分提前 SURRENDERED。
 *
 * (面試題 / 中級)：M5 我們會做變更受益人 / 地址。為什麼變更受益人時保單狀態不變？
 *  答：受益人異動是「保單內容 (endorsement)」，不影響保單生命週期；
 *      只有「失效」、「滿期」這類事件才換 status。
 *
 * 為什麼這個 enum 暫時不需要狀態機 (canTransitionTo)？
 *   M4 上半我們只做查詢；保單狀態的合法跳轉 (例如 IN_FORCE → LAPSED 由排程觸發)
 *   不在這個階段要做的範圍。M5 變更受益人也不會觸動 status，所以暫不引入狀態機。
 */
public enum PolicyStatus {
    /** 生效中 — 已繳首期保費、生效日已到 */
    IN_FORCE,
    /** 停效 — 寬限期過了還沒繳保費 */
    LAPSED,
    /** 滿期 — 到達 expiry_date */
    MATURED,
    /** 解約 — 客戶主動解約 */
    SURRENDERED,
    /** 終止 — 其他原因 (理賠完畢、被保險人死亡保額已給付) */
    TERMINATED
}
