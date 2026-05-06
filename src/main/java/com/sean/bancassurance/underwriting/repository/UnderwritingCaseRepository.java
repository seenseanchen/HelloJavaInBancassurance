package com.sean.bancassurance.underwriting.repository;

import com.sean.bancassurance.underwriting.domain.UnderwritingCase;
import com.sean.bancassurance.underwriting.domain.UnderwritingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 核保案件 Repository。
 *
 *  繼承 JpaRepository<Entity, ID>
 *      → 自動取得 save / findById / findAll / count / delete 等方法。
 *      ★ 不需要自己實作；Spring Data JPA 會在啟動時用 dynamic proxy 生成實體類。
 *
 *  @Repository
 *      可省略 (JpaRepository 子介面會被 @EnableJpaRepositories 自動掃描)，
 *      但加上去語意明確、IDE 也能正確標記。
 *
 *  Method Naming Query (方法命名查詢)
 *      Spring Data JPA 會根據方法名稱解析成 SQL：
 *        findByCaseNumber           → SELECT ... WHERE case_number = ?
 *        findByStatusOrderByCreated → SELECT ... WHERE status = ? ORDER BY created_at
 *      ★ 拼錯欄位名 → 啟動時就拋例外 (fail fast)。比寫 native SQL 安全。
 *
 *  Pageable / Page
 *      分頁支援。Service / Controller 接 ?page=0&size=20 自動轉成 Pageable。
 *      回 Page 而非 List：總筆數一併送給前端做分頁列。
 *
 * (面試題 / 中級)：JpaRepository / CrudRepository / PagingAndSortingRepository 差別？
 *   答：JpaRepository = CrudRepository + PagingAndSortingRepository + JPA 特有 (flush, batch)。
 *       一般專案直接用 JpaRepository 即可。
 *
 * (面試題 / 中級)：為什麼 findById 回 Optional<T>？
 *   答：強迫呼叫端面對「找不到」的情境，避免 NullPointerException。
 *       配合 .orElseThrow(...) 寫起來簡潔。
 */
@Repository
public interface UnderwritingCaseRepository extends JpaRepository<UnderwritingCase, UUID> {

    /** 用對外案件編號查詢 */
    Optional<UnderwritingCase> findByCaseNumber(String caseNumber);

    /** 列表頁：依狀態過濾 + 分頁 */
    Page<UnderwritingCase> findByStatus(UnderwritingStatus status, Pageable pageable);

    boolean existsByCaseNumber(String caseNumber);
}
