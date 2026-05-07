# M5 — 本機驗證與冒煙測試 (保單變更：樂觀鎖 + 冪等性 + 稽核軌跡)

> M5 範圍：三支 PATCH (address / beneficiaries / payment-method) + 一支 GET /changes。
> 重點學習：**`@Transactional` propagation、`@Version` 樂觀鎖、`If-Match` (412) vs OptimisticLock (409)、Idempotency-Key 冪等性、`policy_change_log` JSONB 稽核**。

---

## 0. 變數約定 (placeholder convention)

下方 curl 範例使用以下 placeholder，**執行前請替換**：

| 變數 | 意義 | 範例值 |
|---|---|---|
| `{{host}}` | API base URL | `http://localhost:8080` |
| `{{id}}`   | URL path 上的識別子 — 對 PATCH/GET 都是 policy UUID | `11111111-1111-1111-1111-111111111111` |

執行方式：下方所有 curl 命令都用 `{{host}}` / `{{id}}` 佔位符。執行時可選擇：
- **shell 執行**：先設環境變數，然後用 sed 或其他工具替換；或直接手工替換值
- **Postman / API 工具**：建立環境變數 `host` = `http://localhost:8080` / `id` = `11111111-1111-1111-1111-111111111111`，工具會自動展開 `{{...}}`

範例（shell 方式）：

```bash
export host=http://localhost:8080
export id=11111111-1111-1111-1111-111111111111   # 王小明的 BANK-LIFE-20260507-0001

# 若要跑下方的 curl，可用 envsubst (macOS: brew install gettext)
# curl -s {{host}}/api/policies/{{id}} | jq
# 改為：
curl -s "$(envsubst <<< '{{host}}/api/policies/{{id}}' | sed "s/{{host}}/{{host}}/g; s/{{id}}/{{id}}/g")" | jq
```

或更簡單的方式 — **直接替換值**：
```bash
# 把下方 {{host}} → http://localhost:8080 / {{id}} → 11111111-1111-1111-1111-111111111111
curl -s http://localhost:8080/api/policies/11111111-1111-1111-1111-111111111111 | jq
```

---

## 1. 編譯與啟動

```bash
cd ~/Documents/Claude/Projects/HelloJavaInBancassurance
./mvnw -q -DskipTests compile
docker compose up -d
./mvnw spring-boot:run
```

啟動 log 應看到 Flyway 跑 V5：

```
Migrating schema "public" to version "5 - policy change log and idempotency"
```

驗證新表：

```sql
\d policy_change_log
\d idempotency_record
SELECT version, description, success, installed_on FROM flyway_schema_history;
```

---

## 2. 核心流程：完整 happy path

### 2.1 GET 拿到當前版本（同時拿 ETag）

```bash
# {{id}} = policy UUID
curl -i -s {{host}}/api/policies/{{id}} | head -30
```

預期回應 header 含：

```
HTTP/1.1 200
ETag: "0"
Content-Type: application/json
```

body 內 `"version": 0`。**這個 `"0"` 就是下一支 PATCH 要帶的 If-Match 值**。

### 2.2 變更通訊地址（最簡 PATCH）

```bash
curl -i -s -X PATCH {{host}}/api/policies/{{id}}/address \
  -H 'Content-Type: application/json' \
  -H 'If-Match: "0"' \
  -H 'X-Actor: alice@bank.com' \
  -H 'Idempotency-Key: 1c0b3a40-cccc-1111-aaaa-000000000001' \
  -d '{
    "expectedVersion": 0,
    "newAddress": "台北市大安區信義路四段8號5樓",
    "reason": "客戶搬家"
  }' | jq
```

預期：

- HTTP 200
- 回應 header `ETag: "1"` (版本 +1)
- body 的 `billingAddress` 是新地址、`version: 1`

DB 驗證：

```sql
-- policy 主表 version 從 0 變 1
SELECT policy_number, billing_address, version FROM policy WHERE id = '11111111-1111-1111-1111-111111111111';

-- change log 留下 1 筆 ADDRESS
SELECT change_type, before_snapshot->>'billingAddress' AS before_addr,
       after_snapshot->>'billingAddress' AS after_addr,
       reason, actor, after_version, occurred_at
FROM policy_change_log
WHERE policy_id = '11111111-1111-1111-1111-111111111111'
ORDER BY occurred_at DESC LIMIT 1;
```

