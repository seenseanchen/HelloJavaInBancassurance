package com.sean.bancassurance.policy.domain;

import com.sean.bancassurance.common.audit.BaseEntity;
import com.sean.bancassurance.underwriting.domain.Channel;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 保單 — Aggregate Root。
 *
 * 跟 M2 的 UnderwritingCase 同套基底 (BaseEntity + 軟刪除 + @Version)。
 * 重點差異：本表有「子實體」(受益人 Beneficiary)，所以會出現 @OneToMany — 這是
 * M4 的核心學習點。
 *
 * ── @OneToMany 的關鍵設計決策 ──────────────────────────────────────
 *
 *  fetch = LAZY  (★ 強烈建議的預設)
 *      Hibernate 會在你「真的存取 beneficiaries」時才下 SQL；不存取就不查。
 *      → 列表頁查 100 張保單時，不會自動多打 100 條 SELECT *FROM policy_beneficiary。
 *      ★ 副作用：交易結束 (request 結束) 後再 .getBeneficiaries() → LazyInitializationException
 *        → 解法：在 service 層交易內就轉成 DTO，或用 fetch join / Entity Graph。
 *
 *      (面試題 / 中級)：「@OneToMany 的預設 fetch type 是什麼？」
 *        答：LAZY — JPA 規範中 *ToMany 預設 LAZY、*ToOne 預設 EAGER。但實務上
 *           *ToOne 也常顯式改 LAZY，避免不小心被 join 拖慢。
 *
 *  cascade = ALL
 *      對 Policy 的 persist/merge/remove 會「連動」對 beneficiaries 做。
 *      → 新增保單時把受益人放進 list 一起 save() 即可，不必自己呼叫 BeneficiaryRepository。
 *      ★ 副作用：CASCADE 會放大「誤刪」的影響。如果 Beneficiary 有獨立生命週期，
 *        cascade 不該設 ALL；保單與受益人是「整體 / 部份」(part-whole) → 用 ALL OK。
 *
 *  orphanRemoval = true
 *      從 list 移除某個 Beneficiary → Hibernate 自動 DELETE 該筆 row。
 *      ★ 跟 cascade=REMOVE 的差別 (面試題 / 資深)：
 *         - cascade=REMOVE：刪父 → 刪子。
 *         - orphanRemoval：「子被踢出 list」也算刪。涵蓋 collection 操作。
 *
 *  mappedBy = "policy"
 *      告訴 JPA 真正的外鍵欄位在 Beneficiary.policy 那邊；本表是「反向 (inverse)」端。
 *      → 沒寫 mappedBy 會導致 Hibernate 多開一張中間表 (join table)，幾乎一定不是你要的。
 *
 *  @OrderBy("priority ASC, allocationPercentage DESC")
 *      載入 collection 時自動排序。對 view layer 友善 — Service 不必再排一次。
 *
 *  helper methods (addBeneficiary / removeBeneficiary)
 *      封裝「兩端同步」(維持 owning side 與 inverse side 一致)。
 *      雙向關聯最容易出 bug 的地方就是「只設一邊」，導致 EntityManager 的 first-level
 *      cache 跟 DB 不同步。
 *
 * ── 為什麼用 List 不用 Set？──────────────────────────────────────
 *  - 受益人有「順位 (priority)」，是有序集合 → List 自然。
 *  - Set 用 hashCode/equals 比對，Entity 還沒 persist 時 id 是 null，hashCode 會炸。
 *    要嘛自己定 equals 用業務鍵 (id_number)，要嘛乾脆用 List — 後者簡單。
 *  - 副作用：List + LAZY 載入時 Hibernate 會用 "Bag" 語意，不允許 cartesian product
 *    (兩個 @OneToMany 同時 fetch join) — 一般不會踩到，知道一下就好。
 */
@Entity
@Table(name = "policy")
@SQLRestriction("is_deleted = false")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Policy extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "policy_number", nullable = false, unique = true, length = 40, updatable = false)
    private String policyNumber;

    @Column(name = "product_code", nullable = false, length = 32)
    private String productCode;

    /** 來源核保案件 id：可為 null (M4 種子資料時直接建保單沒走核保) */
    @Column(name = "underwriting_case_id")
    private UUID underwritingCaseId;

    // ───── 要保人 ─────
    @Column(name = "holder_name", nullable = false, length = 64)
    private String holderName;

    @ToString.Exclude   // 敏感欄位，避免出現在 log
    @Column(name = "holder_id_number", nullable = false, length = 32)
    private String holderIdNumber;

    // ───── 被保險人 ─────
    @Column(name = "insured_name", nullable = false, length = 64)
    private String insuredName;

    @ToString.Exclude   // 敏感欄位，避免出現在 log
    @Column(name = "insured_id_number", nullable = false, length = 32)
    private String insuredIdNumber;

    // ───── 金額 ─────
    @Column(name = "coverage_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal coverageAmount;

    @Column(name = "premium", nullable = false, precision = 18, scale = 2)
    private BigDecimal premium;

    @Enumerated(EnumType.STRING)
    @Column(name = "premium_payment_method", nullable = false, length = 20)
    private PremiumPaymentMethod premiumPaymentMethod;

    // ───── 通路 / 狀態 ─────
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PolicyStatus status;

    // ───── 業務日期 ─────
    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "billing_address", nullable = false, length = 255)
    private String billingAddress;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private Boolean deleted = false;

    /** 樂觀鎖 — M5 變更會大量用到 */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // ════════════════════════════════════════════════════════════════
    // OneToMany: 受益人
    // ════════════════════════════════════════════════════════════════

    /**
     * 受益人 (1:N 子集合)。
     * - LAZY：列表 API 不會自動拉受益人；要顯示時 service 會主動觸發或 fetch join。
     * - cascade=ALL + orphanRemoval=true：把受益人當成保單的一部份來管理。
     * - mappedBy="policy"：外鍵在 Beneficiary 端 (policy_id)。
     */
    @OneToMany(
            mappedBy = "policy",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    @OrderBy("priority ASC, allocationPercentage DESC")
    @Builder.Default
    @ToString.Exclude
    private List<Beneficiary> beneficiaries = new ArrayList<>();

    /**
     * 兩端同步 helper。永遠用這個方法新增受益人。
     * <pre>
     *   policy.addBeneficiary(b);   // 自動把 b.policy 設成 this，並加進 list
     * </pre>
     */
    public void addBeneficiary(Beneficiary b) {
        beneficiaries.add(b);
        b.setPolicy(this);
    }

    /**
     * 移除受益人。orphanRemoval=true 會在 commit 時自動 DELETE。
     */
    public void removeBeneficiary(Beneficiary b) {
        beneficiaries.remove(b);
        b.setPolicy(null);
    }
}
