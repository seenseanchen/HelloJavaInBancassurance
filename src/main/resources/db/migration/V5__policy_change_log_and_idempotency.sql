-- =====================================================================
-- V5__policy_change_log_and_idempotency.sql
-- M5 「保單變更」核心：稽核軌跡 + 冪等性。
--
-- 設計重點：
--   1. policy_change_log 是 append-only — 永遠只 INSERT，任何 UPDATE/DELETE 都
--      應該被視為「破壞稽核」。Application 端 (Repository) 不會開放 update/delete。
--      跟 M3 的 underwriting_case_event 同套思路，但記錄的東西不一樣：
--        - underwriting_case_event：from_status -> to_status (狀態變動)
--        - policy_change_log     ：before_snapshot / after_snapshot (內容快照)
--      因為「變更受益人」會動到 1..N 列子表，純 column 寫不下，存 JSON 最直接。
--
--   2. 用 PG 原生 JSONB (不是 TEXT)：
--        - 支援 GIN 索引，可以對 before_snapshot->>'address' 之類做查詢
--        - PG 內部存壓縮 binary，比 TEXT 省空間
--        - JPA / Hibernate 對 JSONB 的支援要靠 hibernate-types 或 @JdbcTypeCode
--          (Hibernate 6+)。我們會在 Entity 用 @JdbcTypeCode(SqlTypes.JSON) 簡單接。
--
--   3. idempotency_record 三個關鍵欄位：
--        - request_hash：請求 body 的 SHA-256。同 key 但 body 不同 → 拒絕 (422)，
--          避免「Idempotency-Key 重用」攻擊
--        - response_status / response_body：把上次的回應原封不動存下來，replay 時直接吐
--        - expires_at：TTL，過期就 GC (本系統先不寫 GC job，但欄位先預留)
--
--   4. 為什麼 idempotency 不放 Redis？
--        - 教學專案：沒 Redis 也能跑 (一台 PG 就好)
--        - 實務：金融業多半「PG + Redis cache」雙層 — Redis hit 快，PG 是 source of truth
--          線上交易常見模式：Redis 存 key + PG 留稽核軌跡 (合規不能只存記憶體)
-- =====================================================================

-- =====================================================================
-- policy_change_log — 保單變更稽核軌跡 (append-only)
-- =====================================================================
CREATE TABLE policy_change_log (
    id              UUID            PRIMARY KEY,

    -- 1:N 的 N 端，指回 policy
    -- ON DELETE RESTRICT：保單就算軟刪 (is_deleted=true) 也保留變更歷史；
    -- 若有人試圖真刪保單 (DELETE FROM policy ...) DB 會拒絕，逼你先處理事件。
    policy_id       UUID            NOT NULL
                    REFERENCES policy(id)
                    ON DELETE RESTRICT,

    -- 變更類型 — 比 column 更具表達力，client 可以依此分流
    -- BENEFICIARIES / ADDRESS / PAYMENT_METHOD
    change_type     VARCHAR(32)     NOT NULL,

    -- before / after 快照：把當下相關欄位包成 JSON。
    -- 範例 (ADDRESS)：
    --   before_snapshot = {"billingAddress": "舊地址"}
    --   after_snapshot  = {"billingAddress": "新地址"}
    -- 範例 (BENEFICIARIES)：
    --   before_snapshot = {"beneficiaries": [{...}, {...}]}
    --   after_snapshot  = {"beneficiaries": [{...}]}
    --
    -- 為什麼不存「整張 policy」？太冗餘且暴露太多欄位給稽核 query — 只存「該次變更觸及的欄位」較精準。
    before_snapshot JSONB           NOT NULL,
    after_snapshot  JSONB           NOT NULL,

    -- 變更原因 (客戶填 / 業務員填) — 監管要求保留
    reason          TEXT,

    -- 誰做的 (login id)
    actor           VARCHAR(64)     NOT NULL,

    -- 變更生效的版本號 (after 的 version) — 對齊 @Version，方便還原 / 比對
    after_version   BIGINT          NOT NULL,

    -- 業務時間戳 — 跟稽核的 created_at 區分 (見 V2 同樣設計)
    occurred_at     TIMESTAMPTZ     NOT NULL,

    -- 稽核欄位
    created_at      TIMESTAMPTZ     NOT NULL,
    created_by      VARCHAR(64)     NOT NULL,
    updated_at      TIMESTAMPTZ     NOT NULL,
    updated_by      VARCHAR(64)     NOT NULL
    -- 注意：append-only，不需要 version / is_deleted
);

