# M4 上半 — 本機驗證與冒煙測試 (保單查詢)

> M4 上半範圍：`Policy` / `Beneficiary` Entity + 三支 GET API。
> 重點學習：`@OneToMany` (LAZY / cascade / orphanRemoval / mappedBy)、
>           `JOIN FETCH` 解 N+1、`Specification` 動態查詢、Pageable。

---

## 0. 變數約定 (placeholder convention)

下方 curl 範例使用以下 placeholder，**執行前請替換**：

| 變數 | 意義 | 範例值 |
|---|---|---|
| `{{host}}` | API base URL | `{{host}}` |
| `{{id}}`   | URL path 上的識別子 — 依 endpoint 而定：對 `/by-number/{id}` 是 `policyNumber`、對 `/{id}` 是 UUID | `BANK-LIFE-20260507-0001` 或 `11111111-1111-1111-1111-111111111111` |

最快的執行方式：用 shell 變數一次設好，curl 改用 `"$host"` / `"$id"` 即可（注意外層引號要雙引號才會展開）：

```bash
export host={{host}}
export id=BANK-LIFE-20260507-0001
curl -s "$host/api/policies/by-number/$id" | jq
```

或者用 `envsubst` 把整段檔案展開：`envsubst < M4_SMOKE_TEST.md`（前提是把 `{{host}}` 改成 `${host}` 格式，本檔保留 `{{...}}` 是為了文件可讀性）。

---

## 1. 編譯與啟動

```bash
cd ~/Documents/Claude/Projects/HelloJavaInBancassurance
./mvnw -q -DskipTests compile
docker compose up -d
./mvnw spring-boot:run
```

啟動 log 應看到 Flyway 跑了 V3 / V4：

```
Migrating schema "public" to version "3 - init policy"
Migrating schema "public" to version "4 - seed policy"
```

驗證表 + 種子資料 (psql 或 IntelliJ Database tool)：

```sql
\d policy
\d policy_beneficiary
SELECT version, description, success, installed_on FROM flyway_schema_history;

SELECT policy_number, holder_name, status, channel, coverage_amount
FROM policy
ORDER BY effective_date DESC;

-- 應看到 5 筆種子：BANK-LIFE-20260507-0001 ... BANK-LIFE-20060101-0001
```

---

## 2. 三支查詢 API 的正向流程

### 2.1 用對外保單號查單筆 (含受益人)

```bash
# {{id}} = policyNumber，例如 BANK-LIFE-20260507-0001
curl -s '{{host}}/api/policies/by-number/{{id}}' | jq
```

預期回應 (重點欄位)：

```json
{
  "id": "11111111-1111-1111-1111-111111111111",
  "policyNumber": "BANK-LIFE-20260507-0001",
  "productCode": "LIFE-WHOLE-001",
  "holderName": "王小明",
  "maskedHolderIdNumber": "A12***789",
  "insuredName": "王小明",
  "maskedInsuredIdNumber": "A12***789",
  "coverageAmount": 3000000.00,
  "premium": 850000.00,
  "premiumPaymentMethod": "SINGLE_PAY",
  "channel": "BANCASSURANCE",
  "status": "IN_FORCE",
  "effectiveDate": "2026-05-01",
  "expiryDate": "2056-05-01",
  "billingAddress": "台北市信義區信義路五段7號",
  "beneficiaries": [
    { "name": "王太太", "relationship": "SPOUSE", "allocationPercentage": 60.00, "priority": 1 },
    { "name": "王大寶", "relationship": "CHILD",  "allocationPercentage": 40.00, "priority": 1 }
  ],
  "version": 0,
  "createdAt": "...",
  "updatedAt": "..."
}
```

開 SQL log (`spring.jpa.show-sql=true`) 應看到「**單一**」`SELECT ... LEFT JOIN ...` —
這就是 `findByPolicyNumberWithBeneficiaries` 的 JOIN FETCH 效果：

```sql
SELECT p.*, b.* FROM policy p
LEFT JOIN policy_beneficiary b ON b.policy_id = p.id
WHERE p.policy_number = ?
```

如果這裡看到「2 條 SQL」(先撈 policy，再撈 beneficiaries)，就是 N+1 沒解，
代表 `findByPolicyNumber` 而不是 `findByPolicyNumberWithBeneficiaries` 被呼叫。

### 2.2 用內部 UUID 查單筆

```bash
# {{id}} = policy UUID，例如 11111111-1111-1111-1111-111111111111
curl -s '{{host}}/api/policies/{{id}}' | jq
```

回應同 2.1。

### 2.3 多條件清單查詢 (分頁)

#### 2.3.1 全部保單，預設分頁 (size=20，按 effectiveDate desc)

```bash
curl -s '{{host}}/api/policies' | jq '.content | map({policyNumber, status, effectiveDate})'
```

預期：5 筆，按生效日由新到舊：

```
BANK-LIFE-20260507-0001  IN_FORCE  2026-05-01
ONLINE-LIFE-20260301-0007 IN_FORCE 2026-03-15
AGENT-LIFE-20260108-0042 IN_FORCE  2026-01-15
BANK-MEDI-20251201-0099  LAPSED    2025-12-01
BANK-LIFE-20060101-0001  MATURED   2006-01-01
```

