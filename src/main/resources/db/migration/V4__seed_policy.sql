-- =====================================================================
-- V4__seed_policy.sql
-- M4 上半測試用種子資料：5 張保單 + 受益人組合。
--
-- 為什麼用 Flyway 而不是 data.sql？
--   * Flyway 有版本紀錄、可重現、跟程式版本綁在一起
--   * data.sql 每次啟動會重跑，種子重複時會違反 unique constraint
--   * 真正的測試環境會把這支標 -- placeholder 或用 Flyway placeholders 控管環境差異
--
-- 警告：production 環境不會跑這支 — 之後會在 application-prod.yml 加 spring.flyway.locations
--       排除 seed 子目錄，目前學習階段先寫進主目錄方便 demo。
-- =====================================================================

-- 為了讓 idempotent (重複跑也不會炸)，先確認沒有相同 policy_number 才塞。
-- 但 Flyway migration 本來就只跑一次，這裡只是保險 — 如果你 reset DB 再啟動沒問題。

-- ============================================================
-- Policy 1：王小明 — 銀保通路躉繳壽險，IN_FORCE，含 2 個受益人
-- ============================================================
INSERT INTO policy (
    id, policy_number, product_code, underwriting_case_id,
    holder_name, holder_id_number, insured_name, insured_id_number,
    coverage_amount, premium, premium_payment_method,
    channel, status, effective_date, expiry_date, billing_address,
    created_at, created_by, updated_at, updated_by, is_deleted, version
) VALUES (
    '11111111-1111-1111-1111-111111111111',
    'BANK-LIFE-20260507-0001',
    'LIFE-WHOLE-001',
    NULL,
    '王小明', 'A123456789',
    '王小明', 'A123456789',
    3000000.00, 850000.00, 'SINGLE_PAY',
    'BANCASSURANCE', 'IN_FORCE',
    DATE '2026-05-01', DATE '2056-05-01',
    '台北市信義區信義路五段7號',
    NOW(), 'system', NOW(), 'system', FALSE, 0
);

INSERT INTO policy_beneficiary (
    id, policy_id, name, id_number, relationship,
    allocation_percentage, priority,
    created_at, created_by, updated_at, updated_by, is_deleted, version
) VALUES
    ('11111111-1111-1111-1111-aaaaaaaaaaaa',
     '11111111-1111-1111-1111-111111111111',
     '王太太', 'B223344556', 'SPOUSE', 60.00, 1,
     NOW(), 'system', NOW(), 'system', FALSE, 0),
    ('11111111-1111-1111-1111-bbbbbbbbbbbb',
     '11111111-1111-1111-1111-111111111111',
     '王大寶', 'A187654321', 'CHILD', 40.00, 1,
     NOW(), 'system', NOW(), 'system', FALSE, 0);


-- ============================================================
-- Policy 2：李美華 — 業務員通路月繳投資型，IN_FORCE，1 個受益人
-- ============================================================
INSERT INTO policy (
    id, policy_number, product_code, underwriting_case_id,
    holder_name, holder_id_number, insured_name, insured_id_number,
    coverage_amount, premium, premium_payment_method,
    channel, status, effective_date, expiry_date, billing_address,
    created_at, created_by, updated_at, updated_by, is_deleted, version
) VALUES (
    '22222222-2222-2222-2222-222222222222',
    'AGENT-LIFE-20260108-0042',
    'LIFE-INVEST-002',
    NULL,
    '李美華', 'C234567890',
    '李美華', 'C234567890',
    1500000.00, 12000.00, 'MONTHLY',
    'AGENT', 'IN_FORCE',
    DATE '2026-01-15', NULL,
    '新北市板橋區文化路一段100號',
    NOW(), 'system', NOW(), 'system', FALSE, 0
);

INSERT INTO policy_beneficiary (
    id, policy_id, name, id_number, relationship,
    allocation_percentage, priority,
    created_at, created_by, updated_at, updated_by, is_deleted, version
) VALUES
    ('22222222-2222-2222-2222-aaaaaaaaaaaa',
     '22222222-2222-2222-2222-222222222222',
     '李爸爸', 'D345678901', 'PARENT', 100.00, 1,
     NOW(), 'system', NOW(), 'system', FALSE, 0);