### 2.3 變更受益人（M5 主菜：集合替換 + 業務規則）

```bash
# 換成「兒子 70%、女兒 30%」的新版本
curl -i -s -X PATCH {{host}}/api/policies/{{id}}/beneficiaries \
  -H 'Content-Type: application/json' \
  -H 'If-Match: "1"' \
  -H 'X-Actor: alice@bank.com' \
  -d '{
    "expectedVersion": 1,
    "beneficiaries": [
      {"name": "王大寶", "idNumber": "A187654321", "relationship": "CHILD",
       "allocationPercentage": 70.00, "priority": 1},
      {"name": "王小妹", "idNumber": "A287654321", "relationship": "CHILD",
       "allocationPercentage": 30.00, "priority": 1}
    ],
    "reason": "客戶離婚，移除原配偶"
  }' | jq
```

預期：

- HTTP 200，`version: 2`，`beneficiaries` 變兩個（王大寶、王小妹）
- 「王太太」從清單消失（被 orphanRemoval DELETE）

DB 驗證 — N+1 觀察：開 `spring.jpa.show-sql=true` 應看到順序大致：

```sql
-- (1) JOIN FETCH 撈 policy + beneficiaries
SELECT p.*, b.* FROM policy p LEFT JOIN policy_beneficiary b ON ... WHERE p.id=?

-- (2) DELETE 舊 beneficiaries (orphanRemoval)
DELETE FROM policy_beneficiary WHERE id=? AND version=?
DELETE FROM policy_beneficiary WHERE id=? AND version=?

-- (3) UPDATE policy with version check
UPDATE policy SET ..., version=? WHERE id=? AND version=?

-- (4) INSERT 新 beneficiaries (cascade=ALL)
INSERT INTO policy_beneficiary ...
INSERT INTO policy_beneficiary ...
```

```sql
-- 驗證受益人
SELECT name, allocation_percentage, priority, version
FROM policy_beneficiary
WHERE policy_id = '11111111-1111-1111-1111-111111111111' AND is_deleted = FALSE;
-- 預期 2 筆：王大寶 70 / 王小妹 30

-- change log JSONB 快照
SELECT change_type,
       jsonb_array_length(before_snapshot->'beneficiaries') AS before_count,
       jsonb_array_length(after_snapshot->'beneficiaries')  AS after_count,
       reason, after_version
FROM policy_change_log
WHERE policy_id = '11111111-1111-1111-1111-111111111111'
ORDER BY occurred_at DESC LIMIT 1;
-- before_count=2 (王太太+王大寶)，after_count=2 (王大寶+王小妹)
```

### 2.4 變更繳費方式（enum 變更）

```bash
# 目前 SINGLE_PAY → 改 ANNUAL (其實 SINGLE_PAY 已經繳完，這裡只是示範)
# 注意：seed 中王小明本來就是 SINGLE_PAY；M5 業務規則禁止 換成 SINGLE_PAY，但「從」沒禁
# 為了讓 demo 跑得通，先換到 MONTHLY 試試
curl -i -s -X PATCH {{host}}/api/policies/{{id}}/payment-method \
  -H 'Content-Type: application/json' \
  -H 'If-Match: "2"' \
  -H 'X-Actor: alice@bank.com' \
  -d '{
    "expectedVersion": 2,
    "newPaymentMethod": "MONTHLY",
    "reason": "客戶要求改月繳"
  }' | jq
```

預期：HTTP 200，`premiumPaymentMethod: "MONTHLY"`，`version: 3`。

### 2.5 GET 變更歷史

```bash
curl -s "{{host}}/api/policies/{{id}}/changes" | jq
```

預期：3 筆，按 `occurredAt desc` 由新到舊（PAYMENT_METHOD → BENEFICIARIES → ADDRESS）。每筆都有 `beforeSnapshot` / `afterSnapshot` JSONB。

---

## 3. 反向流程 — 三種錯誤情境

### 3.1 If-Match 比對失敗 → 412 Precondition Failed

