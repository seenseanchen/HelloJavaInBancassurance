package com.sean.bancassurance.auth.domain;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * 應用角色 — 對應 V6 migration 的 app_user_role.role CHECK 約束。
 *
 *  ADMIN        系統管理員：全部都能做，緊急情況使用
 *  UNDERWRITER  核保員：審查案件、核准/退件；不能改保單 (職務區隔)
 *  CSR          客服 / 業務員：送件、查保單、改保單；不能 approve/reject (避免自審)
 *
 *  ── 為什麼 enum 名稱不加 "ROLE_" 前綴？─────────────────────────────────
 *
 *  Spring Security 約定：
 *      hasRole("ADMIN")        → 真正比對的是 GrantedAuthority "ROLE_ADMIN"
 *      hasAuthority("ROLE_X")  → 直接字串比對，不自動加前綴
 *      hasAuthority("READ")    → 直接字串比對，常用於「權限 (permission)」級的細粒度
 *
 *  也就是說：
 *      ✔ enum 名稱 ADMIN      （業務語意）
 *      ✔ DB 存 'ADMIN'        （schema 乾淨）
 *      ✔ Authority 字串 "ROLE_ADMIN"（Spring Security 慣例）
 *
 *  toAuthority() 這個 helper 就是處理「業務語意 → Spring Security 字串」的翻譯。
 *  只在「轉成 GrantedAuthority」時才加 ROLE_ 前綴，邊界處理乾淨。
 *
 *  (面試題 / 中級)：「ROLE 跟 PRIVILEGE 在 Spring Security 怎麼區分？」
 *    - Role：粗粒度，描述「身份」(ADMIN / USER)。Spring Security 慣例加 ROLE_ 前綴。
 *    - Privilege / Authority：細粒度，描述「能做什麼」(READ_REPORT / APPROVE_LOAN)。
 *    - 大型系統會做「Role → Privilege」一對多映射 (RBAC 的標準設計)。
 *      我們這個專案規模不需要，直接 Role 就夠用。
 *
 *  (面試題 / 資深)：「ABAC vs RBAC 怎麼選？」
 *    - RBAC (Role-Based)：簡單，「核保員可以 approve」這種 if-by-role 規則就夠
 *    - ABAC (Attribute-Based)：靈活，「核保員只能 approve 自己領件的案子且金額 <500萬」
 *      這種多維度條件規則，需要 ABAC（attributes: actor / resource / action / environment）
 *    - 業界現實：99% 系統 RBAC 就夠；ABAC 用在政府 / 金融超複雜場景，需要 XACML 之類的政策引擎
 */
public enum AppRole {

    ADMIN,
    UNDERWRITER,
    CSR;

    /**
     * 轉成 Spring Security 認得的 GrantedAuthority。
     *
     *  為什麼用 SimpleGrantedAuthority 而不是自己 implements GrantedAuthority？
     *    - SimpleGrantedAuthority 是 Spring Security 內建、最常見的實作
     *    - 唯一作用就是 String → GrantedAuthority 的薄包裝
     *    - 自己實作沒有任何加分，只是多寫 code
     */
    public GrantedAuthority toAuthority() {
        // ROLE_ 前綴是 Spring Security 慣例，hasRole("ADMIN") 內部就是比 "ROLE_ADMIN"
        return new SimpleGrantedAuthority("ROLE_" + name());
    }
}
