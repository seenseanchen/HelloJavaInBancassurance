package com.sean.bancassurance.auth.repository;

import com.sean.bancassurance.auth.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 應用使用者 Repository。
 *
 *  ── 為什麼只有 findByUsername？─────────────────────────────────────────
 *
 *  M9 階段的需求只有兩個：
 *    1. 登入時 by username 載入 → loadUserByUsername(...)
 *    2. （未來）依 id 載入 → JpaRepository 已內建 findById(...)
 *
 *  其他「列出所有 user / 刪除 user / 改密碼」屬於使用者管理功能，
 *  本專案 scope 不做（會額外要 admin 介面、忘記密碼流程、密碼複雜度規則...）。
 *  種子資料就是測試用的三個帳號，不需要管理 API。
 *
 *  ── 為什麼不寫 @Query JPQL，用 Spring Data 的 method naming？─────────────
 *
 *  Spring Data 的「method name → JPQL」轉換規則：
 *    findBy<FieldName>(...)        → SELECT u FROM AppUser u WHERE u.<field> = ?
 *    findByUsername(String x)      → ... WHERE u.username = x
 *
 *  優點：規則明確，IDE 補全友善，不必維護 JPQL 字串。
 *  缺點：複雜查詢會變很長 (findByXxxAndYyyOrZzzOrderByAaaDesc) — 此時改 @Query。
 *
 *  我們這邊一個欄位 + 一個動詞，method naming 最乾淨。
 *
 *  ── @ElementCollection 的 fetch 策略 ────────────────────────────────────
 *
 *  AppUser.roles 已標 fetch=EAGER，呼叫 findByUsername(...) 時 Hibernate 會：
 *    1. SELECT * FROM app_user WHERE username = ?
 *    2. SELECT * FROM app_user_role WHERE user_id = ?
 *
 *  兩條 SELECT，沒有 N+1 問題（因為只查單一 user）。如果改 LAZY，service 呼叫
 *  user.getRoles() 時若交易已關 → LazyInitializationException。
 *
 *  (面試題 / 中級)：「@EntityGraph 跟 fetch=EAGER 怎麼選？」
 *    - fetch=EAGER：欄位上設定，永遠生效
 *    - @EntityGraph：query 上設定，只對該次生效
 *  本案 AppUser.roles 永遠都要載入 → fetch=EAGER 簡單；
 *  Policy.beneficiaries 只在 detail 頁載入 → @EntityGraph 或 JPQL JOIN FETCH 更彈性。
 *  M4 寫 PolicyRepository 時就是這個思路。
 */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /**
     * 依 username 載入 user (含 roles，因為 roles fetch=EAGER)。
     * 找不到時回 Optional.empty() — caller 自己決定要不要丟 UsernameNotFoundException。
     */
    Optional<AppUser> findByUsername(String username);
}
