package com.sean.bancassurance.underwriting.api.dto;

import com.sean.bancassurance.underwriting.domain.UnderwritingCaseEvent;
import com.sean.bancassurance.underwriting.domain.UnderwritingEventType;
import com.sean.bancassurance.underwriting.domain.UnderwritingStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 案件歷史事件 — 對外回應 DTO。
 *
 * 沒有暴露 createdBy / updatedBy 等技術稽核欄位 (那是給 DBA 看的，
 * 對 client 而言「事件 actor + occurredAt」就夠了)。
 */
public record CaseEventResponse(
        UUID id,
        UUID caseId,
        UnderwritingEventType action,
        UnderwritingStatus fromStatus,   // 可能為 null (建立事件)
        UnderwritingStatus toStatus,
        String actor,
        String comment,
        Instant occurredAt
) {
    public static CaseEventResponse from(UnderwritingCaseEvent e) {
        return new CaseEventResponse(
                e.getId(),
                e.getCaseId(),
                e.getAction(),
                e.getFromStatus(),
                e.getToStatus(),
                e.getActor(),
                e.getComment(),
                e.getOccurredAt()
        );
    }
}
