# M17/M18 Smoke Test — 核保案件管理頁 + 保單查詢與變更頁

完成於 2026-05-13。本文件涵蓋：
- M17：核保案件清單 / 新增 / 詳情 / 狀態流轉 / 事件歷程
- M18：保單查詢 / 詳情 / 地址變更 / 受益人變更 / 繳費方式變更 / 變更歷程
- 前後端整合重點：`If-Match`、`Idempotency-Key`、`409/412/422` UI 行為

---

## §0 變數約定

| 變數 | 意義 | 範例值 |
|---|---|---|
| `{{frontend_host}}` | 前端 dev server URL | `http://127.0.0.1:5173` |
| `{{api_host}}` | 後端 API base URL | `http://localhost:8080` |
| `{{policy_id}}` | M4 種子 IN_FORCE 保單 UUID | `11111111-1111-1111-1111-111111111111` |
| `{{policy_no}}` | 對應保單號 | `BANK-LIFE-20260507-0001` |

```bash
export frontend_host="http://127.0.0.1:5173"
export api_host="http://localhost:8080"
export policy_id="11111111-1111-1111-1111-111111111111"
export policy_no="BANK-LIFE-20260507-0001"
```

---

## §1 環境前置

```bash
# 1) 啟後端（含 security + seed data）
./mvnw spring-boot:run

# 2) 啟前端
cd frontend
pnpm install
pnpm dev --host 127.0.0.1 --port 5173
```

預期：
- 可開啟 `{{frontend_host}}/login`
- 未登入打 `{{frontend_host}}/underwriting/cases` 會被導回 `/login`
- 請務必用後端帳號登入（mock 模式無法跑 M17/M18 API）

---

## §2 帳號與角色矩陣

| 帳號 | 密碼 | 角色 | 預期 |
|---|---|---|---|
| `underwriter01` | `uw123` | UNDERWRITER | 可做核保流轉；不可送件、不可保單變更 |
| `csr01` | `csr123` | CSR | 可送件、可保單變更；不可核准/退件 |
| `admin` | `admin123` | ADMIN | 全功能可操作 |

---

## §3 M17 — 核保案件管理頁

### §3.1 CSR 送件（`/underwriting/cases/new`）

1. 以 `csr01 / csr123` 登入
2. 進入 `{{frontend_host}}/underwriting/cases/new`
3. 填表送出（姓名、身分證、商品代碼、保額、保費、通路）

預期：
- 成功提示「送件成功，已建立核保案件」
- 自動導到 `/underwriting/cases/:id`
- 新案件狀態為 `SUBMITTED`

### §3.2 UNDERWRITER 清單/篩選/詳情（`/underwriting/cases`）

1. 改用 `underwriter01 / uw123` 登入
2. 進入 `{{frontend_host}}/underwriting/cases`
3. 使用狀態篩選（例如只看 `SUBMITTED`）
4. 點任一筆「查看詳情」

預期：
- 清單顯示分頁、狀態 badge、送件資訊
- 詳情頁可看到由後端回傳 `nextStates` 對應出的操作按鈕

### §3.3 動態狀態流轉

1. 在 `SUBMITTED` 案件按「領件」
2. 案件進入 `UNDER_REVIEW`
3. 按鈕組改變為 `核准/退件/要求補件`（不應再有「領件」）
4. 點「查看歷程」進入 `/underwriting/cases/:id/events`

預期：
- 流轉成功會出 success 訊息
- timeline 有對應事件（例如 `CASE_CLAIMED`）

### §3.4 409 衝突 UI（雙分頁重現）

1. 用同一帳號開兩個分頁，停在同一案件詳情（同為 `SUBMITTED`）
2. 分頁 A 先按「領件」成功
3. 分頁 B 不刷新，直接按「領件」

預期：
- 分頁 B 出現「狀態衝突」dialog（來自後端 409）
- 內容包含「狀態已被他人變更，請重新讀取後再嘗試」或後端訊息
- 點「重新讀取」後，按鈕組更新成最新狀態可用操作

### §3.5 權限反向

1. `underwriter01` 進 `/underwriting/cases/new`

預期：
- 畫面顯示「沒有送件權限（僅 CSR / ADMIN）」提示
- 送件按鈕 disabled

---

## §4 M18 — 保單查詢與變更頁

### §4.1 查詢與詳情（`/policies`、`/policies/:id`）

1. 以 `underwriter01 / uw123` 登入
2. 進入 `{{frontend_host}}/policies`
3. 用保單號 `{{policy_no}}` 查詢
4. 進入保單詳情頁

預期：
- 可看到保單基本資料、受益人、版本 `vN`
- 詳情頁顯示 ETag（供變更流程使用）
- 以 UNDERWRITER 身分，「變更地址/受益人/繳費方式」按鈕 disabled

### §4.2 CSR 地址變更（`/policies/:id/change/address`）

1. 改用 `csr01 / csr123` 登入
2. 打開 `{{frontend_host}}/policies/{{policy_id}}/change/address`
3. 改地址後送出

