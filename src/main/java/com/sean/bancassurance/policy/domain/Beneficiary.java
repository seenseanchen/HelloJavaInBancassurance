package com.sean.bancassurance.policy.domain;

import com.sean.bancassurance.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
import java.util.UUID;

/**
 * 保單受益人 — Policy 的子實體。
 *
 * 重點 annotation：
 *
 *  @ManyToOne(fetch = LAZY)  ★
 *      - *ToOne 預設 EAGER，這幾乎是 JPA 最大的「預設陷阱」。
 *      - EAGER 會讓你每查一筆 Beneficiary 就自動 SELECT policy → 滾雪球 N+1。
 *      - 一律顯式改 LAZY。
 *      (面試題 / 中級)：「@ManyToOne 為什麼要改 LAZY？」
 *        答：避免無意間觸發父實體查詢；當你真的需要 policy 時再透過 service 觸發
 *           或寫 fetch join，這樣可控。
 *
 *  @JoinColumn(name = "policy_id", nullable = false)
 *      明寫外鍵欄位名。沒寫的話 Hibernate 推導 → 換命名策略時會炸。
 *      與 Policy.beneficiaries 的 mappedBy="policy" 對應。
 *
 *  @Version
 *      跟 Policy 一樣加樂觀鎖 — M5 受益人變更時要避免 lost update。
 *
 *  ★ 不在 Beneficiary 上覆寫 equals/hashCode，原因：
 *     - JPA Entity 在 persist 之前 id 為 null，會讓基於 id 的 hashCode 不穩定。
 *     - List 不需要 hashCode/equals 也能正常運作。
 *     - 真要寫，請用「業務鍵 (idNumber + relationship)」並謹慎處理 null。
 */
@Entity
@Table(name = "policy_beneficiary")
@SQLRestriction("is_deleted = false")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString  // 排除欄位用 field-level @ToString.Exclude (見下方 idNumber / policy)
public class Beneficiary extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * 反向指回 Policy (owning side: policy_beneficiary.policy_id)。
     * 配合 Policy.beneficiaries 的 mappedBy="policy"。
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_id", nullable = false)
    @ToString.Exclude
    private Policy policy;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @ToString.Exclude   // 敏感欄位
    @Column(name = "id_number", nullable = false, length = 32)
    private String idNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship", nullable = false, length = 20)
    private BeneficiaryRelationship relationship;

    @Column(name = "allocation_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal allocationPercentage;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private Boolean deleted = false;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
