# M9 Smoke Test — Spring Security + JWT + RBAC

完成於 2026-05-11。本文件涵蓋：
- M9.1 ~ M9.4 認證流程（登入 / 帶 token / 401 反向）
- M9.5 RBAC 三角色（ADMIN / UNDERWRITER / CSR）
- M9.6 Swagger UI BearerAuth 互動驗證
- 整合測試 `./mvnw test` 全綠（13 個 case，均含 Spring Security FilterChain）

---

## §0 變數約定

| 變數 | 意義 | 範例值 |
|---|---|---|
| `{{host}}` | API base URL | `http://localhost:8080` |
| `{{caseId}}` | 核保案件 UUID（POST 送件後從 response 抓） | `de305d54-75b4-431b-adb2-eb6b9e546014` |
| `{{policyId}}` | 保單 UUID（M4 種子的 5 張之一） | `11111111-1111-1111-1111-111111111111` |
| `{{token}}` | JWT access token（login response 的 `data.accessToken`） | `eyJhbGciOi...` |

Shell setup（強烈建議照貼）：
```bash
export host="http://localhost:8080"
# policy_1 是 M4 種子的 IN_FORCE 保單；其他 id 見 V4__seed_policy.sql
export policy_1="11111111-1111-1111-1111-111111111111"
```

---

## §1 環境前置

```bash
# 1. PostgreSQL 跑起來
docker compose up -d postgres
docker exec -i $(docker ps --filter name=postgres -q) \
  psql -U dev -d bancassurance -c "
    SELECT username, display_name FROM app_user ORDER BY username;
  "
# 預期看到 3 列：admin / csr01 / underwriter01

# 2. 啟動專案
./mvnw spring-boot:run
# log 應含一行：
#   JwtService initialized: issuer=bancassurance-backend, ttlMs=3600000 (60 min)
```

---

## §2 認證流程（M9.1 ~ M9.4）

### §2.1 沒帶 token → 401

```bash
curl -i "$host/api/policies?status=IN_FORCE"
```
預期：
```
HTTP/1.1 401
X-Trace-Id: xxx
{"status":401,"code":"AUTHENTICATION_REQUIRED",...,"traceId":"xxx"}
```

### §2.2 登入拿 token（注意 response 走 `data.accessToken`）

```bash
# 因為 M6 ApiResponseWrapper 自動包 envelope，accessToken 在 data.accessToken
UW_TOKEN=$(curl -s -X POST "$host/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"underwriter01","password":"uw123"}' \
  | jq -r '.data.accessToken')

echo "$UW_TOKEN"
# 預期：eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOi...（三段式）

# 完整 envelope 長相
curl -s -X POST "$host/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"underwriter01","password":"uw123"}' \
  | jq
# 預期：
# {
#   "code": "SUCCESS",
#   "message": "OK",
#   "data": {
#     "accessToken": "eyJ...",
#     "tokenType": "Bearer",
#     "expiresIn": 3600,
#     "username": "underwriter01",
#     "displayName": "王核保",
#     "roles": ["ROLE_UNDERWRITER"]
#   },
#   "traceId": "..."
# }
```

### §2.3 帶 token 打受保護 endpoint → 200

```bash
curl -i -H "Authorization: Bearer $UW_TOKEN" \
  "$host/api/policies?status=IN_FORCE&size=2"
```
預期：`HTTP/1.1 200`，回保單列表。

### §2.4 帶亂的 token → 401 INVALID_TOKEN

```bash
curl -i -H "Authorization: Bearer not-a-real-token" "$host/api/policies"
```
預期：`HTTP/1.1 401`，`code` 為 `AUTHENTICATION_REQUIRED`。

### §2.5 密碼錯 → 401 BAD_CREDENTIALS

```bash
curl -i -X POST "$host/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"underwriter01","password":"WRONG"}'
```
預期：`HTTP/1.1 401`，`code` 為 `BAD_CREDENTIALS`。

> 注意對外訊息是統一的 `"Bad credentials"`，server log 才有「密碼錯 / user 不存在 / 帳號 disabled」的細節。這是防使用者列舉（user enumeration）攻擊的標準做法。

### §2.6 觀察 server log 的 traceId

每個 401/200 都會在 log 看到 `[traceId=xxx]`，跟 response header 的 `X-Trace-Id` 一致 — M6 + M9 完整串通。

---

