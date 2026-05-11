-- =====================================================================
-- V6__init_security.sql
-- M9 安全 — 應用層使用者與角色。
--
-- 設計重點：
--   1. 「應用使用者」放在 application schema (跟業務資料同 DB)，是金融業常見做法。
--      替代方案是接 LDAP / AD / Keycloak — 銀行 in-house 系統很多用 LDAP，
--      但教學專案保持簡單，所有 user 寫在同一個 PG。M9 完成後若要改 LDAP，
--      只要實作另一個 UserDetailsService 替換 DbUserDetailsService 即可。
--
--   2. 表名前綴 app_：避免跟 PG 系統表 (information_schema.users 等) 撞名，
--      也避免跟未來可能的 customer / employee 業務表混淆。
--      app_user = 「能登入這個 application 的人」，跟「客戶 (customer)」是不同概念。
--
--   3. 角色拆獨立表 (app_user_role)，不是 enum column：
--      - 一個 user 可以有多個 role (admin 同時是 underwriter)
--      - 未來加新角色不必動 user 表 schema
--      - 適合「預先建表 / 後加角色」的擴充場景
--      - JPA 對應方式：@ElementCollection + @CollectionTable (見 AppUser entity)
--
--   4. 密碼用 BCrypt 雜湊 (Spring Security 內建 BCryptPasswordEncoder)：
--      - 雜湊長度固定 60 字元 ($2a$10$...)，DB 欄位寬度給 72 留 buffer
--      - 「strength=10」表示 2^10 次 round，OWASP 建議 10–12，效能在 ~100ms/次 左右
--      - 比 SHA-256 安全：BCrypt 內建 salt + 慢速函式 (對抗 GPU 暴力破解)
--      - DEV-ONLY：下方種子帳號的明碼是 admin123 / uw123 / csr123，
--        production 上線前必須輪換 (用 ALTER 或新 migration 重設)。
--
--   5. created_at / updated_at 沒用 BaseEntity 的 @MappedSuperclass：
--      app_user 不是業務 entity，登入系統時刻意「不」帶 audit listener，
--      避免 user 自己稽核自己造成循環 (例如 SecurityContext 還沒建立就觸發 @CreatedBy)。
--      schema 上仍保留欄位，啟動時手動 set。
-- =====================================================================

-- =====================================================================
-- app_user — 應用使用者
-- =====================================================================
CREATE TABLE app_user (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),

    -- 登入帳號 (case-sensitive)。實務上會討論「要不要 case-insensitive」，
    -- 我們選 case-sensitive 並在前端規範「一律小寫」— 比較好做 unique key。
    username        VARCHAR(64)     NOT NULL UNIQUE,

    -- BCrypt 雜湊。固定 60 字元 ($2a$10$ + 22 char salt + 31 char hash)，
    -- 給 72 留 buffer 以防未來換 algo (Argon2 雜湊更長)。
    password_hash   VARCHAR(72)     NOT NULL,

    -- 顯示名稱 (UI 用)。跟 username 分開，前者可改、後者不可改。
    display_name    VARCHAR(100)    NOT NULL,

    -- 帳號是否啟用 (鎖定 / 停用 用)
    -- Spring Security 的 UserDetails 介面會問四個 boolean (enabled / accountNonExpired /
    -- credentialsNonExpired / accountNonLocked)，銀行業通常合併成這一個 enabled，
    -- 細分情境用獨立 audit 表記錄原因。
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,

    -- 稽核欄位 (跟業務 entity 一致；但本表不掛 AuditingEntityListener — 見上方註解)
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE  app_user                 IS '應用使用者 (能登入後台的帳號)';
COMMENT ON COLUMN app_user.username        IS '登入帳號 — UNIQUE，case-sensitive';
COMMENT ON COLUMN app_user.password_hash   IS 'BCrypt 雜湊 ($2a$10$...)，明碼禁止寫入';
COMMENT ON COLUMN app_user.enabled         IS 'false 代表帳號被停用，不能登入';