模擬 client 拿了舊版本就送變更：

```bash
curl -i -s -X PATCH "{{host}}/api/policies/{{id}}/address" \
  -H 'Content-Type: application/json' \
  -H 'If-Match: "0"' \
  -d '{"expectedVersion": 0, "newAddress": "其他地址"}'
```

預期：

```
HTTP/1.1 412 Precondition Failed
{
  "status": 412, "error": "Precondition Failed",
  "code": "PRECONDITION_FAILED",
  "message": "Version mismatch: expected=0, actual=3. Reload the resource and retry.",
  ...
}
```

**重點**：If-Match 帶錯版本 → 412 是 RFC 7232 標準（不是 409）。

### 3.2 保單狀態不允許變更 → 409 INVALID_POLICY_STATE

對 LAPSED 保單 (Policy 3) 打變更：

```bash
# Policy 3：BANK-MEDI-20251201-0099，status=LAPSED
export id3=33333333-3333-3333-3333-333333333333
curl -i -s -X PATCH "{{host}}/api/policies/{{id}}3/address" \
  -H 'Content-Type: application/json' \
  -d '{"expectedVersion": 0, "newAddress": "新地址"}'
```

預期：

```
HTTP/1.1 409 Conflict
{
  "code": "INVALID_POLICY_STATE",
  "message": "Cannot change address: policy must be IN_FORCE, but current status is LAPSED",
  ...
}
```

### 3.3 受益人比例加總 ≠ 100 → 422

```bash
curl -i -s -X PATCH "{{host}}/api/policies/{{id}}/beneficiaries" \
  -H 'Content-Type: application/json' \
  -d '{
    "expectedVersion": 3,
    "beneficiaries": [
      {"name": "王大寶", "idNumber": "A187654321", "relationship": "CHILD",
       "allocationPercentage": 60.00, "priority": 1}
    ]
  }'
```

預期：

```
HTTP/1.1 422 Unprocessable Entity
{
  "code": "BUSINESS_RULE_VIOLATION",
  "message": "beneficiaries allocationPercentage must sum to 100.00, got 60.00",
  ...
}
```

### 3.4 沒有第一順位受益人 → 422

```bash
curl -i -s -X PATCH "{{host}}/api/policies/{{id}}/beneficiaries" \
  -H 'Content-Type: application/json' \
  -d '{
    "expectedVersion": 3,
    "beneficiaries": [
      {"name": "王大寶", "idNumber": "A187654321", "relationship": "CHILD",
       "allocationPercentage": 100.00, "priority": 2}
    ]
  }'
```

預期 422 + `"at least one beneficiary must have priority = 1 (primary)"`。

### 3.5 換成 SINGLE_PAY → 422

```bash
curl -i -s -X PATCH "{{host}}/api/policies/{{id}}/payment-method" \
  -H 'Content-Type: application/json' \
  -d '{"expectedVersion": 3, "newPaymentMethod": "SINGLE_PAY"}'
```

預期 422 + `"Cannot change payment method to SINGLE_PAY for an in-force policy"`。

### 3.6 Bean Validation 失敗 → 400

身分證格式不對（應為「1 字母 + 9 數字」）：

```bash
curl -i -s -X PATCH "{{host}}/api/policies/{{id}}/beneficiaries" \
  -H 'Content-Type: application/json' \
  -d '{
    "expectedVersion": 3,
    "beneficiaries": [
      {"name": "X", "idNumber": "999", "relationship": "CHILD",
       "allocationPercentage": 100.00, "priority": 1}
    ]
  }'
```

預期 400，details 含 `idNumber must match Taiwan ID pattern`。

---

## 4. 真正的併發樂觀鎖衝突 → 409 OPTIMISTIC_LOCK_CONFLICT

### 4.0 為什麼「兩支 curl 直接打」只會看到 412，而不是 409？

```
沒有 sleep 時的時序：

Thread 1: loadEntity(v=N) ─→ assertVersion ✓ ─→ saveAndFlush ─→ COMMIT (v=N+1)
Thread 2:                                                                      ─→ loadEntity(v=N+1) ─→ assertVersion ✗ ─→ 412

問題：Thread 2 是在 Thread 1 commit「之後」才開始執行。
     READ_COMMITTED 的快照已經看到 v=N+1，
     所以我們自己的 assertVersionMatches() 先攔下來，回 412。
     根本到不了 Hibernate 的 @Version WHERE 篩選那一層。
```

