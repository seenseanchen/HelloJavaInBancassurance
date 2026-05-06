-- =====================================================================
-- V2__underwriting_case_event.sql
-- 核保案件事件日誌 (Audit Log of state transitions)。
--
-- 設計重點：
--   1. append-only：永遠只 INSERT，不 UPDATE/DELETE，這樣稽核軌跡才可信。
--      Application 端會嚴格守住這條規則 (Repository 不開放 update / delete)。
--   2. 跟 underwriting_case 是 1:N。每張案件會有多筆事件。
--   3. 不用外鍵的稽核 / 法遵替代寫法：有些公司會把事件表搬到獨立 schema 甚至獨立 DB
--      做不可篡改 (immutable) 儲存。我們先用單表單 schema + FK 做最樸實的版本。
-- =====================================================================

CREATE TABLE underwriting_case_event (
    -- 事件本身的 PK
    id              UUID            PRIMARY KEY,

    -- 對應的案件 id (1:N 的 N 端)
    case_id         UUID            NOT NULL
                    REFERENCES underwriting_case(id)
                    ON DELETE RESTRICT,    -- 案件被刪 (即使軟刪除) 也保留事件

    -- 事件型別 — 比 status 更具表達力
    -- CASE_SUBMITTED / CASE_CLAIMED / INFO_REQUESTED /
    -- CASE_RESUBMITTED / CASE_APPROVED / CASE_REJECTED / CASE_WITHDRAWN
    action          VARCHAR(32)     NOT NULL,

    -- 狀態快照：from -> to
    -- 「from」可為 NULL：例如「建立案件」(SUBMITTED) 沒有 from。
    from_status     VARCHAR(20),
    to_status       VARCHAR(20)     NOT NULL,

    -- 誰做的 (login id)
    actor           VARCHAR(64)     NOT NULL,

    -- 事件附帶說明 (退件原因 / 補件清單 / 撤件理由)
    comment         TEXT,

    -- 事件發生時間 (UTC)。為什麼不直接用 created_at？
    -- → 業務語意上 occurred_at 跟稽核欄位 created_at 雖然這裡同值，
    --   但分開命名讓未來「事件補登」(發生在過去、現在才寫進來) 有空間。
    occurred_at     TIMESTAMPTZ     NOT NULL,

    -- 稽核欄位 (跟主表同一套)
    created_at      TIMESTAMPTZ     NOT NULL,
    created_by      VARCHAR(64)     NOT NULL,
    updated_at      TIMESTAMPTZ     NOT NULL,
    updated_by      VARCHAR(64)     NOT NULL
    -- 注意：本表故意不加 version / is_deleted —— append-only 不需要樂觀鎖也不軟刪。
);

-- 「給我這張案件的完整歷史，按時間排」是最常見的查詢
CREATE INDEX idx_uw_case_event_case_id_occurred_at
    ON underwriting_case_event (case_id, occurred_at);

-- 「看某個核保員今天做了哪些事」
CREATE INDEX idx_uw_case_event_actor_occurred_at
    ON underwriting_case_event (actor, occurred_at DESC);

COMMENT ON TABLE  underwriting_case_event              IS '核保案件狀態變更稽核軌跡 (append-only)';
COMMENT ON COLUMN underwriting_case_event.action       IS '業務動作: CASE_SUBMITTED/CASE_CLAIMED/INFO_REQUESTED/CASE_RESUBMITTED/CASE_APPROVED/CASE_REJECTED/CASE_WITHDRAWN';
COMMENT ON COLUMN underwriting_case_event.from_status  IS '轉移前狀態，建立事件時為 NULL';
COMMENT ON COLUMN underwriting_case_event.to_status    IS '轉移後狀態 (NOT NULL)';

-- 補一個歷史事件：把現有 (M2 之後) 已存在的案件也補一筆 CASE_SUBMITTED
-- 這樣 V2 之後查歷史不會缺第一筆。
-- 注意：這是「資料修補」的典型 Flyway 用法 — DDL + DML 一起跑。
INSERT INTO underwriting_case_event (
    id, case_id, action, from_status, to_status, actor, comment,
    occurred_at, created_at, created_by, updated_at, updated_by
)
SELECT
    gen_random_uuid(),       -- pgcrypto 內建，PG 13+ 都有
    c.id,
    'CASE_SUBMITTED',
    NULL,
    c.status,
    c.submitted_by,
    'Backfilled by V2 migration',
    c.submitted_at,
    NOW(), 'system',
    NOW(), 'system'
FROM underwriting_case c;
