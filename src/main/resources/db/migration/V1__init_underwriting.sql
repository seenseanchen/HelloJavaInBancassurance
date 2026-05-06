-- =====================================================================
-- V1__init_underwriting.sql
-- 建立「人壽審查契約 (Underwriting)」核心表。
--
-- Flyway 規則（必背）：
--   * 檔名格式：V{version}__{description}.sql
--   * 一旦正式環境跑過，這支檔案的內容就「不可再修改」，
--     後續變更要新開 V2__xxx.sql，否則 Flyway 啟動時 checksum 比對會失敗。
--   * 命名用 snake_case，PostgreSQL 預設會把所有未引號的識別字轉小寫。
-- =====================================================================

-- PostgreSQL 內建 gen_random_uuid()(v4)，但我們希望用「時間排序友善」的 UUID v7。
-- PG 17 之前沒有原生 v7，這裡先讓 UUID 由 Application 端 (Java) 產生，
-- DB 只負責存 uuid 型態。
-- (面試題 / 中級)：UUID v4 vs v7 差在哪？答：v7 前 48 bit 是毫秒時間戳，
-- 索引 (B-tree) 寫入時不會像 v4 一樣到處亂插，避免 page split 與快取失效。

CREATE TABLE underwriting_case (
    -- 主鍵：UUID 字串。對外不洩漏序號，避免商業機密。
    id                  UUID            PRIMARY KEY,

    -- 業務面向的對外編號 (e.g. UW-20260506-0001)，客服 / 業務看的就是這個。
    -- 唯一索引 + NOT NULL，避免重號。
    case_number         VARCHAR(32)     NOT NULL UNIQUE,

    -- 要保人 / 被保人姓名 (簡化：本系統暫不切要保人 vs 被保人)
    applicant_name      VARCHAR(64)     NOT NULL,

    -- 身分證字號 / 護照號。注意：實務上要加密欄位 + 遮罩，這裡為了學習先存明文。
    applicant_id_number VARCHAR(32)     NOT NULL,

    -- 商品代號 (對應商品主檔)。M10 才會做商品上下架，這裡先當外鍵字串引用。
    product_code        VARCHAR(32)     NOT NULL,

    -- 投保金額 (保額)：numeric(18,2) 對應 Java BigDecimal，禁用 float / double。
    coverage_amount     NUMERIC(18, 2)  NOT NULL CHECK (coverage_amount > 0),

    -- 保費 (年繳)：同樣禁用浮點。
    premium             NUMERIC(18, 2)  NOT NULL CHECK (premium > 0),

    -- 通路：BANCASSURANCE (銀保) / AGENT (業務員) / ONLINE (網路投保)
    channel             VARCHAR(20)     NOT NULL,

    -- 狀態：SUBMITTED / UNDER_REVIEW / PENDING_INFO / APPROVED / REJECTED / WITHDRAWN
    -- 用 VARCHAR 而非 enum 型態：DB 端 enum 改起來麻煩（要 ALTER TYPE），
    -- 字串 + Application 端管控反而更好維運。
    status              VARCHAR(20)     NOT NULL,

    -- 送件人 / 核保人 (login id)
    submitted_by        VARCHAR(64)     NOT NULL,
    reviewed_by         VARCHAR(64),

    -- 核保意見：可能很長，TEXT 型態 (PG 上 TEXT 跟 VARCHAR 沒效能差)
    review_comment      TEXT,

    -- 業務時間欄位
    submitted_at        TIMESTAMPTZ     NOT NULL,
    reviewed_at         TIMESTAMPTZ,

    -- ===== 稽核欄位（金融業必備）=====
    -- timestamptz：以 UTC 儲存，但帶時區資訊。比 timestamp 安全。
    created_at          TIMESTAMPTZ     NOT NULL,
    created_by          VARCHAR(64)     NOT NULL,
    updated_at          TIMESTAMPTZ     NOT NULL,
    updated_by          VARCHAR(64)     NOT NULL,

    -- 軟刪除 flag（is_deleted）；資料留在表裡，等定期歸檔
    is_deleted          BOOLEAN         NOT NULL DEFAULT FALSE,

    -- 樂觀鎖版本號 (對應 JPA @Version)，M5 保單變更會大量用到，先建好欄位
    version             BIGINT          NOT NULL DEFAULT 0
);

-- 常用查詢索引：
--   1) 用 case_number 查單筆 (已是 UNIQUE，自動有索引)
--   2) 列表頁通常按狀態 + 送件時間排序
CREATE INDEX idx_underwriting_case_status_submitted_at
    ON underwriting_case (status, submitted_at DESC);

-- 業務員 / 核保員看「我的案件」的查詢
CREATE INDEX idx_underwriting_case_submitted_by
    ON underwriting_case (submitted_by);

CREATE INDEX idx_underwriting_case_reviewed_by
    ON underwriting_case (reviewed_by)
    WHERE reviewed_by IS NOT NULL;  -- partial index：reviewed_by 多半 NULL，不浪費空間

-- 表 / 欄位註解：方便 DBA 與後人維護。
COMMENT ON TABLE  underwriting_case                     IS '人壽核保案件主表';
COMMENT ON COLUMN underwriting_case.case_number         IS '對外案件編號，例如 UW-20260506-0001';
COMMENT ON COLUMN underwriting_case.status              IS 'SUBMITTED/UNDER_REVIEW/PENDING_INFO/APPROVED/REJECTED/WITHDRAWN';
COMMENT ON COLUMN underwriting_case.coverage_amount     IS '保額 (新台幣)，BigDecimal';
COMMENT ON COLUMN underwriting_case.premium             IS '保費 (年繳, 新台幣)，BigDecimal';
COMMENT ON COLUMN underwriting_case.version             IS '樂觀鎖版本號，由 JPA @Version 管理';