預期：
- 成功提示「地址變更成功」
- `expectedVersion` 會跟著更新（版本 +1）
- 回詳情頁可看到 `billingAddress` 已更新

### §4.3 CSR 受益人變更（`/policies/:id/change/beneficiaries`）

1. 進入受益人變更頁
2. 先故意把比例調成不等於 100（例如 60 + 30）
3. 送出

預期：
- 前端即時擋下，顯示「受益人比例加總需等於 100」

再測正向：
1. 將比例改為 100（例如 60 + 40）
2. 確認至少一位 `priority=1`
3. 補上完整身分證號後送出

可用種子資料：
- 王太太：`B223344556`
- 王大寶：`A187654321`

預期：
- 成功提示「受益人變更成功」
- 版本 +1

### §4.4 CSR 繳費方式變更（`/policies/:id/change/payment-method`）

1. 進入繳費方式變更頁
2. 選 `MONTHLY / QUARTERLY / SEMI_ANNUAL / ANNUAL` 任一項送出

預期：
- 成功提示「繳費方式變更成功」
- 版本 +1，詳情頁顯示新繳費方式

### §4.5 保單變更歷程（`/policies/:id/changes`）

1. 進入 `{{frontend_host}}/policies/{{policy_id}}/changes`

預期：
- timeline 依時間降冪
- 可看到 `ADDRESS / BENEFICIARIES / PAYMENT_METHOD` 記錄
- 每筆含 `beforeSnapshot` / `afterSnapshot` / `actor` / `afterVersion`

### §4.6 409/412 衝突 UI（雙分頁重現）

1. CSR 開兩個分頁停在同一保單「地址變更」頁
2. 分頁 A 先送一次成功
3. 分頁 B 不刷新直接送出舊版本資料

預期：
- 分頁 B 出現「資料版本衝突」dialog（後端 409 或 412）
- 點「重新讀取」後，`expectedVersion` 更新為最新

### §4.7 422 業務規則錯誤

1. 在繳費方式頁嘗試送 `SINGLE_PAY`（可用 API 工具直接打）
2. 或在受益人頁送不合法組合（比例非 100）

預期：
- UI 顯示後端 `422 BUSINESS_RULE_VIOLATION` 訊息

---

## §5 API 快速驗證（可選）

> 用來確認 412 是 `If-Match` 前置條件失敗，不是前端假訊息。

```bash
# 1) 拿 CSR token
CSR_TOKEN=$(curl -s -X POST "$api_host/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"csr01","password":"csr123"}' \
  | jq -r '.data.accessToken')

# 2) 先取 ETag
ETAG=$(curl -s -I -H "Authorization: Bearer $CSR_TOKEN" \
  "$api_host/api/policies/$policy_id" | grep -i "^ETag:" | awk '{print $2}' | tr -d '\r')
echo "$ETAG"

# 3) 第一次 PATCH（應 200）
curl -i -X PATCH "$api_host/api/policies/$policy_id/address" \
  -H "Authorization: Bearer $CSR_TOKEN" \
  -H "Content-Type: application/json" \
  -H "If-Match: $ETAG" \
  -H "Idempotency-Key: 11111111-2222-3333-4444-555555555555" \
  -d "{\"expectedVersion\":${ETAG//\"/},\"newAddress\":\"台北市大安區仁愛路四段100號\"}"

# 4) 用同一個舊 ETAG 再打一次（應 412）
curl -i -X PATCH "$api_host/api/policies/$policy_id/address" \
  -H "Authorization: Bearer $CSR_TOKEN" \
  -H "Content-Type: application/json" \
  -H "If-Match: $ETAG" \
  -H "Idempotency-Key: aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" \
  -d "{\"expectedVersion\":${ETAG//\"/},\"newAddress\":\"台北市中山區南京東路三段200號\"}"
```

預期：
- 第 3 步 `HTTP 200`
- 第 4 步 `HTTP 412 Precondition Failed`，`code=PRECONDITION_FAILED`

---

## §6 完成檢查單

### M17
- [x] CSR 可在 `/underwriting/cases/new` 成功送件
- [x] UNDERWRITER 可在 `/underwriting/cases` 篩選、看詳情、做流轉
- [x] 詳情頁按鈕組會依 `nextStates` 動態變更
- [x] 雙分頁可重現 409 衝突，且 dialog 可重新讀取
- [x] `/underwriting/cases/:id/events` 顯示完整事件時間軸

### M18
- [x] `/policies` 可查詢（保單號 + 狀態）
- [x] `/policies/:id` 顯示版本與 ETag
- [x] CSR 可成功做地址 / 受益人 / 繳費方式變更
- [x] 受益人比例非 100 會被前端先擋
- [X] 雙分頁可重現 409/412 衝突並可恢復
- [X] `/policies/:id/changes` 可看到 before/after 快照與版本軌跡
- [X] UNDERWRITER 無法執行保單變更（按鈕 disabled / 權限提示）
