package com.sean.bancassurance.common.config;

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
 *      Spring Boot 4 還新加了 @ImportRuntimeHints 等支援 AOT，這裡用基本的就夠。
 *
 *  @EnableJpaAuditing(auditorAwareRef = "auditorProvider")
 *      → 讓 BaseEntity 上的 @CreatedDate / @CreatedBy 等真的有效。
 *      → auditorAwareRef 指向下方的 bean 名字，告訴 JPA「@CreatedBy 要去哪取值」。
 *
 *  AuditorAware<String>
 *      Spring Data 提供的介面：getCurrentAuditor() 回傳目前是誰。
 *      M2 階段尚未接 Spring Security，固定回 "system"。
 *      M9 接 JWT 後改成：
 *          SecurityContextHolder.getContext().getAuthentication().getName()
 *
 * 常見坑：
 *   * 忘了加 @EnableJpaAuditing → BaseEntity 的時間欄位永遠是 null，啟動 INSERT 直接炸
 *     ("not-null property references a null value")。
 *   * AuditorAware 回 Optional.empty() → @CreatedBy 也會是 null，DB 欄位若是 NOT NULL 一樣炸。
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<String> auditorProvider() {
        // M9 之後改成從 SecurityContext 拿登入者；現階段固定 "system"。
        return () -> Optional.of("system");
    }
}
