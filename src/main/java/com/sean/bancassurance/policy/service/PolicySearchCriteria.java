package com.sean.bancassurance.policy.service;

import com.sean.bancassurance.policy.domain.PolicyStatus;
import com.sean.bancassurance.underwriting.domain.Channel;

import java.time.LocalDate;

/**
 * 保單清單查詢條件 — 全部欄位皆可為 null (動態組合)。
 *
 *  為什麼用 record 包成獨立的 criteria 物件？
 *    - 比直接傳 5 個 method 參數清楚 (避免「forgot to update one call site」)
 *    - 之後新增欄位只要改 record，不用動所有 caller 的 method 簽名
 *    - 易測試：在單元測試中可以 new PolicySearchCriteria(...) 直接餵
 *
 *  (面試題 / 中級)：「為什麼用 record 而不用 Lombok @Value？」
 *    - record 是 Java 14+ 內建語法，無外部依賴、IDE / Jackson 都認得
 *    - 自動產生 equals/hashCode/toString
 *    - 缺點：欄位 final 不能改 — 但對「查詢條件」這種 immutable 物件正合適
 */
public record PolicySearchCriteria(
        String holderIdNumber,
        PolicyStatus status,
        String productCode,
        Channel channel,
        LocalDate effectiveDateFrom,
        LocalDate effectiveDateTo
) {
    /** 全空條件：等於「查全部」(分頁仍會生效) */
    public static PolicySearchCriteria empty() {
        return new PolicySearchCriteria(null, null, null, null, null, null);
    }
}
