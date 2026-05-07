-- =====================================================================
-- V3__init_policy.sql
-- 建立「保單 (Policy)」與「受益人 (Beneficiary)」核心表 — M4 上半。
--
-- 設計重點：
--   1. 一張保單 (policy) 有 0..N 個受益人 (policy_beneficiary)，1:N 結構。
--   2. policy 已預埋 version (樂觀鎖) — M5 變更會用到，這次先建好欄位避免再 ALTER。
--   3. 列表查詢的常見組合 (status / 持有人身分證 / 商品代號) 都建索引；
--      銀保通路常被「依商品 + 月份」查業績 → 也建 (product_code, effective_date) 索引。
--   4. 金額一律 numeric(18,2)，禁用 float/double。
--   5. 受益人分配比例存 numeric(5,2) (最多 100.00)，加 CHECK 限制 0~100；
--      「全部受益人加總 = 100」這條規則放在 application 層 (DB 端難以表達)。
-- =====================================================================

CREATE TABLE policy (
    -- 內部主鍵：UUID v7 (M4 階段先用 v4，理由同 underwriting_case)。
    id                      UUID            PRIMARY KEY,

    -- 對外保單號：客服 / 業務員 / 客戶看的編號。
    -- 格式範例：BANK-LIFE-20260507-0001 (通路-險別-日期-序號)
    policy_number           VARCHAR(40)     NOT NULL UNIQUE,

    -- 商品代號 (對應未來 M10 的商品主檔)
    product_code            VARCHAR(32)     NOT NULL,

    -- 來源核保案件 id：保單必由一張通過的核保案件而來。
    -- 注意：這裡用 nullable，因為 M4 階段我們會直接塞種子資料，
    -- 後續 M5/M10 補上「核保通過自動建單」時再改成 NOT NULL。
    underwriting_case_id    UUID,

    -- ========== 要保人 (policy holder) ==========
    -- 簡化：本系統不切「人」資料表，姓名 / 身分證直接存欄位。
    -- 實務上會有獨立 customer / party 表 + customer_id FK。
    holder_name             VARCHAR(64)     NOT NULL,
    holder_id_number        VARCHAR(32)     NOT NULL,

    -- ========== 被保險人 (insured) ==========
    -- 多數人壽商品「要保人 = 被保險人」，但例如父母幫小孩投保就會不同。
    insured_name            VARCHAR(64)     NOT NULL,
    insured_id_number       VARCHAR(32)     NOT NULL,

    -- ========== 商品 / 金額 ==========
    coverage_amount         NUMERIC(18, 2)  NOT NULL CHECK (coverage_amount > 0),
    premium                 NUMERIC(18, 2)  NOT NULL CHECK (premium > 0),

    -- 繳費方式：MONTHLY / QUARTERLY / SEMI_ANNUAL / ANNUAL / SINGLE_PAY
    premium_payment_method  VARCHAR(20)     NOT NULL,

    -- ========== 通路 / 狀態 ==========
    -- 通路：BANCASSURANCE / AGENT / ONLINE — 跟核保表一致
    channel                 VARCHAR(20)     NOT NULL,

    -- 保單狀態：
    --   IN_FORCE  生效中
    --   LAPSED    停效 (繳費寬限期過了還沒繳)
    --   MATURED   滿期
    --   SURRENDERED 解約
    --   TERMINATED 終止 (其他原因，如理賠後)
    status                  VARCHAR(20)     NOT NULL,

    -- ========== 業務日期 ==========
    effective_date          DATE            NOT NULL,
    expiry_date             DATE,
    -- 同欄位 CHECK：到期日如果有，必須晚於生效日
    CONSTRAINT chk_policy_dates CHECK (expiry_date IS NULL OR expiry_date > effective_date),

    -- ========== 帳單 / 通訊地址 ==========
    -- 銀行業：地址變更是 M5 變更 API 最常見之一
    billing_address         VARCHAR(255)    NOT NULL,

    -- ========== 稽核欄位 (跟核保表相同套路) ==========
    created_at              TIMESTAMPTZ     NOT NULL,
    created_by              VARCHAR(64)     NOT NULL,
    updated_at              TIMESTAMPTZ     NOT NULL,
    updated_by              VARCHAR(64)     NOT NULL,

    is_deleted              BOOLEAN         NOT NULL DEFAULT FALSE,

    -- 樂觀鎖 — M5 變更會大量依賴
    version                 BIGINT          NOT NULL DEFAULT 0
);

