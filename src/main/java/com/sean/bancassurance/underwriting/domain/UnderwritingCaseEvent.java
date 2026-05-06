package com.sean.bancassurance.underwriting.domain;

import com.sean.bancassurance.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * 核保案件事件 (Audit Log)。
 *
 * 設計取捨：
 *
 * 1. 為什麼這裡只存 case_id (UUID) 而不是用 @ManyToOne 關聯到 UnderwritingCase？
 *    - 事件表是 append-only 的「日誌」，不參與案件主流程的物件圖
 *    - 多對一關聯會誘惑你寫 event.getCase().setStatus(...) — 違反 append-only 原則
 *    - 用裸 UUID 故意讓「想偷改主表」變得不順手
 *    - 查詢時要 join 也很簡單：JPQL 寫個 case_id 條件即可
 *
 *    (面試題 / 中級)：「JPA 關聯一定要建嗎？什麼時候不該建？」
 *      答：當「兩端的生命週期不對稱」(主表是聚合根，事件只是側錄)、或「想避免
 *          無意間 cascade / lazy loading」時，把外鍵當普通欄位處理反而更乾淨。
 *
 * 2. 為什麼不繼承 BaseEntity 但要有稽核欄位？
 *    答：本表只 INSERT 不 UPDATE，updated_at/by 永遠等於 created_at/by，但仍保留欄位
 *    讓查詢 SQL 跟其他表一致。沿用 BaseEntity 是最省力的做法。
 *
 * 3. 為什麼欄位上不用 @Version？
 *    答：append-only，不會 UPDATE，沒有 lost update 風險。
 */
@Entity
@Table(name = "underwriting_case_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnderwritingCaseEvent extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32, updatable = false)
    private UnderwritingEventType action;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20, updatable = false)
    private UnderwritingStatus fromStatus;   // 建立事件時為 null

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20, updatable = false)
    private UnderwritingStatus toStatus;

    @Column(name = "actor", nullable = false, length = 64, updatable = false)
    private String actor;

    @Column(name = "comment", columnDefinition = "TEXT", updatable = false)
    private String comment;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
}