-- ============================================================
-- Policy 3：陳大明 — 銀保通路年繳醫療，LAPSED (停效，沒繳費)
-- ============================================================
INSERT INTO policy (
    id, policy_number, product_code, underwriting_case_id,
    holder_name, holder_id_number, insured_name, insured_id_number,
    coverage_amount, premium, premium_payment_method,
    channel, status, effective_date, expiry_date, billing_address,
    created_at, created_by, updated_at, updated_by, is_deleted, version
) VALUES (
    '33333333-3333-3333-3333-333333333333',
    'BANK-MEDI-20251201-0099',
    'MEDI-BASIC-003',
    NULL,
    '陳大明', 'E456789012',
    '陳小寶', 'E498765432',
    500000.00, 24000.00, 'ANNUAL',
    'BANCASSURANCE', 'LAPSED',
    DATE '2025-12-01', NULL,
    '台中市西屯區台灣大道三段99號',
    NOW(), 'system', NOW(), 'system', FALSE, 0
);

-- 沒受益人 (醫療險不一定指定受益人，理賠回給被保險人)


-- ============================================================
-- Policy 4：陳大明 (同個人，第二張保單) — 線上躉繳，IN_FORCE
-- ============================================================
-- 故意做「同 holder 多張保單」，用來示範 holderIdNumber 過濾條件
INSERT INTO policy (
    id, policy_number, product_code, underwriting_case_id,
    holder_name, holder_id_number, insured_name, insured_id_number,
    coverage_amount, premium, premium_payment_method,
    channel, status, effective_date, expiry_date, billing_address,
    created_at, created_by, updated_at, updated_by, is_deleted, version
) VALUES (
    '44444444-4444-4444-4444-444444444444',
    'ONLINE-LIFE-20260301-0007',
    'LIFE-WHOLE-001',
    NULL,
    '陳大明', 'E456789012',
    '陳大明', 'E456789012',
    2000000.00, 600000.00, 'SINGLE_PAY',
    'ONLINE', 'IN_FORCE',
    DATE '2026-03-15', DATE '2046-03-15',
    '台中市西屯區台灣大道三段99號',
    NOW(), 'system', NOW(), 'system', FALSE, 0
);

INSERT INTO policy_beneficiary (
    id, policy_id, name, id_number, relationship,
    allocation_percentage, priority,
    created_at, created_by, updated_at, updated_by, is_deleted, version
) VALUES
    ('44444444-4444-4444-4444-aaaaaaaaaaaa',
     '44444444-4444-4444-4444-444444444444',
     '陳太太', 'F567890123', 'SPOUSE', 100.00, 1,
     NOW(), 'system', NOW(), 'system', FALSE, 0);


-- ============================================================
-- Policy 5：林志明 — 銀保通路年繳壽險，MATURED (滿期)
-- ============================================================
INSERT INTO policy (
    id, policy_number, product_code, underwriting_case_id,
    holder_name, holder_id_number, insured_name, insured_id_number,
    coverage_amount, premium, premium_payment_method,
    channel, status, effective_date, expiry_date, billing_address,
    created_at, created_by, updated_at, updated_by, is_deleted, version
) VALUES (
    '55555555-5555-5555-5555-555555555555',
    'BANK-LIFE-20060101-0001',
    'LIFE-TERM-004',
    NULL,
    '林志明', 'G678901234',
    '林志明', 'G678901234',
    1000000.00, 35000.00, 'ANNUAL',
    'BANCASSURANCE', 'MATURED',
    DATE '2006-01-01', DATE '2026-01-01',
    '高雄市苓雅區四維三路2號',
    NOW(), 'system', NOW(), 'system', FALSE, 0
);

INSERT INTO policy_beneficiary (
    id, policy_id, name, id_number, relationship,
    allocation_percentage, priority,
    created_at, created_by, updated_at, updated_by, is_deleted, version
) VALUES
    ('55555555-5555-5555-5555-aaaaaaaaaaaa',
     '55555555-5555-5555-5555-555555555555',
     '林媽媽', 'H789012345', 'PARENT', 50.00, 1,
     NOW(), 'system', NOW(), 'system', FALSE, 0),
    ('55555555-5555-5555-5555-bbbbbbbbbbbb',
     '55555555-5555-5555-5555-555555555555',
     '林弟弟', 'G612345678', 'SIBLING', 50.00, 2,  -- 第二順位
     NOW(), 'system', NOW(), 'system', FALSE, 0);
