# M3 — 本機驗證與冒煙測試 (狀態機 + 業務規則)

> M3 範圍：核保流程的狀態機、6 個業務動作 endpoint、稽核軌跡 (Audit Log)、
> 非法跳轉 → 409、樂觀鎖衝突 → 409。

---

## 1. 編譯與啟動

```bash
cd ~/Documents/Claude/Projects/HelloJavaInBancassurance
./mvnw -q -DskipTests compile
docker compose up -d
./mvnw spring-boot:run
```

啟動 log 應看到 Flyway 跑了 V2：

```
Migrating schema "public" to version "2 - underwriting case event"
```

驗證表已建立（IntelliJ Database tool 或 psql）：

```sql
\d underwriting_case_event
SELECT version, description, success, installed_on FROM flyway_schema_history;
```

V1 既有的案件會被 V2 backfill 寫入「CASE_SUBMITTED」事件：

```sql
SELECT case_id, action, from_status, to_status, comment
FROM underwriting_case_event
ORDER BY occurred_at;
```

---

## 2. 完整正向流程：送件 → 領件 → 補件 → 重送 → 核准

> 把每一步的回應 caseId 換進下一步的 URL 即可。建議把 caseId 存成 shell 變數：
> `CASE_ID=$(curl -s -X POST ... | jq -r '.id')`

### 2.1 送件 (建立)

```bash
CASE_ID=$(curl -s -X POST http://localhost:8080/api/underwriting/cases \
  -H 'Content-Type: application/json' \
  -d '{
    "applicantName": "陳大文",
    "applicantIdNumber": "B234567890",
    "productCode": "LIFE-WHOLE-65",
    "coverageAmount": 5000000,
    "premium": 32000,
    "channel": "BANCASSURANCE",
    "submittedBy": "teller02@bank"
  }' | jq -r '.id')
echo "CASE_ID=$CASE_ID"
```

期望：`status: SUBMITTED`，事件表多一筆 `CASE_SUBMITTED`。

### 2.2 核保員領件 (SUBMITTED → UNDER_REVIEW)

```bash
curl -s -X POST http://localhost:8080/api/underwriting/cases/$CASE_ID/claim \
  -H 'Content-Type: application/json' \
  -d '{"actor": "uw01@insurance"}' | jq
```

期望：`status: UNDER_REVIEW`、`reviewedBy: "uw01@insurance"`、`reviewedAt` 有值。

### 2.3 要求補件 (UNDER_REVIEW → PENDING_INFO)

```bash
curl -s -X POST http://localhost:8080/api/underwriting/cases/$CASE_ID/request-info \
  -H 'Content-Type: application/json' \
  -d '{"actor": "uw01@insurance", "comment": "請補三個月內健康檢查報告"}' | jq
```

期望：`status: PENDING_INFO`、`reviewComment` 有要補件清單。

### 2.4 業務員重送 (PENDING_INFO → UNDER_REVIEW)

```bash
curl -s -X POST http://localhost:8080/api/underwriting/cases/$CASE_ID/resubmit \
  -H 'Content-Type: application/json' \
  -d '{"actor": "teller02@bank", "comment": "已補上健檢報告"}' | jq
```

期望：`status: UNDER_REVIEW`、`reviewComment` 被清空為 null（避免下個核保員看到上輪訊息）。

### 2.5 核准 (UNDER_REVIEW → APPROVED)

```bash
curl -s -X POST http://localhost:8080/api/underwriting/cases/$CASE_ID/approve \
  -H 'Content-Type: application/json' \
  -d '{"actor": "uw01@insurance", "comment": "標準體承保"}' | jq
```

期望：`status: APPROVED` (terminal)。

### 2.6 看完整歷史軌跡

```bash
curl -s http://localhost:8080/api/underwriting/cases/$CASE_ID/events | jq
```

期望按 `occurredAt` 升冪 5 筆事件：
`CASE_SUBMITTED → CASE_CLAIMED → INFO_REQUESTED → CASE_RESUBMITTED → CASE_APPROVED`

---

## 3. 反向案例（這才是面試會深問的部分）

### 3.1 非法跳轉 → 409

對 2.5 已 APPROVED 的案件再叫 reject：

```bash
curl -i -X POST http://localhost:8080/api/underwriting/cases/$CASE_ID/reject \
  -H 'Content-Type: application/json' \
  -d '{"actor": "uw01@insurance", "comment": "改主意"}'
```

期望：`HTTP/1.1 409 Conflict`，body：