## §3 RBAC 三角色完整驗證（M9.5）

> 三個帳號：
> - `admin` / `admin123`：ADMIN + UNDERWRITER + CSR 全部
> - `underwriter01` / `uw123`：UNDERWRITER
> - `csr01` / `csr123`：CSR

### §3.1 csr01 送一張新案件（CASE_ID 抽出來給後面用）

```bash
CSR_TOKEN=$(curl -s -X POST "$host/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"csr01","password":"csr123"}' \
  | jq -r '.data.accessToken')

CASE_ID=$(curl -s -X POST "$host/api/underwriting/cases" \
  -H "Authorization: Bearer $CSR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "applicantName":"陳新人",
    "applicantIdNumber":"X111111111",
    "productCode":"LIFE-001",
    "coverageAmount":1000000.00,
    "premium":25000.00,
    "channel":"BANCASSURANCE"
  }' | jq -r '.data.id')
echo "CASE_ID=$CASE_ID"
```
預期：拿到一個 UUID。**server log 印出 `submittedBy=csr01`** — 證明 actor 是從 SecurityContext 取，不是 client 自報。

### §3.2 反向：csr01 想 approve → 403 ACCESS_DENIED

```bash
curl -i -X POST "$host/api/underwriting/cases/$CASE_ID/approve" \
  -H "Authorization: Bearer $CSR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"comment":"trying as CSR"}'
```
預期：`HTTP/1.1 403`，`code` 為 `ACCESS_DENIED`。

> `@PreAuthorize("hasAnyRole('UNDERWRITER', 'ADMIN')")` 在 method 層直接擋下；service 完全沒被呼叫到。

### §3.3 正向：underwriter01 領件 → 200

```bash
UW_TOKEN=$(curl -s -X POST "$host/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"underwriter01","password":"uw123"}' \
  | jq -r '.data.accessToken')

curl -s -X POST "$host/api/underwriting/cases/$CASE_ID/claim" \
  -H "Authorization: Bearer $UW_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{}' | jq '{status: .data.status, reviewedBy: .data.reviewedBy}'
```
預期：
```
{
  "status": "UNDER_REVIEW",
  "reviewedBy": "underwriter01"
}
```

### §3.4 正向：underwriter01 approve → 200

```bash
curl -s -X POST "$host/api/underwriting/cases/$CASE_ID/approve" \
  -H "Authorization: Bearer $UW_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"comment":"approving"}' | jq '.data.status'
# 預期：APPROVED
```

### §3.5 反向：underwriter01 想改保單 → 403

```bash
curl -i -X PATCH "$host/api/policies/$policy_1/address" \
  -H "Authorization: Bearer $UW_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"expectedVersion":0,"newAddress":"trying as underwriter"}'
```
預期：`HTTP/1.1 403`，`code` 為 `ACCESS_DENIED`。

### §3.6 正向：csr01 改保單地址 → 200

```bash
# 先看當前 version (M5 ETag header)
ETAG=$(curl -s -I -H "Authorization: Bearer $CSR_TOKEN" \
  "$host/api/policies/$policy_1" | grep -i "^ETag:" | awk '{print $2}' | tr -d '\r')
echo "Current ETag: $ETAG"  # "0" 或 "N" 看你跑過幾次

curl -i -X PATCH "$host/api/policies/$policy_1/address" \
  -H "Authorization: Bearer $CSR_TOKEN" \
  -H "If-Match: $ETAG" \
  -H "Content-Type: application/json" \
  -d "{\"expectedVersion\":${ETAG//\"/},\"newAddress\":\"新北市新店區安康路一段100號\",\"reason\":\"M9.6 smoke test\"}"
```
預期：`HTTP/1.1 200`，新 ETag。**`change_log.actor = csr01` — 從 SecurityContext 取**。

### §3.7 RBAC 速查表

| Endpoint | csr01 | underwriter01 | admin |
|---|---|---|---|
| POST `/api/auth/login` | 200 | 200 | 200 |
| POST `/api/underwriting/cases` | 201 | **403** | 201 |
| GET `/api/underwriting/cases` | 200 | 200 | 200 |
| POST `/api/underwriting/cases/{id}/claim` | **403** | 200 | 200 |
| POST `/api/underwriting/cases/{id}/approve` | **403** | 200 | 200 |
| POST `/api/underwriting/cases/{id}/reject` | **403** | 200 | 200 |
| POST `/api/underwriting/cases/{id}/withdraw` | 200 | **403** | 200 |
| GET `/api/policies/{id}` | 200 | 200 | 200 |
| PATCH `/api/policies/{id}/address` | 200 | **403** | 200 |
| PATCH `/api/policies/{id}/beneficiaries` | 200 | **403** | 200 |