-- 「給我這張保單的所有變更歷史，按時間排」最常見
CREATE INDEX idx_policy_change_log_policy_occurred_at
    ON policy_change_log (policy_id, occurred_at DESC);

-- 「看某業務員今天做了哪些變更」
CREATE INDEX idx_policy_change_log_actor_occurred_at
    ON policy_change_log (actor, occurred_at DESC);

-- 「依變更型別撈報表」(每月地址變更件數 / 受益人變更件數)
CREATE INDEX idx_policy_change_log_change_type_occurred_at
    ON policy_change_log (change_type, occurred_at DESC);

COMMENT ON TABLE  policy_change_log                IS '保單變更稽核軌跡 (append-only)';
COMMENT ON COLUMN policy_change_log.change_type    IS 'BENEFICIARIES/ADDRESS/PAYMENT_METHOD';
COMMENT ON COLUMN policy_change_log.before_snapshot IS '變更前欄位快照 (JSONB)';
COMMENT ON COLUMN policy_change_log.after_snapshot  IS '變更後欄位快照 (JSONB)';
COMMENT ON COLUMN policy_change_log.after_version   IS 'after 對應 policy.version；M5 變更後 +1';


-- =====================================================================
-- idempotency_record — 冪等性鍵
-- =====================================================================
-- 使用情境：client 在 PATCH 帶 header `Idempotency-Key: <uuid>`。
--
-- Service 層處理流程：
--   1. 取出 key + 當下的 method+path+request_hash
--   2. 查表：
--      a) 沒有 → 正常執行，把回應 (status + body) 寫進表，commit
--      b) 同 key 同 hash → 直接 replay 上次的 response (不重跑業務邏輯)
--      c) 同 key 不同 hash → 拒絕 (422 IDEMPOTENCY_KEY_REUSED)
--
-- 為什麼 endpoint 要存？
--   同個 key 在不同 endpoint 用，幾乎一定是 client bug；存下來才能精準錯誤回報。
-- =====================================================================
CREATE TABLE idempotency_record (
    -- 用 idempotency_key 當 PK，UNIQUE 約束自然成立
    idempotency_key VARCHAR(128)    PRIMARY KEY,

    -- HTTP method + path：例如 "PATCH /api/policies/{id}/beneficiaries"
    -- (path 用 template，不寫死 {id} 值，避免 key 跟 path 高度耦合)
    endpoint        VARCHAR(255)    NOT NULL,

    -- request body 的 SHA-256 (hex 字串長度 = 64)
    -- 用來偵測「同 key 但內容變了」這種 client 誤用
    request_hash    VARCHAR(64)     NOT NULL,

    -- 上次的 HTTP status code (例如 200)
    response_status INTEGER         NOT NULL,

    -- 上次的回應 body (JSON 字串)。replay 時整包吐回去。
    response_body   TEXT            NOT NULL,

    -- 過期時間 — 之後 GC job 用。M5 不寫 GC，只是先預留。
    -- 慣例：24h - 7d，看業務性質。金融變更類我們用 24h。
    expires_at      TIMESTAMPTZ     NOT NULL,

    -- 稽核欄位 (只有 created_*；冪等紀錄理論上不會被 update — 一次寫死)
    created_at      TIMESTAMPTZ     NOT NULL,
    created_by      VARCHAR(64)     NOT NULL
);

-- GC job 用：依 expires_at 找過期記錄
CREATE INDEX idx_idempotency_record_expires_at
    ON idempotency_record (expires_at);

COMMENT ON TABLE  idempotency_record                IS 'Idempotency-Key 冪等性紀錄 (PATCH/POST 重送防呆)';
COMMENT ON COLUMN idempotency_record.idempotency_key IS '由 client 在 header Idempotency-Key 提供';
COMMENT ON COLUMN idempotency_record.endpoint       IS 'method + path template，例如 "PATCH /api/policies/{id}/beneficiaries"';
COMMENT ON COLUMN idempotency_record.request_hash   IS 'SHA-256 hex 字串 — 用於偵測「同 key 不同 body」濫用';
COMMENT ON COLUMN idempotency_record.expires_at     IS 'TTL：過期後可由 GC job 清除 (本階段不實作 GC)';
