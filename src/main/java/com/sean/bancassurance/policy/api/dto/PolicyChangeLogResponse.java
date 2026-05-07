package com.sean.bancassurance.policy.api.dto;

import com.sean.bancassurance.policy.domain.PolicyChangeLog;
import com.sean.bancassurance.policy.domain.PolicyChangeType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 變更歷史 DTO — GET /api/policies/{id}/changes 回傳。
 *
 * 直接把 before/after JSON 原樣吐回去 (Map<String, Object>)。
 * 為什麼不轉成強型別？
 *   - 不同 changeType 的 snapshot 結構不一樣 (受益人 list / 字串 / enum)
 *   - 強型別要做 sealed interface + 多個 record (BeneficiariesSnapshot / AddressSnapshot ...)
 *     換得「IDE 提示」，但對「給人看的歷史記錄」沒實際價值
 *   - Map 直接序列化成 JSON 物件，client 用即可
 */
public record PolicyChangeLogResponse(
        UUID id,
        UUID policyId,
        PolicyChangeType changeType,
        Map<String, Object> beforeSnapshot,
        Map<String, Object> afterSnapshot,
        String reason,
        String actor,
        Long afterVersion,
        Instant occurredAt
) {
    public static PolicyChangeLogResponse from(PolicyChangeLog log) {
        return new PolicyChangeLogResponse(
                log.getId(),
                log.getPolicyId(),
                log.getChangeType(),
                log.getBeforeSnapshot(),
                log.getAfterSnapshot(),
                log.getReason(),
                log.getActor(),
                log.getAfterVersion(),
                log.getOccurredAt()
        );
    }
}
