package com.sean.bancassurance.policy.repository;

import com.sean.bancassurance.policy.domain.Policy;
import com.sean.bancassurance.policy.domain.PolicyStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 保單 Repository — 同時示範三種查詢風格：
 *
 *   1. Method Naming Query   — findByPolicyNumber       (簡單、固定條件)
 *   2. @Query JPQL           — findByIdWithBeneficiaries (固定但要 JOIN FETCH)
 *   3. JpaSpecificationExecutor (extends 介面)         — 動態組合條件 (清單查詢)
 *
 * 三者並存是為了讓你看到「不同需求要選不同武器」。實務上一個 Repository 裡常常
 * 三種都用得到。
 *
 * ── extends JpaSpecificationExecutor<Policy> ───────────────────────
 *  Spring Data JPA 的「動態查詢神器」。會自動多出三個方法：
 *      Optional<T> findOne(Specification<T>);
 *      List<T>     findAll(Specification<T>);
 *      Page<T>     findAll(Specification<T>, Pageable);
 *  我們在 service 層組 Specification 物件 (用 lambda)，這個 Repository 不用寫實作。
 *
 *  (面試題 / 資深)：「動態查詢條件 5 個欄位都可有可無，會用什麼方式做？」
 *    答：三條路 — Specification (型別安全，IDE 自動提示)、QueryDSL (語法最漂亮，
 *        要多引入 codegen)、Native SQL + StringBuilder (最危險，可能 SQL injection)。
 *        中型專案我選 Specification，大型專案 QueryDSL。
 *
 * ── @EntityGraph 的用法 ─────────────────────────────────────────────
 *  替代 JOIN FETCH 的較新 / 較宣告式寫法。Spring Data JPA 會自動幫你生成
 *  「LEFT JOIN FETCH」的 SQL。
 *  缺點：標 @EntityGraph 後，Page 分頁會報 "HHH000104: firstResult/maxResults
 *  specified with collection fetch" 警告 — 在記憶體裡分頁，效能爛。
 *  解法：清單頁不要 fetch 受益人；單筆查 (getById) 才用 @EntityGraph。
 */
@Repository
public interface PolicyRepository extends
        JpaRepository<Policy, UUID>,
        JpaSpecificationExecutor<Policy> {

    // ─── 1. Method Naming Query ──────────────────────────────────────
    Optional<Policy> findByPolicyNumber(String policyNumber);

    boolean existsByPolicyNumber(String policyNumber);

    /**
     * 「我的保單」清單（給客服 / 業務員看）。
     * 帶分頁；不 fetch 受益人 (列表頁不需要)。
     */
    Page<Policy> findByHolderIdNumber(String holderIdNumber, Pageable pageable);

    /**
     * 依狀態查 — 索引 (status, effective_date DESC) 會被用到。
     */
    Page<Policy> findByStatus(PolicyStatus status, Pageable pageable);


    // ─── 2. @Query JPQL — 帶 JOIN FETCH 一次撈完 (避免 N+1) ──────────
    /**
     * 查單筆保單時連受益人一起帶回來。
     *
     * 這裡用 LEFT JOIN FETCH (而非 @EntityGraph) 的理由：
     *   - 直觀地寫 SQL 形狀，知道自己在 JOIN 什麼
     *   - 不用擔心 collection fetch 與分頁衝突 (本方法回 Optional 不分頁)
     *
     * 副作用：因為 Policy: Beneficiary = 1:N，JOIN 完會有 N 列重複的 Policy。
     *   Hibernate 會用 PK 去重；要強制去重也可以加 DISTINCT 但近版 Hibernate 不需要。
     *
     * (面試題 / 中級)：「N+1 是什麼？怎麼解？」
     *   N+1：先一條 SQL 撈 N 個父實體，再對每個父打一條 SQL 撈子集合 → 1 + N 條。
     *   解法：JOIN FETCH (JPQL) / @EntityGraph / @BatchSize / fetch type EAGER (不推)。
     */
    @Query("""
            SELECT p FROM Policy p
            LEFT JOIN FETCH p.beneficiaries
            WHERE p.id = :id
            """)
    Optional<Policy> findByIdWithBeneficiaries(@Param("id") UUID id);

    /** 同上但用對外保單號 — 客服最常打的查詢 */
    @Query("""
            SELECT p FROM Policy p
            LEFT JOIN FETCH p.beneficiaries
            WHERE p.policyNumber = :policyNumber
            """)
    Optional<Policy> findByPolicyNumberWithBeneficiaries(@Param("policyNumber") String policyNumber);


    // ─── 3. @EntityGraph 對比寫法 (列為參考) ─────────────────────────
    /**
     * 同樣的目的用 @EntityGraph 寫法。看起來更「宣告式」。
     * 我們不打算實際用，但保留註解讓你知道存在。
     *
     * <pre>
     * @EntityGraph(attributePaths = "beneficiaries")
     * Optional<Policy> findWithBeneficiariesById(UUID id);
     * </pre>
     */
}
