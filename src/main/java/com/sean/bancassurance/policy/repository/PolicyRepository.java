package com.sean.bancassurance.policy.repository;

import com.sean.bancassurance.policy.domain.Policy;
import com.sean.bancassurance.policy.domain.PolicyStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
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


    // ════════════════════════════════════════════════════════════════
    // 4. 悲觀鎖示範 (M5) — 純對比教學，service 預設走樂觀鎖路徑
    // ════════════════════════════════════════════════════════════════

    /**
     * 用悲觀寫鎖載入保單 — 對應 SQL: SELECT ... FOR UPDATE
     *
     * ── @Lock(PESSIMISTIC_WRITE) 行為 ────────────────────────────────
     *  Hibernate 會把 SELECT 改成 SELECT ... FOR UPDATE：
     *    - PostgreSQL：取得 row-level write lock，其他交易要 SELECT FOR UPDATE 會等
     *    - 等到本交易 commit / rollback 才釋放
     *    - 普通 SELECT 不受影響 (PG 預設 Read Committed)
     *
     * ── 樂觀鎖 vs 悲觀鎖 — 何時選哪個？ ─────────────────────────────────
     *
     *  樂觀鎖 (@Version)：
     *    優點：不鎖、效能好、不會 deadlock
     *    缺點：衝突時 client 要重試 → UX 不佳；高衝突場景重試風暴
     *    適合：寫入競爭低、客戶一次只改一張保單 (M5 場景)
     *
     *  悲觀鎖 (FOR UPDATE)：
     *    優點：不會撞鎖回 409，client 體驗順
     *    缺點：鎖佔資源、可能 deadlock、效能差
     *    適合：高頻寫入單一資源 (例如熱門商品庫存扣減)、跨多表的複雜更新
     *
     *  M5 場景：銀保系統「同一張保單同時被改」機率極低；用樂觀鎖足夠。
     *  反例：M10 商品「秒殺」庫存扣減 — 那必須悲觀鎖或 Redis SETNX。
     *
     * (面試題 / 資深)：「悲觀鎖 deadlock 怎麼避免？」
     *   答：
     *    1. 一律按固定順序拿鎖 (e.g. 先小 id 再大 id)
     *    2. 設 lock timeout — PG 用 SET LOCAL lock_timeout
     *    3. 監控 deadlock，重試小範圍
     *
     * ── 為什麼這支方法不直接被 PolicyChangeService 用？ ────────────────
     *  M5 我們示範的是樂觀鎖 (面試主流答案)。這支方法保留是為了：
     *    - 整合測試示範「悲觀鎖在併發下不會 lost update」
     *    - 面試話術可以說「我兩種都實作過，這個專案選樂觀鎖是因為...」
     *
     * 注意：用悲觀鎖的方法必須在 @Transactional 裡呼叫 — 沒交易就沒鎖！
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Policy p WHERE p.id = :id")
    Optional<Policy> findByIdForUpdate(@Param("id") UUID id);
}
