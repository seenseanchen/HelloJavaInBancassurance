package com.sean.bancassurance.common.config;

import com.sean.bancassurance.auth.util.SecurityUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

/**
 * 啟用 Spring Data JPA Auditing。
 *
 *  @Configuration
 *      告訴 Spring：「這個 class 內 @Bean 標記的方法會生產 bean」。
 *
 *  @EnableJpaAuditing(auditorAwareRef = "auditorProvider")
 *      → 讓 BaseEntity 上的 @CreatedDate / @CreatedBy 等真的有效。
 *      → auditorAwareRef 指向下方的 bean 名字，告訴 JPA「@CreatedBy 要去哪取值」。
 *
 *  AuditorAware<String>
 *      Spring Data 提供的介面：getCurrentAuditor() 回傳目前是誰。
 *      M9 之前固定回 "system"；M9.5 改成從 SecurityContext 取登入者，
 *      沒登入時 fallback 到 "system" (例如 Flyway 啟動 / 背景排程)。
 *
 *  ── 為什麼用 SecurityUtils 而非直接 SecurityContextHolder.getContext()...？
 *
 *  SecurityUtils.currentUsernameForAudit() 已經處理好：
 *    - null Authentication
 *    - "anonymousUser" → fallback "system"
 *    - AppUserPrincipal 或非 AppUserPrincipal 都能拿 name
 *  集中在 util，避免每個 AuditorAware 都自己寫一遍。
 *
 *  ── 常見坑 ────────────────────────────────────────────────────────────
 *   * 忘了加 @EnableJpaAuditing → BaseEntity 的時間欄位永遠是 null，INSERT 直接炸
 *     ("not-null property references a null value")。
 *   * AuditorAware 回 Optional.empty() → @CreatedBy 也會是 null，DB 欄位若是 NOT NULL 一樣炸。
 *     所以 fallback 字串很重要。
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<String> auditorProvider() {
        // 永遠回非空 Optional：登入時是 username，否則是 "system"
        // (有登入 / 無登入兩條路徑都安全，避免 @CreatedBy 變 null 觸發 NOT NULL 例外)
        return () -> Optional.of(SecurityUtils.currentUsernameForAudit());
    }
}
