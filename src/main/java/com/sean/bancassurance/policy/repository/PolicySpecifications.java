package com.sean.bancassurance.policy.repository;

import com.sean.bancassurance.policy.domain.Policy;
import com.sean.bancassurance.policy.domain.PolicyStatus;
import com.sean.bancassurance.underwriting.domain.Channel;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;

/**
 * Policy 動態查詢 Specification 工廠。
 *
 * 為什麼把這些抽成 static method？
 *   - 重用：Service / 測試 / 報表都能組合
 *   - 可讀性：service 層寫成 where(holderIs("A")).and(statusIs(IN_FORCE)) 像在念句子
 *   - 條件可有可無：傳入 null 直接回 conjunction (true)，組合時自動忽略
 *
 * Specification = 把 JPA Criteria API 包成 lambda。
 *   底層三個物件：
 *     - root        : SQL 中的 FROM Policy → 用 root.get("欄位")
 *     - query       : 整個 CriteriaQuery (可改 distinct / order)
 *     - criteriaBuilder : 製造 Predicate 的工廠 (.equal / .like / .greaterThan)
 *
 * (面試題 / 中級)：「Specification 跟 QueryDSL 你怎麼選？」
 *   - 專案小、團隊熟 JPA → Specification 即可，無額外依賴
 *   - 專案大、查詢複雜 (多 JOIN / 子查詢) → QueryDSL 語法更直覺，但要 codegen
 *
 * (面試題 / 資深)：「Specification 寫到 5 個 .and() 卡頓的話怎麼優化？」
 *   - 每條 .and 各自是 Predicate；Hibernate 會合成單一 SQL 不會分次打
 *   - 真正瓶頸通常在「沒索引」，先看執行計畫再說
 */
public final class PolicySpecifications {

    private PolicySpecifications() { /* utility class — 不要 new */ }

    /**
     * 安全的 equal Spec：值為 null 就回「永遠 true」(不加條件)。
     * 這是動態查詢的核心技巧 — 把「是否啟用此條件」與 SQL 拼接解耦。
     */
    public static Specification<Policy> holderIdNumberIs(String idNumber) {
        return (root, query, cb) ->
                idNumber == null ? cb.conjunction() : cb.equal(root.get("holderIdNumber"), idNumber);
    }

    public static Specification<Policy> statusIs(PolicyStatus status) {
        return (root, query, cb) ->
                status == null ? cb.conjunction() : cb.equal(root.get("status"), status);
    }

    public static Specification<Policy> productCodeIs(String productCode) {
        return (root, query, cb) ->
                productCode == null ? cb.conjunction() : cb.equal(root.get("productCode"), productCode);
    }

    public static Specification<Policy> channelIs(Channel channel) {
        return (root, query, cb) ->
                channel == null ? cb.conjunction() : cb.equal(root.get("channel"), channel);
    }

    /**
     * 生效日範圍 (含起訖)。範例：查 2026-01-01 ~ 2026-12-31 的保單。
     * 兩個值都可為 null：只有上界 / 下界 / 都沒 都能正常組合。
     */
    public static Specification<Policy> effectiveDateBetween(LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            if (from == null && to == null) return cb.conjunction();
            if (from != null && to != null) return cb.between(root.get("effectiveDate"), from, to);
            if (from != null) return cb.greaterThanOrEqualTo(root.get("effectiveDate"), from);
            return cb.lessThanOrEqualTo(root.get("effectiveDate"), to);
        };
    }
}
