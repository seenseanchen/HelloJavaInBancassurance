package com.sean.bancassurance.auth.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 應用使用者 Entity。
 *
 *  ── 為什麼「不」extends BaseEntity？─────────────────────────────────────
 *  BaseEntity 上掛了 AuditingEntityListener，會在 INSERT/UPDATE 時呼叫 AuditorAware
 *  去取「目前是誰」(SecurityContextHolder)。但本表是「使用者表」本身：
 *    - 啟動時 V6 種子 (Flyway 直接 INSERT，繞過 JPA listener) — 沒問題
 *    - 但若有「使用者改自己的 displayName」之類的 update，AuditorAware 會去查
 *      SecurityContext，又繞回 AppUser 載入... 不是死循環，但 audit 路徑亂。
 *  最乾淨的做法：app_user 不掛 listener，schema 留 created_at/updated_at 欄位，
 *  在 @PrePersist / @PreUpdate hook 裡手動 set。本檔案在底部實作。
 *
 *  ── @ElementCollection<AppRole> 為什麼用 Set 不用 List？─────────────────
 *  Set 才能表達「同一 user 同一 role 不重複」的數學語意。
 *  V6 migration 的 PK = (user_id, role) 跟這個對齊 — 重複 INSERT 會被 PK 擋下。
 *  List 的話 Hibernate 預設會多開 order_index 欄位，沒必要。
 *
 *  ── @ElementCollection 與 @OneToMany 的差別 (面試題 / 中級) ─────────────
 *    @ElementCollection      : 子端是「值物件 (value object)」，沒有自己的 identity，
 *                              不可獨立查詢。生命週期完全綁主 entity。
 *                              用例：User → 多個 phone number / role enum
 *    @OneToMany              : 子端是「實體 (entity)」，自己有 PK，可以獨立查詢/維護。
 *                              用例：Policy → 多個 Beneficiary (Beneficiary 自己有 id)
 *
 *  我們的 role 是 enum 字串，沒有 identity，用 @ElementCollection 最合適。
 *  Spring 文件原話 "use @ElementCollection for collections of basic types or embeddables"。
 *
 *  ── fetch = EAGER 的考量 ────────────────────────────────────────────
 *  一般原則「*ToMany 預設 LAZY」，但這裡刻意 EAGER：
 *    - 每次 loadUserByUsername 都需要 roles 才能組 GrantedAuthority
 *    - 一個 user 通常 1–3 個 role，資料量極小
 *    - LAZY 的話 DbUserDetailsService 必須在交易內把 roles 摸到，多一層心智負擔
 *  EAGER + 預期使用模式 (登入流程) → 不會有 N+1 問題
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString   // 排除規則放在 field 上 (見 passwordHash 上的 @ToString.Exclude)
public class AppUser {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "username", nullable = false, unique = true, length = 64, updatable = false)
    private String username;

    /**
     * BCrypt 雜湊。永遠不要把這個欄位序列化到 API response 或 log。
     * 名稱刻意叫 passwordHash 不叫 password，提醒所有 reader 「這已經雜湊過」。
     */
    @ToString.Exclude
    @Column(name = "password_hash", nullable = false, length = 72)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 角色 (Set<AppRole>)。
     *
     * @ElementCollection 對應的子表配置：
     *   @CollectionTable(name = "app_user_role")
     *     - 子表名稱
     *     - joinColumns = (user_id) 指向主表 PK
     *     - 跟 V6 migration 的 app_user_role 表完全對齊
     *
     *   @Enumerated(EnumType.STRING)
     *     - DB 存 enum 名稱字串 ('ADMIN' / 'UNDERWRITER' / 'CSR')
     *     - 不要用 ORDINAL — enum 順序變了 DB 資料就錯亂
     *     - 跟 CHECK constraint 對齊
     *
     *   @Column(name = "role")
     *     - 子表的欄位名
     *
     *   fetch = EAGER
     *     - 登入流程必載入，不留懸念
     */
    @ElementCollection(fetch = FetchType.EAGER, targetClass = AppRole.class)
    @CollectionTable(
            name = "app_user_role",
            joinColumns = @JoinColumn(name = "user_id", nullable = false)
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    @Builder.Default
    private Set<AppRole> roles = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * @PrePersist / @PreUpdate — JPA lifecycle callbacks。
     *
     *  比起 BaseEntity 的 AuditingEntityListener，這個寫法更輕量、無外部依賴：
     *    - @PrePersist：INSERT 之前呼叫，set createdAt + updatedAt
     *    - @PreUpdate ：UPDATE 之前呼叫，只 set updatedAt
     *
     *  (面試題 / 中級)：「JPA lifecycle callbacks 有哪些？」
     *    @PrePersist / @PostPersist
     *    @PreUpdate  / @PostUpdate
     *    @PreRemove  / @PostRemove
     *    @PostLoad （從 DB 讀回後）
     *
     *    常見坑：
     *      - callback 內不能呼叫其他 EntityManager 操作 (會死鎖)
     *      - @PostLoad 在 LAZY collection 還沒載入時觸發，存取 LAZY 欄位會炸
     */
    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