---

## §4 Swagger UI BearerAuth 互動驗證（M9.6）

1. 開 `$host/swagger-ui`
2. 右上角點 **Authorize**（鎖頭 icon）
3. 跳出 `bearerAuth (http, Bearer)` 對話框
4. **只貼 token 本身，不必加 "Bearer " 前綴** — Swagger UI 會自動加
5. 按 Authorize → Close
6. 隨意展開一個 endpoint（例如 `GET /api/policies/{id}`）→ Try it out → Execute
7. 觀察 cURL command 區塊應該包含 `-H "Authorization: Bearer eyJ..."`
8. 回應 200

另外注意：
- `POST /api/auth/login` 在 Swagger UI **不會出現鎖頭** — 因為 `@SecurityRequirements({})` 把它從全域 auth 排除
- 重整網頁 token 仍記得（`persist-authorization: true`）

---

## §5 整合測試 `./mvnw test` 全綠

```bash
./mvnw test
```
預期：
```
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
```

包含的 13 個 case 都已升級為「帶 SecurityContext」：
- `BancassuranceApplicationTests` (1)
- `PolicyOptimisticLockConcurrencyTest` (1，直接呼 service 不走 MockMvc)
- `PolicyChangeIdempotencyTest` (3，全部加 `.with(user("alice").roles("CSR"))`)
- `PolicyChangeNegativeTest` (7，全部加 `.with(user("alice").roles("CSR"))`)
- 上述 MockMvc 測試共用 `IntegrationTestBase` 的 `.apply(springSecurity())` — 真實走 Spring Security FilterChain

> 如果某個 MockMvc test 紅了，最常見原因是忘記加 `.with(user(...))` 導致 401。觀察 response 是否 401 即可定位。

---

## §6 面試話術（按難度分級）

### 初級
**Q：JWT 三段是什麼？**
A：header.payload.signature。前兩段 Base64Url 編碼的 JSON，第三段是用 secret 對前兩段算 HMAC-SHA256 的簽章，防止竄改。

**Q：Spring Boot 4 怎麼設 SecurityConfig？**
A：Spring Security 6+ 用 `SecurityFilterChain` bean + lambda DSL，淘汰了舊的 `WebSecurityConfigurerAdapter`。

### 中級
**Q：HS256 vs RS256 怎麼選？**
A：HS256 對稱密鑰，簽驗用同一把 secret，速度快但 secret 不能給第三方；RS256 非對稱，private 簽 public 驗，適合 OAuth2 / 跨服務 — 銀行內部系統大多 HS256，對外 API 改 RS256。

**Q：JWT 為什麼難做登出？**
A：Stateless — server 不存 session，無從作廢已簽出的 token。三種解法：(1) 短 TTL（access 15min）+ refresh token；(2) Redis 黑名單存 jti；(3) 改 user 的 password 版本號當 claim，登出時 bump。

**Q：為什麼 stateless API 不需要 CSRF？**
A：CSRF 攻擊本質是「利用瀏覽器自動帶 cookie」。JWT 走 `Authorization` header，瀏覽器不會自動加，攻擊者無法偽造受害者瀏覽器的 request。

**Q：`@PreAuthorize` 跟 `@Secured` 差在哪？**
A：`@Secured` 只能寫 role 字串；`@PreAuthorize` 支援 SpEL，可寫 `authentication.principal.id == #policy.holderId` 這種「只能改自己單」的 ABAC 規則。業界一面倒用 `@PreAuthorize`。

**Q：401 vs 403？**
A：401 = 「我不知道你是誰」（沒登入 / token 無效）；403 = 「我知道你是誰，但你不能做這件事」（角色不足）。HTTP 規範裡 401 應該叫 Unauthenticated，這是歷史包袱。

**Q：為什麼 BCrypt 不用 SHA-256 雜湊密碼？**
A：SHA-256 速度太快，GPU 一秒可暴力破解幾億次；且沒內建 salt，同密碼永遠雜湊相同，rainbow table 命中率高。BCrypt 內建 salt + 工作因子（strength=10 ≈ 100ms/次），跟 hardware 速度脫鉤。OWASP 2024 推薦 Argon2id > scrypt > BCrypt > PBKDF2。

