package com.sean.bancassurance.common.exception;

/**
 * 前置條件失敗 → HTTP 412。
 *
 * 觸發情境：client 帶了 If-Match (或 expectedVersion) 但跟伺服器當下版本不符。
 *
 * ── 412 vs 409 — M5 面試最愛問 ──────────────────────────────────────
 *  412 (Precondition Failed)：「你說 if version=0 才更新；現在版本是 2，所以拒絕」
 *      → 是「client 帶條件、條件本身比對失敗」
 *  409 (Conflict)：           「沒帶條件，但實際上跟其他交易撞了」(JPA @Version flush 拋)
 *      → 是「執行中發現衝突」
 *
 *  兩者語意不同：
 *    - 412 是 client 已經顯式表達「我預期是這個版本」(用 If-Match)
 *    - 409 是 client 沒先告訴你他預期什麼，server 自己發現衝突
 *  RFC 7232 §4.2 明確規定 If-Match 失敗用 412。
 *
 * (面試題 / 資深)：「為什麼不一律用 409？」
 *   答：
 *    - HTTP 是設計給「快取 / proxy / CDN」一起合作的；If-Match 是 RFC 標準語意，
 *      用對 status code 才能讓中間層 (例如 CDN purge) 行為正確
 *    - 監控層面：412 跟 409 在儀表板分開顯示 — 412 多 = client 拿錯版本去打；
 *      409 多 = 實際併發衝突。兩種對策不同
 */
public class PreconditionFailedException extends RuntimeException {
    public PreconditionFailedException(String message) {
        super(message);
    }

    public static PreconditionFailedException versionMismatch(long expected, long actual) {
        return new PreconditionFailedException(
                "Version mismatch: expected=%d, actual=%d. Reload the resource and retry."
                        .formatted(expected, actual));
    }
}
