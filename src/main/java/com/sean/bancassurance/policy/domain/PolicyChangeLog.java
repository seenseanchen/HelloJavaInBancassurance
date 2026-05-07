package com.sean.bancassurance.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 保單變更稽核軌跡 — append-only。
 *
 * 跟 M3 的 UnderwritingCaseEvent 同套思路，差別在記錄的「型別」：
 *   - UnderwritingCaseEvent：from_status / to_status (狀態變動為主)
 *   - PolicyChangeLog       ：before_snapshot / after_snapshot (內容快照為主)
 *
 * 為什麼用 JSON 快照？
 *   M5 的「變更受益人」會動到 1..N 列子表，純 column 寫不下；存 JSON 一次解決。
 *   缺點：不適合直接 SQL 撈 (沒有對特定欄位的索引)，但稽核軌跡本來就以 case-by-case
 *   檢視為主，OK。
 *
 * 為什麼不 extends BaseEntity？
 *   - append-only：沒 updated_*、沒 version、沒 is_deleted
 *   - 仍要 created_at / created_by — 直接複製需要的稽核欄位
 *
 * (面試題 / 資深)：「為什麼 audit log 不能修改？」
 *   答：監管要求「不可篡改」(immutable)。一旦修改紀錄，事後就無法證明
 *       「當下做了什麼」。常見作法：限縮 DB 帳號權限、放獨立 schema、
 *       甚至放 WORM (write-once-read-many) 儲存。
 *
 * ── @JdbcTypeCode(SqlTypes.JSON) ─────────────────────────────────────
 *  Hibernate 6 內建的 JSONB 支援 (PG)。比舊版用 hibernate-types 套件乾淨：
 *    - field 用 Map<String,Object> 或 record / class — Jackson 自動序列化
 *    - DB 端是 JSONB，可以下 GIN 索引、JSON path 查詢
 *
 *  替代方案：
 *    - String 欄位 + ObjectMapper 手動序列化 — 樣板碼多
 *    - hibernate-types-jakarta 第三方套件 — 多一層依賴
 *  Hibernate 6 內建的 @JdbcTypeCode 是現在的最佳解。
 */
@Entity
@Table(name = "policy_change_log")
@EntityListeners(AuditingEntityListener.class)   // 只用 @CreatedDate/@CreatedBy；append-only 沒 update
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class PolicyChangeLog {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "policy_id", nullable = false, updatable = false)
    private UUID policyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, updatable = false, length = 32)
    private PolicyChangeType changeType;

    /**
     * 變更前快照。Hibernate 6 會用 Jackson 序列化成 JSONB。
     *
     * 為什麼不用 record / 自訂類別？
     *   - 變更類型多樣 (受益人 list / 字串地址 / enum 繳費方式) — 用 Map 通吃
     *   - 真要強型別：每個 changeType 一個 record，但 schema-on-write 反而失去 JSON 彈性
     */
    @JdbcTypeCode(SqlTypes.JSON)   // Hibernate 6 內建：對應 PG JSONB
    @Column(name = "before_snapshot", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> beforeSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_snapshot", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> afterSnapshot;

    @Column(name = "reason", updatable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "actor", nullable = false, updatable = false, length = 64)
    private String actor;

    @Column(name = "after_version", nullable = false, updatable = false)
    private Long afterVersion;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    // ─── 稽核欄位 (只用 created_*；不繼承 BaseEntity) ──────────────────

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false, length = 64)
    private String createdBy;

    /**
     * append-only 表本應沒有 update。
     * 但 BaseEntity 的審計 listener 預期表上有 updated_at/updated_by 欄位 (NOT NULL)，
     * 所以我們仍宣告欄位讓 INSERT 能填值；但用 updatable=false 鎖死 — 任何 setter
     * 改了，flush 時 Hibernate 也不會發 UPDATE。
     */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by", nullable = false, length = 64)
    private String updatedBy;
}
