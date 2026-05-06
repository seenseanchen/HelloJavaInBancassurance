package com.sean.bancassurance.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 所有業務 Entity 的稽核基底。
 *
 * 關鍵 annotation：
 *
 *  @MappedSuperclass
 *      告訴 JPA：這個 class 不是一張表，但它的欄位「會被」子類別繼承並映射。
 *      子類別 (例如 UnderwritingCase) 對應的表會自動多出 created_at, created_by 等欄位。
 *      ★ 跟 @Entity 互斥 — 自己不會生 Hibernate 代理，也不能查詢。
 *
 *  @EntityListeners(AuditingEntityListener.class)
 *      啟用 Spring Data JPA 的自動稽核：
 *        - 在 INSERT 之前填 createdAt / createdBy
 *        - 在 UPDATE 之前更新 updatedAt / updatedBy
 *      ★ 必須搭配主啟動類別上的 @EnableJpaAuditing 才會生效（見 JpaAuditingConfig）。
 *
 *  @CreatedDate / @LastModifiedDate
 *      時間自動填，不用 service 自己 set。
 *      ★ 型別可選 Date / Instant / LocalDateTime；金融業強烈建議 Instant (UTC)。
 *
 *  @CreatedBy / @LastModifiedBy
 *      使用者自動填，需要透過 AuditorAware<String> bean 提供「目前是誰」。
 *      M9 接 Spring Security 後，這裡就會自動拿到登入者；M2 階段先回傳 "system"。
 *
 * 為什麼用 Instant 而不是 LocalDateTime？
 *  Instant 是「絕對時間」(UTC 時間軸上的一個點)；LocalDateTime 沒有時區概念，
 *  在跨時區系統會出錯。金融業跨時區是常態 (台北 / 香港 / 紐約)，一律用 Instant。
 *
 * (面試題 / 中級)：
 *   - 為什麼選 @MappedSuperclass 而不是 @Inheritance(SINGLE_TABLE/JOINED/TABLE_PER_CLASS)？
 *     答：我們不需要「多型查詢」(查 BaseEntity 拉出所有子類)；只是想共用欄位定義。
 *         @MappedSuperclass 最輕量，沒有 discriminator column 的複雜度。
 */
@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false, length = 64)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by", nullable = false, length = 64)
    private String updatedBy;
}