#### 2.3.2 「我的保單」— 用持有人身分證查 (示範陳大明的 2 張保單)

```bash
curl -s '{{host}}/api/policies?holderIdNumber=E456789012' | jq '.content'
```

應回 2 筆 (Policy 3 LAPSED + Policy 4 IN_FORCE)，按 effectiveDate desc。

#### 2.3.3 只看 IN_FORCE 的保單

```bash
curl -s '{{host}}/api/policies?status=IN_FORCE' | jq '.totalElements, .content | map(.policyNumber)'
```

預期：3 筆 IN_FORCE。

#### 2.3.4 多條件組合：銀保通路 + IN_FORCE + 商品 LIFE-WHOLE-001

```bash
curl -s '{{host}}/api/policies?channel=BANCASSURANCE&status=IN_FORCE&productCode=LIFE-WHOLE-001' | jq '.content'
```

預期：1 筆 — 王小明的 BANK-LIFE-20260507-0001。

#### 2.3.5 日期範圍 + 分頁參數

```bash
curl -s '{{host}}/api/policies?effectiveDateFrom=2026-01-01&effectiveDateTo=2026-12-31&page=0&size=2&sort=effectiveDate,asc' | jq
```

預期：第一頁 2 筆，按 effectiveDate 升冪：AGENT-LIFE-20260108-0042 → ONLINE-LIFE-20260301-0007。
`totalElements=3`、`totalPages=2`。

---

## 3. 反向流程 (錯誤處理)

### 3.1 找不到保單 → 404

```bash
# {{id}} 故意給一個不存在的值，例如 NOT-EXIST-12345
curl -i -s '{{host}}/api/policies/by-number/{{id}}'
```

預期：HTTP 404，body 為 GlobalExceptionHandler 的 ApiError 結構：

```json
{
  "code": "RESOURCE_NOT_FOUND",
  "message": "Policy not found: <你給的 id>",
  "timestamp": "..."
}
```

### 3.2 不合法的 enum 值 → 400

```bash
curl -i -s '{{host}}/api/policies?status=NOT_A_STATUS'
```

預期：HTTP 400，由 `GlobalExceptionHandler.handleTypeMismatch` 回應：

```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "VALIDATION_FAILED",
  "message": "Parameter 'status' has invalid value 'NOT_A_STATUS'; expected type PolicyStatus",
  "path": "/api/policies",
  "timestamp": "...",
  "details": [
    { "field": "status", "message": "expected PolicyStatus, got 'NOT_A_STATUS'" }
  ]
}
```

> 注意：Spring 對 query string 「型別轉失敗」拋的是 `MethodArgumentTypeMismatchException`，
> **不是** `MethodArgumentNotValidException` (後者只在 `@RequestBody @Valid` 失敗時拋)。
> 這兩者都該回 400，但要分別寫 handler。

### 3.3 不合法的日期格式 → 400

```bash
curl -i -s '{{host}}/api/policies?effectiveDateFrom=2026/01/01'
```

預期：HTTP 400，body 結構同 3.2，但 `field` 是 `effectiveDateFrom`、`expected type` 是 `LocalDate`。
`@DateTimeFormat(iso = DATE)` 只接 `YYYY-MM-DD`。

---

## 4. SQL 驗證 (對 DB 直接打)

### 4.1 確認索引被使用

```sql
EXPLAIN ANALYZE
SELECT * FROM policy
WHERE holder_id_number = 'E456789012'
ORDER BY effective_date DESC LIMIT 20;
```

預期執行計畫看到 `Index Scan using idx_policy_holder_id_number`。
資料量小時 PG 可能選 Seq Scan，這正常 — 上百萬筆才看得出索引價值。

### 4.2 同 holder 的多張保單

```sql
SELECT policy_number, status, channel, effective_date
FROM policy
WHERE holder_id_number = 'E456789012'
  AND is_deleted = FALSE   -- 等同 @SQLRestriction
ORDER BY effective_date DESC;
```

### 4.3 受益人加總 = 100 (應用層保證，DB 端沒 CHECK)

```sql
SELECT policy_id, SUM(allocation_percentage) AS total
FROM policy_beneficiary
WHERE is_deleted = FALSE
GROUP BY policy_id
HAVING SUM(allocation_percentage) <> 100;
```

預期：0 筆 (除了 Policy 3 沒受益人 — 不會被 GROUP BY 列出，這是 OK 的)。

---

## 5. 觀察 N+1 是否被解決 (進階)

打開 application-local.yml 的 SQL log：

```yaml
spring:
  jpa:
    show-sql: true
    properties:
      hibernate.format_sql: true
      hibernate.use_sql_comments: true
logging:
  level:
    org.hibernate.SQL: DEBUG
```

### 5.1 單筆查詢應為 1 條 SQL (JOIN FETCH)

呼叫 `GET /api/policies/{id}` → console 應看到一條 LEFT JOIN，不應該看到第二條
`SELECT ... FROM policy_beneficiary WHERE policy_id = ?`。

