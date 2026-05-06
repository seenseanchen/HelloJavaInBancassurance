package com.sean.bancassurance.underwriting.domain;

import com.sean.bancassurance.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 核保案件 — Aggregate Root。
 *
 * Java / JPA / Lombok annotation 速查：
 *
 *  @Entity
 *      標記為 JPA 實體，會被掃描並對應到 DB 表。
 *      ★ 必須有「無參數的 protected/public 建構子」，Hibernate 會用反射建立 proxy。
 *        @NoArgsConstructor 幫我們生出來。
 *
 *  @Table(name = "underwriting_case")
 *      明寫表名。不寫的話 Hibernate 會用 class 名套 NamingStrategy 推導 (UnderwritingCase
 *      → underwriting_case)。為了避免「換 NamingStrategy 全炸」的隱性耦合，明寫安全。
 *
 *  @Id
 *      宣告主鍵欄位。沒有 @GeneratedValue → 我們自己在 Application 端塞 UUID v7。
 *      (面試題 / 中級)：為何不用 @GeneratedValue(strategy = IDENTITY/SEQUENCE)？
 *        - IDENTITY：每筆 INSERT 後 Hibernate 要再 SELECT lastval()，破壞 batch insert。
 *        - SEQUENCE：仍然會洩漏「我們的客戶序號」(競爭情報)。
 *        - UUID v7 由 App 端產生，分散式系統友善 + 索引友善。
 *
 *  @Version
 *      樂觀鎖 (Optimistic Locking) 標記。
 *        - 每次 UPDATE，Hibernate 自動把 version + 1 並在 WHERE 加上原 version 比對。
 *        - 兩個交易同時讀到 v=3，先送出的成功變 v=4；後送出的 WHERE version=3 找不到列
 *          → Hibernate 拋 OptimisticLockException。
 *      M5 保單變更會大量依賴它；M2 階段先預備好欄位，未來不需要再 schema 變更。
 *
 *  @Enumerated(EnumType.STRING)
 *      ★ 必填，理由見 UnderwritingStatus 註解 (絕對不要用 ORDINAL)。
 *
 *  @Column(...)
 *      明寫 nullable / length / precision，這是「Entity 與 DDL 的契約」。
 *      ddl-auto: validate 會在啟動時比對：對不上就拒絕啟動，避免跑到一半才炸。
 *
 *  @SQLRestriction("is_deleted = false")  (Hibernate 6+)
 *      自動在所有 SELECT 加上 WHERE is_deleted = false，實作軟刪除。
 *      取代舊版的 @Where (在 Hibernate 6 之後 deprecated)。
 *      ★ 副作用：之後 NativeQuery 不會自動套用，要自己加條件。
 *
 *  Lombok 三件套 (金融業標配)：
 *    @Getter / @Setter        : 省 getter/setter
 *    @NoArgsConstructor       : JPA 必備的無參數建構子
 *    @AllArgsConstructor      : 配合 @Builder
 *    @Builder                 : 流暢的物件建構，DTO ↔ Entity 轉換很愛用
 *    @ToString(exclude = ...) : log 印出時排除敏感欄位 (id_number) 與會 lazy load 的關聯
 *
 * 設計筆記：
 *  - 為什麼欄位用包裝型別 (Long / BigDecimal / Instant) 而不是基本型別？
 *      JPA 要能表示「未設定」(null) 與「0」的差異；基本型別預設 0，無法區分。
 *  - 為什麼 BaseEntity 不放 id？
 *      不同子類的 ID 策略可能不同 (UUID / BIGINT)，硬抽進 BaseEntity 會限制彈性。
 */
@Entity
@Table(name = "underwriting_case")
@SQLRestriction("is_deleted = false")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"applicantIdNumber"})
public class UnderwritingCase extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "case_number", nullable = false, unique = true, length = 32, updatable = false)
    private String caseNumber;

    @Column(name = "applicant_name", nullable = false, length = 64)
    private String applicantName;

    @Column(name = "applicant_id_number", nullable = false, length = 32)
    private String applicantIdNumber;

    @Column(name = "product_code", nullable = false, length = 32)
    private String productCode;

    /**
     * precision = 18, scale = 2 → numeric(18,2)
     * 18 位數中 2 位是小數，可表達到 9,999,999,999,999,999.99。
     */
    @Column(name = "coverage_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal coverageAmount;

    @Column(name = "premium", nullable = false, precision = 18, scale = 2)
    private BigDecimal premium;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UnderwritingStatus status;

    @Column(name = "submitted_by", nullable = false, length = 64)
    private String submittedBy;

    @Column(name = "reviewed_by", length = 64)
    private String reviewedBy;

    /** TEXT 在 PG 沒長度限制，這裡也不寫 length */
    @Column(name = "review_comment", columnDefinition = "TEXT")
    private String reviewComment;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /** 軟刪除 flag。預設 false；@SQLRestriction 會自動過濾。 */
    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private Boolean deleted = false;

    /**
     * 樂觀鎖版本號。Hibernate 自動維護，一般情況下我們不要手動 set。
     * 用包裝型別 Long 而非 long：JPA 可區分「新物件 (null)」與「已存在 (0)」。
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