-- 列表常見查詢索引：
--   1) 「我的保單」：依持有人身分證查
CREATE INDEX idx_policy_holder_id_number
    ON policy (holder_id_number);

--   2) 「客服查在保中」：依狀態 + 生效日 (列表預設排序)
CREATE INDEX idx_policy_status_effective_date
    ON policy (status, effective_date DESC);

--   3) 「業務員依商品看業績」：商品 + 生效日
CREATE INDEX idx_policy_product_code_effective_date
    ON policy (product_code, effective_date DESC);

COMMENT ON TABLE  policy                          IS '保單主表';
COMMENT ON COLUMN policy.policy_number            IS '對外保單號，例如 BANK-LIFE-20260507-0001';
COMMENT ON COLUMN policy.status                   IS 'IN_FORCE/LAPSED/MATURED/SURRENDERED/TERMINATED';
COMMENT ON COLUMN policy.premium_payment_method   IS 'MONTHLY/QUARTERLY/SEMI_ANNUAL/ANNUAL/SINGLE_PAY';
COMMENT ON COLUMN policy.coverage_amount          IS '保額 (新台幣)，BigDecimal';
COMMENT ON COLUMN policy.premium                  IS '保費 (新台幣)，BigDecimal';
COMMENT ON COLUMN policy.version                  IS '樂觀鎖版本號，由 JPA @Version 管理 (M5 用)';


-- =====================================================================
-- 受益人 (Beneficiary) — Policy 的 1:N 子表
-- =====================================================================
CREATE TABLE policy_beneficiary (
    id                      UUID            PRIMARY KEY,

    -- 1:N 的 N 端，外鍵指向 policy
    -- ON DELETE CASCADE：保單軟刪 / 真刪時，受益人也跟著清。
    -- 本系統用軟刪，這條 CASCADE 主要是給「測試環境真刪」一個保險。
    policy_id               UUID            NOT NULL
                            REFERENCES policy(id)
                            ON DELETE CASCADE,

    name                    VARCHAR(64)     NOT NULL,
    id_number               VARCHAR(32)     NOT NULL,

    -- 與要保人的關係：SPOUSE / CHILD / PARENT / SIBLING / OTHER
    relationship            VARCHAR(20)     NOT NULL,

    -- 受益比例：0.00 ~ 100.00。同保單下所有受益人加總 = 100.00 (應用層守)。
    allocation_percentage   NUMERIC(5, 2)   NOT NULL
                            CHECK (allocation_percentage > 0 AND allocation_percentage <= 100),

    -- 順位：1 = 第一順位、2 = 第二順位…
    -- 同順位內按 allocation_percentage 分配；不同順位是「上位先領完才輪下位」。
    priority                INTEGER         NOT NULL
                            CHECK (priority >= 1),

    -- 稽核
    created_at              TIMESTAMPTZ     NOT NULL,
    created_by              VARCHAR(64)     NOT NULL,
    updated_at              TIMESTAMPTZ     NOT NULL,
    updated_by              VARCHAR(64)     NOT NULL,

    is_deleted              BOOLEAN         NOT NULL DEFAULT FALSE,

    -- 樂觀鎖：受益人本身也可能單獨被改 (M5 重點)
    version                 BIGINT          NOT NULL DEFAULT 0
);

-- 列受益人時最常見：依保單 + 順位 + 比例排序
CREATE INDEX idx_policy_beneficiary_policy_priority
    ON policy_beneficiary (policy_id, priority, allocation_percentage DESC);

COMMENT ON TABLE  policy_beneficiary                       IS '保單受益人 (1:N 子表)';
COMMENT ON COLUMN policy_beneficiary.relationship          IS 'SPOUSE/CHILD/PARENT/SIBLING/OTHER';
COMMENT ON COLUMN policy_beneficiary.allocation_percentage IS '受益比例 0.00~100.00；同保單加總應為 100';
COMMENT ON COLUMN policy_beneficiary.priority              IS '受益順位 (1 = 第一順位)';