-- =====================================================================
-- app_user_role — 使用者角色 (1:N)
-- =====================================================================
-- 為什麼用 PK = (user_id, role) 而不是獨立 id 欄位？
--   1. 「同一 user 同一 role 只能存一次」這個約束直接由 PK 表達，不用額外 unique constraint
--   2. 沒有獨立 id 表示「這列本身沒有 identity」，刪除時靠 (user_id, role) 識別
--   3. 跟 JPA 的 @ElementCollection 對得上 — Hibernate 會自動產 (user_id, role) 複合 PK
--
-- ON DELETE CASCADE：刪 user 時自動清角色 — 角色不能脫離 user 存在。
-- =====================================================================
CREATE TABLE app_user_role (
    user_id     UUID            NOT NULL
                REFERENCES app_user(id)
                ON DELETE CASCADE,

    -- 角色名稱。CHECK 約束限制只能是定義中的三個 — schema 級別防呆，
    -- 比 Java enum 多一層保險（避免有人直接 SQL INSERT 不合法的 role）。
    role        VARCHAR(32)     NOT NULL,

    PRIMARY KEY (user_id, role),
    CONSTRAINT chk_app_user_role_value
        CHECK (role IN ('ADMIN', 'UNDERWRITER', 'CSR'))
);

-- 「給我所有 ADMIN 是誰」這種查詢用 (admin 列表畫面)
CREATE INDEX idx_app_user_role_role ON app_user_role (role);

COMMENT ON TABLE  app_user_role IS '使用者角色 (1:N)';
COMMENT ON COLUMN app_user_role.role IS 'ADMIN / UNDERWRITER / CSR — 跟 Java AppRole enum 對齊';


-- =====================================================================
-- 種子資料 — 三個 DEV 帳號
-- =====================================================================
--   ⚠️  以下 BCrypt 雜湊對應的明碼僅供開發測試使用，PRODUCTION 上線前必須重設。
--
--       admin           / admin123   (擁有 ADMIN + UNDERWRITER + CSR 全部角色)
--       underwriter01   / uw123      (核保員，王核保)
--       csr01           / csr123     (客服 / 業務員，李客服)
--
--   為什麼三個帳號明碼不同？
--     避免「複製貼上一個帳號試到全部 endpoint 都通」的偷懶測試 —
--     每個角色有自己的密碼，逼學習者真的切換身份去測 RBAC。
--
--   為什麼 UUID 寫死成可辨識的 pattern (1111... / 2222... / 3333...)?
--     測試資料友善：log 一眼看出是誰，方便 join 其他表 debug。
--     production 用 gen_random_uuid() 隨機。
-- =====================================================================
INSERT INTO app_user (id, username, password_hash, display_name) VALUES
    ('11111111-1111-1111-1111-111111111111',
     'admin',
     '$2b$10$ns1YGuuAmrJXgih0srpq4OBjU19Anfr9jEMHYtl88dqrC6gqW.0Tu',  -- admin123
     '系統管理員'),

    ('22222222-2222-2222-2222-222222222222',
     'underwriter01',
     '$2b$10$8tshEhYA.JLq0OFFACn.B.sEZUuNKm2DE5khJUdC8lp29vW55s5fa',  -- uw123
     '王核保'),

    ('33333333-3333-3333-3333-333333333333',
     'csr01',
     '$2b$10$JMaoHMvAraLxsLLQYxpXQOlSl8Psqloz8ArJ7PBZiaMtIRp6u0fTy',  -- csr123
     '李客服');

INSERT INTO app_user_role (user_id, role) VALUES
    -- admin 全包：實務上「super admin」會集所有角色於一身，方便緊急處理
    ('11111111-1111-1111-1111-111111111111', 'ADMIN'),
    ('11111111-1111-1111-1111-111111111111', 'UNDERWRITER'),
    ('11111111-1111-1111-1111-111111111111', 'CSR'),

    -- 核保員：只能看 / 審 / 核准 / 退件，不能改保單 (職務區隔 SoD)
    ('22222222-2222-2222-2222-222222222222', 'UNDERWRITER'),

    -- 客服：送件、查保單、改保單；不能 approve / reject (避免自審)
    ('33333333-3333-3333-3333-333333333333', 'CSR');
