package com.sean.bancassurance.policy.domain;

/**
 * 保費繳費方式。
 *
 * 銀行業一個有趣的小細節：銀保通路客戶最常選 SINGLE_PAY (躉繳)，
 * 因為在銀行櫃台一筆下單就完成；業務員通路則 MONTHLY 居多 (持續服務 + 年金規劃)。
 * 這也是 M4 清單查詢的常見過濾條件之一。
 */
public enum PremiumPaymentMethod {
    /** 月繳 */
    MONTHLY,
    /** 季繳 */
    QUARTERLY,
    /** 半年繳 */
    SEMI_ANNUAL,
    /** 年繳 */
    ANNUAL,
    /** 躉繳 (一次付清) */
    SINGLE_PAY
}
