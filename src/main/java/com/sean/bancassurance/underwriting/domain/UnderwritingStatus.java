package com.sean.bancassurance.underwriting.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 核保案件狀態 + 狀態機規則。
 *
 * 重要設計：
 *
 * 1. 為什麼用 enum 而不是 int / String 常數？
 *    - 編譯期型別檢查：傳錯狀態值，編譯就失敗。
 *    - IDE 可枚舉所有值，重構安全 (改名會連鎖更新所有使用點)。
 *    - enum 在 Java 是「特殊的 class」，可以加方法、實作介面、放靜態欄位。
 *
 * 2. 為什麼 DB 端用 VARCHAR + JPA @Enumerated(EnumType.STRING)？
 *    - 絕對不要用 EnumType.ORDINAL：它把順序 (0,1,2...) 寫進 DB。
 *      日後在中間插入新狀態 (例如 SUBMITTED 與 UNDER_REVIEW 之間插一個)，
 *      整張表的舊資料全錯位。這在金融業是「災難級 bug」。
 *
 * 3. 狀態機 (State Machine) — 「表驅動」實作：
 *
 *    把「合法轉移」放進 EnumMap<From, EnumSet<To>>，
 *    Service 端只要呼叫 from.canTransitionTo(to)，
 *    完全不用 if-else / switch。新增狀態 → 改一行表。
 *
 *    (面試題 / 中級)：「業務狀態機 100 行 if-else 怎麼重構？」
 *      答：表驅動 / 策略模式 / Spring StateMachine 三選一。
 *          狀態機規則穩定 → 表驅動最便宜。複雜 → 策略模式。
 *          跨服務、需要持久化整個 state graph → Spring StateMachine。
 *
 *    (面試題 / 資深)：「兩個核保員同時對同一案件按 approve、reject 怎麼辦？」
 *      答：1) Service 層先 reload entity 驗證狀態 (但仍可能 race)；
 *          2) 真正擋下來的是 @Version 樂觀鎖 (M2 已埋好)：
 *             先送出的成功 version+1，後送出的 WHERE version=N 找不到列 → 拋 OptimisticLockException
 *             → 我們翻成 HTTP 409 Conflict。
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
    WITHDRAWN;

    /**
     * 合法狀態轉移表。
     *
     * 為什麼用 EnumMap + EnumSet 而不是 HashMap + HashSet？
     *  - EnumMap / EnumSet 內部用 bit-array / 連續陣列，效能比 hash 容器好
     *  - key/value 都限定在 enum 值，型別安全
     *  - 這類「以 enum 為 key 的對應」就應該用 EnumMap (Effective Java Item 37)
     *
     * static 初始化區塊：在 class 載入時跑一次，之後 immutable。
     * 為什麼不用 Map.of(...) 一行寫完？
     *  - Map.of 會回傳 java.util.ImmutableCollections，雖然不可變但不是 EnumMap
     *  - 我們要 EnumMap 的效能特性，所以手動 put + 包 unmodifiableMap
     */
    private static final Map<UnderwritingStatus, Set<UnderwritingStatus>> ALLOWED_TRANSITIONS;

    static {
        EnumMap<UnderwritingStatus, Set<UnderwritingStatus>> map = new EnumMap<>(UnderwritingStatus.class);
        map.put(SUBMITTED,    EnumSet.of(UNDER_REVIEW, WITHDRAWN));
        map.put(UNDER_REVIEW, EnumSet.of(APPROVED, REJECTED, PENDING_INFO, WITHDRAWN));
        map.put(PENDING_INFO, EnumSet.of(UNDER_REVIEW, WITHDRAWN));
        // 三個 terminal state，明確放空 set 比省略好讀，也避免 NPE
        map.put(APPROVED,     EnumSet.noneOf(UnderwritingStatus.class));
        map.put(REJECTED,     EnumSet.noneOf(UnderwritingStatus.class));
        map.put(WITHDRAWN,    EnumSet.noneOf(UnderwritingStatus.class));
        ALLOWED_TRANSITIONS = java.util.Collections.unmodifiableMap(map);
    }

    /**
     * 我能轉到 target 嗎？
     */
    public boolean canTransitionTo(UnderwritingStatus target) {
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    /**
     * 我是 terminal state 嗎？(已核准 / 退件 / 撤件)
     */
    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }

    /**
     * 取得我所有合法的下一步 (供 API / UI 顯示「可做的動作」清單用)。
     * 回傳 unmodifiable，呼叫端不能加減內容。
     */
    public Set<UnderwritingStatus> nextStates() {
        return ALLOWED_TRANSITIONS.get(this);
    }
}
