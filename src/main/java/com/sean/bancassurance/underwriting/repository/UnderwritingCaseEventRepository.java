package com.sean.bancassurance.underwriting.repository;

import com.sean.bancassurance.underwriting.domain.UnderwritingCaseEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 核保案件事件 Repository。
 *
 * 設計重點：append-only。
 *  - 我們**不開放** delete / deleteAll / deleteById 給外部呼叫
 *    (Spring Data JPA 的 deleteById 仍會在 base interface 上存在，
 *     但團隊規範禁止呼叫；可在 Code Review 階段擋掉，或之後加 ArchUnit 規則)
 *  - update 也不開放：Entity 上的欄位都標 updatable = false (見 UnderwritingCaseEvent)
 */
@Repository
public interface UnderwritingCaseEventRepository extends JpaRepository<UnderwritingCaseEvent, UUID> {

    /**
     * 撈某案件的完整歷史，照發生時間排序。
     */
    List<UnderwritingCaseEvent> findByCaseIdOrderByOccurredAtAsc(UUID caseId);
}
