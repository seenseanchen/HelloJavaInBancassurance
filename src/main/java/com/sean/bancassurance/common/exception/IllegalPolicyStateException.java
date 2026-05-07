package com.sean.bancassurance.common.exception;

import com.sean.bancassurance.policy.domain.PolicyStatus;

/**
 * 保單目前狀態不允許該操作 → HTTP 409。
 *
 * 觸發情境：
 *   - 保單已 LAPSED (停效)，client 想變更受益人 — 業務上不允許
 *   - 保單 MATURED (滿期)，client 想改地址 — 已結案
 *
 * 為什麼回 409 不是 400？(同一條面試題會被問兩次 — 跟 M3 IllegalStateTransition 同樣道理)
 *   400 = 請求格式錯
 *   409 = 請求格式對，但跟 server 當前狀態衝突
 */
public class IllegalPolicyStateException extends RuntimeException {

    public IllegalPolicyStateException(String message) {
        super(message);
    }

    public static IllegalPolicyStateException notInForce(PolicyStatus current, String operation) {
        return new IllegalPolicyStateException(
                "Cannot %s: policy must be IN_FORCE, but current status is %s"
                        .formatted(operation, current));
    }
}