### 5.2 清單查詢應為 1 條 SQL (不 fetch beneficiaries)

呼叫 `GET /api/policies` → console 應只看到一條 `SELECT ... FROM policy`，
**不**應該看到 5 條 `SELECT ... FROM policy_beneficiary` (那就是 N+1)。

理由：`PolicySummaryResponse` 不存取 `beneficiaries`，所以 LAZY 不會被觸發。

---

## 6. 完成檢查單

- [x] `./mvnw -q -DskipTests compile` 通過
- [x] Flyway V3 / V4 跑成功，5 筆種子在 `policy` 表
- [x] `policy_beneficiary` 共有 6 筆 (王: 2, 李: 1, 陳 4: 1, 林: 2, 陳 3 沒受益人)
- [x] `GET /api/policies/by-number/BANK-LIFE-20260507-0001` 回 200，含 2 個受益人，按 priority asc + allocation desc 排序
- [x] `GET /api/policies?holderIdNumber=E456789012` 回 2 筆
- [x] `GET /api/policies?status=IN_FORCE` 回 3 筆
- [x] `GET /api/policies?effectiveDateFrom=2026-01-01&effectiveDateTo=2026-12-31&page=0&size=2&sort=effectiveDate,asc` 分頁正確 (totalElements=3)
- [x] 找不到保單 → 404 RESOURCE_NOT_FOUND
- [x] 身分證在回應中已遮罩成 `A12***789`
- [x] SQL log 確認單筆查詢只打 1 條 SQL (JOIN FETCH)；清單查詢也只打 1 條 SQL

---

## 7. 面試話術 (S-T-A-R)

### 7.1 「你怎麼設計分頁查詢？」(中級)

> S：銀保系統客服進線最常打的是「查王小明名下所有在保中的保單」(T)。
> A：在 `PolicyRepository` 同時繼承 `JpaRepository` 與 `JpaSpecificationExecutor`。
> 用 `Specification` 把每個過濾條件寫成靜態工廠 (`statusIs(IN_FORCE)`、`holderIdNumberIs(...)`)，
> 條件為 null 就回 `cb.conjunction()` 當作不加條件。
> 清單頁回 `Page<PolicySummaryResponse>` (不含受益人) 避免列表頁誤觸發 N+1。
> R：5 個動態條件 + 分頁排序在一個 endpoint 解決，DTO 分兩種 (Summary / Full) 讓 SQL 形狀
> 在 list / detail 分別最佳化。

### 7.2 「N+1 怎麼解？」(中級)

> 列表查 100 張保單，如果 `Policy.beneficiaries` 沒設 LAZY、且 ToString / JSON 序列化會
> 觸發載入 → 多打 100 條 SELECT policy_beneficiary。
> 解法兩層：
>   1. Entity 層：`@OneToMany(fetch = LAZY)` 預防意外觸發
>   2. Service 層：列表用不需要受益人的 DTO；單筆需要時用 `JOIN FETCH` 一次撈完
> 反例：ToString 沒 exclude beneficiaries → debug log 會觸發 lazy load，照樣 N+1。
> 我們在 `Policy` 類用 `@ToString(exclude = {..., "beneficiaries"})` 防這條。

### 7.3 「`@OneToMany` 的 cascade、orphanRemoval、mappedBy 用過嗎？」(資深)

> - `cascade = ALL`：把受益人當保單的「組件」，新增 / 刪除一起跑
> - `orphanRemoval = true`：從 list 移除某個受益人 → DELETE。`cascade=REMOVE` 涵蓋不到「踢出 list」這種情境
> - `mappedBy = "policy"`：告訴 JPA 真正的外鍵在 `Beneficiary.policy`，本表是反向端；不寫的話 Hibernate 會多開一張中間表

### 7.4 「動態查詢條件 5 個都可有可無，怎麼做？」(資深)

> 三個選項：Specification、QueryDSL、native SQL + StringBuilder。
> 我選 Specification 的理由：型別安全、零額外依賴、IDE 自動提示；條件少於 10 個時可讀性夠。
> 條件超過 10 個 + 多 JOIN → 切 QueryDSL，避免 lambda 嵌太深。
> 千萬不要 native SQL + StringBuilder，那是 SQL injection 高風險區。

---

## 8. 已知限制 / M4 下半 (= M5) 預告

- 受益人加總 = 100 的規則在 DB 端沒 CHECK，靠應用層 (M5 變更受益人時會驗證)
- `policy.underwriting_case_id` 目前 nullable + 沒 FK，等 M5 再補
- `@Version` 欄位已就緒但本階段 (純查詢) 不會被增量；M5 變更時你會看到它從 0 → 1 → 2

下一步 → **M5 保單變更**。我會帶你實作 `PATCH /api/policies/{id}/beneficiary`，
把 `@Transactional`、`@Version` 樂觀鎖、冪等性鍵 (`Idempotency-Key` header)、
`If-Match: ETag` → 412/409 全部串起來。這是面試最會被深挖的環節。
