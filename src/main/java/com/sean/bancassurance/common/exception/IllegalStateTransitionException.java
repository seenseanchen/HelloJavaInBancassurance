package com.sean.bancassurance.common.exception;

/**
 * 業務狀態機：不合法的狀態跳轉。
 *
 * 為什麼自訂這個例外，而不是直接用 IllegalStateException？
 *  - IllegalStateException 是 Java 內建語意「物件當前狀態不允許此操作」，太籠統
 *  - 自訂例外可在 ControllerAdvice 中精準對應 HTTP 409 Conflict
 *  - 攜帶結構化資訊 (from / to / reason) → log 與 client 排查友善
 *
 * 為什麼回 HTTP 409 而不是 400？
 *  - 400 Bad Request = client 送錯格式
 *  - 409 Conflict     = 請求格式對，但跟伺服器目前狀態衝突 (例如想把已 APPROVED 的退件)
 *  - 這個語意正好對應狀態機的「非法跳轉」與樂觀鎖衝突
 *
 * (面試題 / 中級)：「為什麼樂觀鎖衝突跟非法狀態跳轉都用 409？是同一件事嗎？」
 *  - 兩者都是「請求合法但與伺服器狀態衝突」，所以共用 409 status code
 *  - 但 error code 不同 (INVALID_STATE_TRANSITION vs OPTIMISTIC_LOCK)，client 可以分開處理
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final String from;
    private final String to;

    public IllegalStateTransitionException(String from, String to, String reason) {
        super("Illegal transition from %s to %s: %s".formatted(from, to, reason));
        this.from = from;
        this.to = to;
    }

    public IllegalStateTransitionException(Enum<?> from, Enum<?> to) {
        this(
                from == null ? "(none)" : from.name(),
                to == null ? "(none)" : to.name(),
                "transition not allowed by state machine"
        );
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }
}
