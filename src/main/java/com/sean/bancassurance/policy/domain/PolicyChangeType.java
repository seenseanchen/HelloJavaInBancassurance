package com.sean.bancassurance.policy.domain;

/**
 * 保單變更類型 — M5 對應三個 PATCH endpoint。
 *
 * 為什麼用 enum 不用 String？
 *  1. 編譯期型別檢查 — 打錯字 IDE 會抓到，String 要等 runtime
 *  2. switch / canX 等業務邏輯能集中寫在 enum 上 (參考 M3 UnderwritingStatus)
 *  3. JPA 用 @Enumerated(EnumType.STRING) 存 → DB 看得是字串，做報表 SQL 一樣方便
 *
 * (面試題 / 中級)：「@Enumerated(STRING) 跟 ORDINAL 差在哪？為什麼一律用 STRING？」
 *  答：ORDINAL 存 0/1/2，加新 enum constant 在中間就會把舊資料對映歪掉 (災難級 bug)。
 *      STRING 存 "BENEFICIARIES"，DB 對映靠名字 — 加 enum 不影響舊資料。
 *      金融業稽核資料動輒留 7 年，ORDINAL 是炸彈。
 */
public enum PolicyChangeType {
    /** 受益人變更 (集合替換) */
    BENEFICIARIES,
    /** 通訊 / 帳單地址變更 (單欄位) */
    ADDRESS,
    /** 繳費方式變更 (enum 變更) */
    PAYMENT_METHOD
}
