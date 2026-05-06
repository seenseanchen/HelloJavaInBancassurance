# M2 — 本機驗證與冒煙測試 (Smoke Test)

> 沙箱裡只有 JDK 11，跑不動 Spring Boot 4 / Java 21。
> 請在 macOS 本機 (你已裝 JDK 21) 照下面步驟驗證。

---

## 1. 編譯檢查 (最快的「程式有寫對嗎」驗證)

```bash
cd ~/Documents/Claude/Projects/HelloJavaInBancassurance
./mvnw -q -DskipTests compile
```

期望：BUILD SUCCESS，target/classes 下出現 14 個 .class。
若有編譯錯誤：通常是 import 漏掉或 jakarta vs javax 命名 (Spring Boot 3+ 一律 jakarta)。

---

## 2. 啟動 PostgreSQL + 跑 Flyway

```bash
docker compose up -d           # 確認 docker-compose.yml 已啟好 PG 16
./mvnw spring-boot:run
```

啟動 log 應看到：

```
o.f.c.i.s.JdbcTableSchemaHistory       : Successfully validated 1 migration
o.f.core.internal.command.DbMigrate    : Migrating schema "public" to version "1 - init underwriting"
o.f.core.internal.command.DbMigrate    : Successfully applied 1 migration to schema "public"
```

驗證表已建立：

```bash
docker exec -it <pg-container> psql -U dev -d bancassurance -c '\d underwriting_case'
```

---

## 3. API 冒煙測試 (curl)

### 3.1 健康檢查 (M1 沒壞)

```bash
curl -s http://localhost:8080/api/health | jq
```

### 3.2 送件 — POST /api/underwriting/cases

```bash
curl -i -X POST http://localhost:8080/api/underwriting/cases \
  -H 'Content-Type: application/json' \
  -d '{
    "applicantName": "王小明",
    "applicantIdNumber": "A123456789",
    "productCode": "LIFE-TERM-20",
    "coverageAmount": 3000000,
    "premium": 18000,
    "channel": "BANCASSURANCE",
    "submittedBy": "teller01@bank"
  }'
```

期望 HTTP `201 Created`，回應 body 包含：
- `id` (UUID)
- `caseNumber` 形如 `UW-20260506-0042`
- `status: "SUBMITTED"`
- `maskedApplicantIdNumber: "A12***789"` (敏感欄位已遮罩)
- 帶 `Location: /api/underwriting/cases/<uuid>` header

### 3.3 用 UUID 查單筆

```bash
curl -s http://localhost:8080/api/underwriting/cases/<剛剛的 uuid> | jq
```

### 3.4 用業務編號查

```bash
curl -s http://localhost:8080/api/underwriting/cases/by-number/UW-20260506-0042 | jq
```

### 3.5 列表 + 分頁

```bash
curl -s 'http://localhost:8080/api/underwriting/cases?status=SUBMITTED&page=0&size=10' | jq
```

### 3.6 故意打破驗證 → 期望 400

```bash
curl -i -X POST http://localhost:8080/api/underwriting/cases \
  -H 'Content-Type: application/json' \
  -d '{
    "applicantName": "",
    "applicantIdNumber": "A1",
    "productCode": "P1",
    "coverageAmount": -1,
    "premium": 0,
    "channel": "BANCASSURANCE",
    "submittedBy": "x"
  }'
```

期望：`400 Bad Request`，body 是統一的 `ApiError` 結構，`code: "VALIDATION_FAILED"`，`details` 列出 `applicantName / coverageAmount / premium` 的錯誤訊息。

### 3.7 找不到資源 → 期望 404

```bash
curl -i http://localhost:8080/api/underwriting/cases/00000000-0000-0000-0000-000000000000
```

期望 `404 Not Found`，`code: "RESOURCE_NOT_FOUND"`。

---

## 4. 確認 DB 端寫入正確

```sql
SELECT id, case_number, status, applicant_name, coverage_amount,
       created_at, created_by, updated_at, updated_by, version, is_deleted
FROM underwriting_case
ORDER BY created_at DESC
LIMIT 5;
```

確認：
- `created_by` / `updated_by` = `"system"` (M2 階段固定值)
- `created_at` / `updated_at` 為 UTC 時間
- `version` = 0 (新建)
- `is_deleted` = false

---

## 5. M2 完成檢查單

- [x] `mvn compile` 通過
- [x] Flyway 成功跑 V1
- [x] POST 建案件 → 201 + Location header
- [x] GET by id / by case_number 都拿得到
- [x] 列表 + 狀態過濾正確
- [x] Validation 失敗回 400 結構化錯誤
- [x] 找不到回 404 結構化錯誤
- [x] DB 內 audit 欄位自動填入

全部打勾 → 進 M3 (狀態機 + 業務規則)。