要重現真正的 409，需要兩個 thread **在同一個 READ_COMMITTED 快照視窗內都讀到舊版本**：

```
加上 sleep 後的時序：

Thread 1: loadEntity(v=N) → assertVersion ✓ → SLEEP 3s → saveAndFlush → COMMIT (v=N+1)
Thread 2: loadEntity(v=N) → assertVersion ✓ → SLEEP 3s → saveAndFlush
          ↑ Thread 1 尚未 commit，READ_COMMITTED 看到的還是 v=N       ↑ WHERE version=N → 0 rows → 409 !!
```

### 4.1 步驟一：開啟 demo sleep（重啟 server）

在 `src/main/resources/application.yml` 把 sleep 改為 3000 ms：

```yaml
app:
  demo:
    optimistic-lock-sleep-ms: 3000   # ← 從 0 改成 3000
```

存檔後重啟：

```bash
# Ctrl+C 停掉，再重啟
./mvnw spring-boot:run
```

server log 啟動時不會特別顯示 sleep 值，但等一下打 PATCH 時會看到：
```
⚠️  DEMO SLEEP 3000 ms — 樂觀鎖衝突示範用，PRODUCTION 請確認 optimistic-lock-sleep-ms=0
```

### 4.2 步驟二：先確認保單的當前版本

```bash
export host=http://localhost:8080
export id=11111111-1111-1111-1111-111111111111   # 王小明 BANK-LIFE-20260507-0001

# {{id}} = policy UUID，此處為王小明的保單 ID
curl -s "$host/api/policies/$id" | jq '.version'
# 假設當前 version = N（例如 3）
export VER=3   # ← 改成你看到的版本號
```

### 4.3 步驟三：兩個 terminal 同時送 PATCH（在 3 秒 sleep 視窗內）

**開兩個 terminal，在 1–2 秒內先後執行（不必同毫秒，3 秒 sleep 視窗很充裕）：**

```bash
# Terminal A
curl -i -s -X PATCH "$host/api/policies/$id/address" \
  -H 'Content-Type: application/json' \
  -d "{\"expectedVersion\": $VER, \"newAddress\": \"A 搶先改的地址\", \"reason\": \"demo A\"}"
```

```bash
# Terminal B（A 送出後 1 秒內執行即可）
curl -i -s -X PATCH "$host/api/policies/$id/address" \
  -H 'Content-Type: application/json' \
  -d "{\"expectedVersion\": $VER, \"newAddress\": \"B 後來改的地址\", \"reason\": \"demo B\"}"
```

或用單一 terminal 的 background job 自動化：

```bash
# 一行啟動兩個並行 curl（bash background job）
DATA_A='{"expectedVersion": 4, "newAddress": "A 改的地址"}'                                                                                                                                                                                           ░▒▓ ✔  15:18:16
DATA_B='{"expectedVersion": 4, "newAddress": "B 改的地址"}'
URL="http://localhost:8080/api/policies/11111111-1111-1111-1111-111111111111/address"

# 同時啟動
curl -i -s -X PATCH "$URL" -H 'Content-Type: application/json' -d "$DATA_A" & \
curl -i -s -X PATCH "$URL" -H 'Content-Type: application/json' -d "$DATA_B" & \
wait

```

### 4.4 預期結果

```
=== Response A ===
HTTP/1.1 200
  "version": 4          ← 成功，version +1

=== Response B ===
HTTP/1.1 409 Conflict
  "code": "OPTIMISTIC_LOCK_CONFLICT",
  "message": "The resource was modified by another transaction. Please reload and retry."
```

**Server log 看到的順序大致如下：**