### 資深
**Q：為什麼 SecurityFilterChain 的例外處理跟 `@ControllerAdvice` 分離？**
A：FilterChain 在 DispatcherServlet 之前，丟 `RuntimeException` 不會被 `@RestControllerAdvice` 接到。Spring Security 設計上「在 filter 層解掉」— 用 `AuthenticationEntryPoint`（401）+ `AccessDeniedHandler`（403）這兩個 servlet-level 的「advice」。所以本專案有兩條 403 路徑：filter 層走 `RestAccessDeniedHandler`、method 層 `@PreAuthorize` 失敗走 `GlobalExceptionHandler`。

**Q：MockMvc 怎麼測 Spring Security？**
A：`MockMvcBuilders.apply(SecurityMockMvcConfigurers.springSecurity())` 把 `springSecurityFilterChain` bean 套進 MockMvc，然後用 `.with(user("alice").roles("CSR"))` post-processor 控制每次 request 的安全狀態，bypass 真實 JWT 驗證但仍走完整個 FilterChain。本專案 `IntegrationTestBase.MockMvcConfig` 就這樣寫。

**Q：JWT secret 怎麼管？怎麼輪替？**
A：絕不寫死進 git（CWE-798）。Production 從 Vault / AWS Secrets Manager / k8s Secret 注入環境變數。輪替策略：
1. 雙 key 並存期：新 secret 開始簽，舊 secret 仍可驗（kid claim 標記）
2. 等舊 token 自然過期（TTL = 1h 的話 1h 後就清）
3. 完全切到新 secret
JJWT 0.12+ 支援 `keyLocator` 動態決定每個 token 用哪把 key 驗，這就是給 key rotation 用的。

**Q：怎麼從 RBAC 升級到 ABAC？**
A：用 `@PreAuthorize` 的 SpEL 寫條件規則：
```java
@PreAuthorize("hasRole('UNDERWRITER') and #policy.coverageAmount <= 5000000 " +
              "and authentication.principal.userId == @assignmentService.getAssignedUw(#policy.id)")
```
複雜場景改 ABAC 政策引擎（XACML、OPA、Casbin），把規則從 code 抽到外部 DSL — 修規則不必動程式碼。

---

## §7 上 Production 前的 follow-up

M9 是 MVP 版本，下列項目線上化前應補：

| 項目 | 現況 | Production 應該做 |
|---|---|---|
| JWT secret | application.yml 寫死 dev secret | 從 Vault / k8s Secret 注入 |
| Algorithm | HS256 對稱 | RS256 + JWKS 公開驗證 key |
| Refresh token | 沒做 | access 15min + refresh 7day + DB 記錄可作廢 |
| Logout | 沒做 | Redis 黑名單存 jti |
| 帳號鎖定 | 沒做 | 連續錯 5 次 lock 30min（AppUser.accountNonLocked） |
| 密碼複雜度 | 沒做 | 註冊 / 改密碼時驗 OWASP password policy |
| MFA | 沒做 | 高敏動作（如保單變更）要求 TOTP / SMS 二次驗證 |
| Audit | actor 已從 SecurityContext 取 | 加入「成功/失敗登入」事件入 audit 表，配合 SIEM |
| API rate limit | 沒做 | 加 Bucket4j / Resilience4j，特別 login endpoint 防爆破 |
| Argon2id 雜湊 | BCrypt strength=10 | 用 DelegatingPasswordEncoder 漸進式升級到 Argon2id |
| Method security | `@PreAuthorize` 用 role | 加 ABAC 條件（金額上限、區域、客戶歸屬） |
| Spring Security Headers | 預設 | 加 CSP、HSTS（HTTPS 才有意義） |

---

## §8 完成檢查單

- [x] §1 環境前置：PG 起得來、3 個種子帳號都在
- [x] §2.1–2.6 認證流程全部跑過（特別注意 envelope `data.accessToken`）
- [x] §3.1–3.6 RBAC 三角色都有正向 + 反向
- [x] §4 Swagger UI Authorize 對話框出現、try-it-out 自動帶 token
- [x] §5 `./mvnw test` 13 個全綠
- [x] §3.7 速查表的 10 個格子都核對過
