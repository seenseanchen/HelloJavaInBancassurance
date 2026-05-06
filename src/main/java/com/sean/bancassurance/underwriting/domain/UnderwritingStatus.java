package com.sean.bancassurance.underwriting.domain;

/**
 * 核保案件狀態。
 *
 * 設計重點 (面試常考)：
 *
 * 1. 為什麼用 enum 而不是 int / String 常數？
 *    - 編譯期型別檢查：傳錯狀態值，編譯就失敗。
 *    - IDE 可枚舉所有值，重構安全 (改名會連鎖更新所有使用點)。
 *    - 配合 switch 可寫窮舉 (exhaustive) 邏輯。
 *
 * 2. 為什麼 DB 端用 VARCHAR + JPA @Enumerated(EnumType.STRING)？
 *    - 絕對不要用 EnumType.ORDINAL：它把順序 (0,1,2...) 寫進 DB。
 *      日後在中間插入新狀態 (例如 SUBMITTED 與 UNDER_REVIEW 之間插一個)，
 *      整張表的舊資料全錯位。這在金融業是「災難級 bug」。
 *
 * 3. 狀態機 (State Machine) — M3 才會做，這裡先列出合法轉移：
 *
 *    SUBMITTED      ─▶ UNDER_REVIEW, WITHDRAWN
 *    UNDER_REVIEW   ─▶ APPROVED, REJECTED, PENDING_INFO
 *    PENDING_INFO   ─▶ UNDER_REVIEW, WITHDRAWN
 *    APPROVED       ─▶ (terminal)
 *    REJECTED       ─▶ (terminal)
 *    WITHDRAWN      ─▶ (terminal)
 *
 *    (面試題 / 資深)：請畫出 state diagram 並說明哪些是 terminal state、
 *                     如何防止 race condition 把已 APPROVED 的案件改回 UNDER_REVIEW？
 *                     答：在 service 層用 @Version 樂觀鎖 + 在轉移時驗證來源狀態。
 */
public enum UnderwritingStatus {

    /** 已送件，等待核保員領件 */
    SUBMITTED,

    /** 審查中 */
    UNDER_REVIEW,

    /** 補件中：核保員要求要保人補資料 */
    PENDING_INFO,

    /** 核准 (terminal) */
    APPROVED,

    /** 退件 (terminal) */
    REJECTED,

    /** 撤件：客戶或業務主動撤回 (terminal) */
    WITHDRAWN
}