```
[thread-A] ⚠️  DEMO SLEEP 3000 ms ...
[thread-B] ⚠️  DEMO SLEEP 3000 ms ...
            ← 兩個 thread 都在 sleep，都已經讀到 v=N
[thread-A] Hibernate: UPDATE policy SET billing_address=?, version=? WHERE id=? AND version=?
[thread-A] Policy address changed: policyId=..., version N -> N+1 ...
[thread-B] Hibernate: UPDATE policy SET billing_address=?, version=? WHERE id=? AND version=?
            ← WHERE version=N，但 A 已改成 N+1，0 rows affected
[thread-B] org.springframework.orm.ObjectOptimisticLockingFailureException: ...
```

### 4.5 測試完記得關掉 sleep！

```yaml
# application.yml — 恢復預設
app:
  demo:
    optimistic-lock-sleep-ms: 0   # ← 改回 0，重啟
```

**為什麼 412 ≠ 409？（面試關鍵點）**

| | 412 Precondition Failed | 409 OPTIMISTIC_LOCK_CONFLICT |
|---|---|---|
| Trigger | `assertVersionMatches()` — 我們自己的前置檢查 | Hibernate `saveAndFlush()` — DB 端 `WHERE version=N` 0 rows |
| 觸發時機 | 載入 entity 後立刻比對 | flush 到 DB 時才發現 |
| 意義 | Client 帶的版本「在進方法時」就已過期 | 兩個 client 都「以為自己是最新的」，但在寫入時有人搶先 |
| HTTP 標準 | RFC 7232 §4.2 — If-Match 失敗用 412 | 無明文標準，慣例用 409 Conflict |
| 維運意義 | Client cache 太舊，要 reload | 真實併發衝突，考慮悲觀鎖或 retry 策略 |

---

## 5. Idempotency-Key 冪等性

### 5.1 同 key 重送 → replay (不重跑業務邏輯)

```bash
# 第一次：正常執行
KEY=$(uuidgen)
curl -i -s -X PATCH "{{host}}/api/policies/{{id}}/address" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" \
  -d '{"expectedVersion": 4, "newAddress": "新北市三重區重新路五段609巷1號"}' | tail -5

# 第二次：同 key 同 body 再送一次 — 預期回覆「跟上次一模一樣」
curl -i -s -X PATCH "{{host}}/api/policies/{{id}}/address" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" \
  -d '{"expectedVersion": 4, "newAddress": "新北市三重區重新路五段609巷1號"}' | tail -5
```

兩次回應 `version` 應一樣（不會因為 replay 又 +1）；DB 端也只會看到 1 筆 change log + 1 筆 idempotency_record。

```sql
SELECT COUNT(*) FROM policy_change_log
WHERE actor = 'system' AND change_type = 'ADDRESS'
  AND occurred_at > NOW() - INTERVAL '5 minutes';
-- 應該只有 1
```

### 5.2 同 key 不同 body → 422 IDEMPOTENCY_KEY_REUSED

```bash
curl -i -s -X PATCH "{{host}}/api/policies/{{id}}/address" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" \
  -d '{"expectedVersion": 5, "newAddress": "故意改不同的地址"}' | tail -10
```

預期 422 + `"Idempotency-Key was used previously with a different request body"`。

### 5.3 同 key 不同 endpoint → 422

```bash
curl -i -s -X PATCH "{{host}}/api/policies/{{id}}/payment-method" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" \
  -d '{"expectedVersion": 5, "newPaymentMethod": "QUARTERLY"}'
```

預期 422 + `"Idempotency-Key was used on a different endpoint previously: PATCH /api/policies/{id}/address"`。

---

## 6. SQL 觀察 — change log JSONB 與索引

### 6.1 看完整變更歷史

```sql
SELECT change_type, after_version, actor, occurred_at,
       jsonb_pretty(before_snapshot) AS before,
       jsonb_pretty(after_snapshot)  AS after
FROM policy_change_log
WHERE policy_id = '11111111-1111-1111-1111-111111111111'
ORDER BY occurred_at DESC;
```

### 6.2 用 JSONB path 查特定欄位變更

```sql
-- 找曾經把地址改到「信義」開頭的變更
SELECT id, occurred_at, after_snapshot->>'billingAddress' AS new_addr
FROM policy_change_log
WHERE change_type = 'ADDRESS'
  AND after_snapshot->>'billingAddress' LIKE '台北市信義%';
```

### 6.3 索引使用驗證