```json
{
  "status": 409,
  "code": "INVALID_STATE_TRANSITION",
  "message": "Illegal transition from APPROVED to REJECTED: transition not allowed by state machine",
  ...
}
```

### 3.2 退件未填 comment → 400

```bash
curl -i -X POST http://localhost:8080/api/underwriting/cases/$CASE_ID/reject \
  -H 'Content-Type: application/json' \
  -d '{"actor": "uw01"}'
```

期望：`400 Bad Request`，`code: VALIDATION_FAILED`，`details.comment: "must not be blank"`。

### 3.3 找不到案件 → 404

```bash
curl -i -X POST http://localhost:8080/api/underwriting/cases/00000000-0000-0000-0000-000000000000/claim \
  -H 'Content-Type: application/json' \
  -d '{"actor": "uw01"}'
```

期望：`404 Not Found`，`code: RESOURCE_NOT_FOUND`。

### 3.4 樂觀鎖衝突 → 409 (進階)

需要兩個 client 同時送 transition。最快的測法：

```bash
# 先建一張案件，然後同時開兩個視窗對它打 approve
NEW_ID=$(curl -s -X POST http://localhost:8080/api/underwriting/cases ...略... | jq -r '.id')
curl -X POST .../$NEW_ID/claim -d '{"actor":"uw01"}' -H 'Content-Type: application/json'

# 視窗 A 與視窗 B 幾乎同時:
curl -X POST .../$NEW_ID/approve -d '{"actor":"uw01"}' -H 'Content-Type: application/json'
curl -X POST .../$NEW_ID/reject  -d '{"actor":"uw02","comment":"x"}' -H 'Content-Type: application/json'
```

實務上 99% 機率仍會有一邊先成功；後到的視乎時序：
- 仍處 UNDER_REVIEW → 走非法跳轉路徑 (例如 APPROVED → REJECTED)
- @Version 比對失敗 → `OPTIMISTIC_LOCK_CONFLICT` 409

> 這個 case 用 curl 不好穩定重現，M8 整合測試會用兩條 thread 模擬。M5 保單變更會在
> 同一 endpoint 內示範 `If-Match: version` header，可以更精準觸發。

---

## 4. DB 端驗證 SQL

```sql
-- 4.1 案件主表現況
SELECT case_number, status, reviewed_by, reviewed_at, version
FROM underwriting_case
WHERE id = :CASE_ID;

-- 4.2 完整事件軌跡
SELECT occurred_at, action, from_status, to_status, actor, comment
FROM underwriting_case_event
WHERE case_id = :CASE_ID
ORDER BY occurred_at;

-- 4.3 確認 append-only：每個案件的事件 INSERT 順序與狀態合理
SELECT case_id, COUNT(*) AS events,
       MAX(to_status) FILTER (WHERE action = 'CASE_SUBMITTED') AS first_state,
       BOOL_OR(action = 'CASE_APPROVED' OR action = 'CASE_REJECTED' OR action = 'CASE_WITHDRAWN') AS reached_terminal
FROM underwriting_case_event
GROUP BY case_id;
```

---

## 5. M3 完成檢查單

- [x] V2 migration 啟動時自動跑
- [x] 完整流程：submit → claim → request-info → resubmit → approve 五步全綠
- [x] `/events` endpoint 回傳的事件數量正確、順序正確
- [x] 非法跳轉 (e.g. APPROVED → REJECTED) 回 409 + INVALID_STATE_TRANSITION
- [x] reject 沒帶 comment 回 400 VALIDATION_FAILED
- [x] case_id 不存在回 404
- [x] DB 端 `underwriting_case_event` 累積筆數與 API 操作對得上

全部打勾 → 進 M4 (保單 Domain Model)。

---

## 6. 面試話術 (M3 完成後該能口頭講出來的版本)

> 「在我做的銀保系統(S)，核保流程有 6 個狀態，業務員可能在不同時間點觸發各種轉移(T)。
> 我選擇用 enum 加 `EnumMap<From, EnumSet<To>>` 做表驅動狀態機(A)，
> 把『誰能轉到誰』集中在一張表裡，新增狀態只要改一行；
> 違反狀態機規則時丟自訂的 `IllegalStateTransitionException`，
> 由全域 `@RestControllerAdvice` 翻成 HTTP 409 Conflict。
> 同時為了稽核，每次成功 transition 都會 append 一筆 `underwriting_case_event`，
> 跟 `@Version` 樂觀鎖搭配，防止兩個核保員同時改造成的 lost update。
> 結果是 100 行潛在的 if-else 收斂成 6 行轉移表，新人理解時間從 1 小時降到 5 分鐘(R)。」
