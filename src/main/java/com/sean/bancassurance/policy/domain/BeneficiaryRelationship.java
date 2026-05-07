package com.sean.bancassurance.policy.domain;

/**
 * 受益人與要保人的關係。
 *
 * 為什麼要記？因為法規 (保險法 §16) 規定指定第三人為受益人時要審查「保險利益」，
 * 配偶 / 直系血親免審；OTHER 則需要額外文件。M4 不做檢查，但欄位先留好。
 */
public enum BeneficiaryRelationship {
    /** 配偶 */
    SPOUSE,
    /** 子女 */
    CHILD,
    /** 父母 */
    PARENT,
    /** 兄弟姊妹 */
    SIBLING,
    /** 其他 (法定繼承人 / 第三人) */
    OTHER
}