```sql
EXPLAIN ANALYZE
SELECT * FROM policy_change_log
WHERE policy_id = '11111111-1111-1111-1111-111111111111'
ORDER BY occurred_at DESC LIMIT 20;
```

預期：`Index Scan using idx_policy_change_log_policy_occurred_at`。

---

## 7. 完成檢查單

- [ ] `./mvnw -q -DskipTests compile` 通過
- [ ] Flyway V5 跑成功 (`flyway_schema_history` 有第 5 筆)
- [ ] `policy_change_log` 與 `idempotency_record` 兩表存在
- [ ] GET /policies/{id} 回應含 `ETag: "0"` header
- [ ] PATCH /address 成功 → version 變 1，change log 多 1 筆 ADDRESS
- [ ] PATCH /beneficiaries 全量替換成功，舊受益人 (王太太) 從 `policy_beneficiary` 消失（軟刪可選；本系統真 DELETE 因為 orphanRemoval 沒看 is_deleted）
- [ ] If-Match 帶錯 → 412 PRECONDITION_FAILED
- [ ] 對 LAPSED 保單變更 → 409 INVALID_POLICY_STATE
- [ ] 受益人比例 ≠ 100 → 422 BUSINESS_RULE_VIOLATION
- [ ] 兩支 curl 同時 PATCH 同版本 → 一支 200、一支 409 OPTIMISTIC_LOCK_CONFLICT
- [ ] 同 Idempotency-Key 重送 → 第二次 replay (DB 仍只有 1 筆 change log)
- [ ] 同 key 不同 body → 422 IDEMPOTENCY_KEY_REUSED
- [ ] GET /policies/{id}/changes 回 3+ 筆，按時間倒序

---

## 8. 面試話術 (S-T-A-R)

### 8.1 「`@Transactional` 在同一個 class 內呼叫會生效嗎？」(中級高頻)

> S：M5 我寫了三個 PATCH 方法（address/beneficiaries/payment-method），每個都需要交易。
> T：要把「載入 policy → 業務檢查 → 寫變更 → 寫 audit log」綁成一個原子操作。
> A：每個對外 method 標 `@Transactional`，private helper 不標。理由是 Spring 的
>   `@Transactional` 靠 AOP proxy 實現 — 同一個 class 內 `this.helper()` 不過 proxy，
>   切面不生效。所以如果 helper 標 `propagation = REQUIRES_NEW` 反而失效。
>   要拆出獨立交易：(1) 把 helper 抽到另一個 bean (推薦)、(2) 注入 self proxy。
> R：M5 三個操作的 audit log 與主操作共生死，本來就是同一交易，所以不需要 self-injection。

### 8.2 「樂觀鎖跟悲觀鎖怎麼選？」(中級高頻)

> 樂觀鎖 (`@Version`)：不鎖、效能好、衝突時回 409 client 重試；衝突低的場景首選。
> 悲觀鎖 (`SELECT FOR UPDATE`)：鎖住 row、其他交易等；衝突高 / 跨多表複雜更新適用。
>
> 我在 M5 用樂觀鎖：銀保系統「兩個業務員同時改同一張保單」機率極低，client 拿
> 412/409 重試對 UX 影響小，且效能比悲觀鎖好。
> 我也在 PolicyRepository 寫了一個 `findByIdForUpdate` (PESSIMISTIC_WRITE) 作對比 —
> 真要做秒殺型庫存扣減，那才用悲觀鎖。

### 8.3 「`Propagation.REQUIRES_NEW` 跟 `REQUIRED` 差在哪？」(中級)

> `REQUIRED` (預設)：有交易就用、沒有就開。
> `REQUIRES_NEW`：永遠開新交易；外層交易暫停 (suspend)，內層 commit/rollback 不影響外層。
>
> 經典用例：寫 audit log。如果 audit 用 `REQUIRES_NEW`，主交易 rollback 時 log 仍保留 →
> 「我看到失敗，但日誌證明你嘗試過」的需求。
>
> M5 我**故意不用** `REQUIRES_NEW`：我希望主操作 rollback 時 log 也 rollback，避免
> 「DB 沒改但 log 留了一筆說改了」的稽核錯亂。

