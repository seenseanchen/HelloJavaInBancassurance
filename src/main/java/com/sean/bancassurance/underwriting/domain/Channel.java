package com.sean.bancassurance.underwriting.domain;

/**
 * 投保通路 (送件來源)。
 *
 * BANCASSURANCE 是本專案的主軸：銀行櫃員 / 銀行理專推薦保險商品給客戶投保。
 * 不同通路的核保規則、佣金、備件要求都不同，所以欄位獨立、不要混進 submitted_by。
 */
public enum Channel {
    /** 銀保通路：銀行端送件 */
    BANCASSURANCE,
    /** 業務員通路 */
    AGENT,
    /** 線上投保 (eCommerce) */
    ONLINE
}