### 8.4 「If-Match 412 跟 OptimisticLock 409 為何要分？」(資深 ★)

> 兩者都是「版本衝突」，但 trigger 點不同：
>   - **412**：client 主動帶 `If-Match: "3"`，server 一查當下版本 = 4 → 立刻拒絕。
>     是「前置條件不成立」，client 從沒碰過 server 的狀態。
>     RFC 7232 §4.2 明確規定 If-Match 失敗用 412。
>   - **409**：兩個 client 都 If-Match: "3"、都通過前置檢查；
>     A 先 commit、B 在 flush 時被 `WHERE version=3` 抓到「沒有任何 row 符合」，
>     拋 OptimisticLockException → server 翻 409。
>
> 為什麼不一律 409？
>   1. HTTP 標準語意 — 中間層 (CDN / proxy / 監控儀表板) 看到 412 知道是「快取過期」、
>      看到 409 知道是「實際併發」，可以做不同處理
>   2. 維運層面：412 多 = client cache 太舊 → 提示 client 加 reload；
>     409 多 = 真實併發過高 → 考慮悲觀鎖或 sharding

### 8.5 「冪等性鍵怎麼處理？兩個 request 同 key 同時打進來會怎樣？」(資深)

> 我用 `idempotency_record` 表，PK = idempotency_key。流程：
>   1. service 先 SELECT — 沒命中就跑業務邏輯、最後 INSERT
>   2. 命中 → 比對 request_hash (SHA-256 of body)：相同 → replay 上次 response；
>      不同 → 422 IDEMPOTENCY_KEY_REUSED
>
> 併發場景：兩個 request 同時 SELECT 都沒命中、都進業務邏輯、都 INSERT。
> 一個會 PK conflict 拋例外、那邊 rollback；client 會看到 5xx，重試時就會 hit 第一個的
> 紀錄、走 replay → 最終語意正確。
>
> 為什麼不放 Redis？
>   教學專案：少一個依賴；正式系統：Redis cache + PG 持久化雙層 — Redis hit 快，
>   PG 留稽核 (合規不能只靠記憶體)。

### 8.6 「N+1、`orphanRemoval`、cascade=ALL 在 M5 怎麼配合？」(資深)

> `change beneficiaries` 用 `findByIdWithBeneficiaries` (JOIN FETCH) 把舊集合一次撈進來。
> 如果用普通 `findById`、LAZY 沒觸發 → `clear()` 在空集合上跑 → orphanRemoval 不發 DELETE
> → 舊受益人留下、新的也加進去 → DB 多出殘餘，bug 入庫。
>
> 我用 `clear() + addAll(...)` 而非 `setBeneficiaries(newList)`：
>   後者把整個 collection reference 換掉，Hibernate 失去對「原 PersistentBag」的追蹤，
>   orphanRemoval 不會跑。**「修改 collection 內容」與「替換 collection」是兩件事**。

### 8.7 「為何不用 `try/catch` 把 OptimisticLockException 吞掉？」(資深)

> JPA 的 OptimisticLockException 是「事實聲明：你預期的世界跟實際不符」。
> 吞掉等於告訴 client「我成功了」但其實你沒寫成功 — **silent data corruption**。
> 正確處理：傳遞給 GlobalExceptionHandler → 翻成 409 → client 知道要 reload + retry。
>
> 真要重試：在 service 「外層」加 retry (例如 Spring Retry @Retryable)，
> 而不是在 try/catch 內偷偷 retry — 後者會讓交易邊界錯亂。

---

## 9. 已知限制 / M6 預告

- 本階段 actor 從 `X-Actor` header 拿；M9 接 Spring Security 後改 SecurityContextHolder
- `idempotency_record` 沒寫 GC job；TTL 欄位先預留 (production 一條 cron 每小時清過期)
- 變更前後 ID 是否需在 audit log 中遮罩？目前完整保留（稽核 > 隱私）— 若公司政策要遮罩，
  改 `snapshotBeneficiaries()` 一處即可
- M6 會把 ApiError 加上 `traceId` (MDC) 與統一 `ApiResponse<T>` 包裝；目前是 M2 簡化版

下一步 → **M6 全域例外處理 + 統一回應格式**。
