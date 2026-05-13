# HelloJavaInBancassurance

> **學習目的**：準備銀行 / 人壽保險業 Java 後端工程師面試。
> 以「銀保通路 (Bancassurance)」業務情境為主軸，從零建構一套 Spring Boot 後端 API 系統。

---

## 🗺️ 學習路徑（後端核心 M0→M8 + 擴展 M11/M12/M13/M16）

```
M0 環境  ──►  M1 骨架  ──►  M2 核保 CRUD  ──►  M3 狀態機
                                                     │
                                                     ▼
M8 整合測試  ◄──  M7 Swagger  ◄──  M6 例外處理  ◄──  M4 保單查詢
                                                     │
                                                     ▼
                                                  M5 保單變更 ★ (核心)
```

- **M5 保單變更** 是整個作品集的高潮：`@Transactional`、`@Version` 樂觀鎖、Idempotency Key、JSONB 稽核日誌全部在這一個 milestone 集結。

Infra / Frontend 擴展路徑（已實作）：

```
M11 Nginx Gateway  ──►  M12 OTel + Jaeger + Prometheus + Grafana  ──►  M13 EFK + ILM
                                   │
                                   └──────────────────────────────►  M16 Vue 3 + Design Token + Login
```

## ✅ 已完成里程碑（截至 2026-05-12）

| Milestone | 完成內容 | 驗收文件 |
|---|---|---|
| **M11** | Nginx gateway（HTTP→HTTPS 轉址、`/api` reverse proxy、轉發標頭） | `ops/infra/docs/M11_SMOKE_TEST.md` |
| **M12** | 可觀測性平台（OTel Collector、Jaeger、Prometheus、Grafana + dashboard provisioning） | `ops/infra/docs/M12_SMOKE_TEST.md` |
| **M13** | 集中化日誌（Elasticsearch、Kibana、Fluent Bit）+ ILM policy / index template / data view | `ops/infra/docs/M13_SMOKE_TEST.md` |
| **M16** | Vue 3 前端骨架（Pinia、Router、Tailwind、Element Plus、Login flow、路由守衛） | `docs/M16_SMOKE_TEST.md` |

---

## 🏗️ 業務情境

### 功能一：人壽審查契約（Underwriting）

保戶投保 → 業務員送件 → 核保員審查 → 通過 / 退件 / 補件

重點學習：**狀態機設計、多角色流程、稽核軌跡**

### 功能二：保單查詢與變更（Policy Inquiry & Endorsement）

查詢保單明細 / 受益人 / 繳費狀態；線上申請地址、受益人、繳費方式變更

重點學習：**交易控制、樂觀鎖、冪等性、稽核**

---

## ⚙️ 技術棧

| 類別 | 選用 | 選用理由 |
|---|---|---|
| 語言 | Java 21 LTS | 銀行新案採用率提升；Virtual Thread、record、sealed class |
| 框架 | Spring Boot 4.0.x | 最新主版本，對比 3.3.x 差異 |
| Build | Maven | 金融業最常見，`pom.xml` 結構穩定 |
| ORM | Spring Data JPA + Hibernate 6 | 標準解；搭配 `@Version` 樂觀鎖 |
| DB | PostgreSQL 16（Docker） | ACID、JSONB、銀行 POC 常用 |
| Migration | Flyway | 版控 DDL，金融業必備 |
| API 文件 | springdoc-openapi（Swagger UI） | 自動產生，可直接匯入 Postman |
| 工具庫 | Lombok、MapStruct | 減少樣板程式碼；DTO ↔ Entity 安全轉換 |
| 測試 | JUnit 5 + Testcontainers | 用真實 PostgreSQL 跑整合測試，避免 H2 假象 |
| Gateway | Nginx（Docker Compose） | 統一入口、TLS、反向代理，對接前後端 |
| 觀測 | OpenTelemetry + Jaeger + Prometheus + Grafana | Tracing / Metrics / Dashboard 三柱閉環 |
| 日誌 | Elasticsearch + Kibana + Fluent Bit（EFK） | 集中查詢應用日誌，並套用 ILM 生命週期策略 |
| 前端 | Vue 3 + Vite + Pinia + Router + Tailwind + Element Plus | M16 前端殼層與登入流程，串接後端 API |

---

## 📁 專案結構（Package by Feature）

```
src/main/java/com/sean/bancassurance/
├── BancassuranceApplication.java
├── common/
│   ├── exception/        # GlobalExceptionHandler、自訂業務例外
│   ├── audit/            # JPA AuditorAware、稽核欄位
│   └── config/           # OpenAPI、Jackson、TraceId Filter
├── underwriting/         # 人壽審查契約
│   ├── api/              # Controller + DTO
│   ├── domain/           # Entity、UnderwritingStatus 狀態機
│   ├── repository/
│   └── service/
└── policy/               # 保單查詢與變更
    ├── api/              # Controller + DTO（PolicyResponse / PolicySummaryResponse 雙 DTO）
    ├── domain/           # Policy、Beneficiary Entity；PolicySpecifications 動態查詢
    ├── repository/
    └── service/          # PolicyService（readOnly）、PolicyChangeService（寫操作）
```

> **Package by Feature 而非 by Layer**：金融業專案動輒數百支 Controller，依功能分包能避免「Controller 包 1000 個 class」的維護災難。

---

## 🔑 核心設計決策

### 1. 狀態機（M3）— 表驅動 vs 策略模式

核保流程有 6 個狀態、6 種動作，選用 **EnumMap 表驅動**：

```java
// UnderwritingStatus.java（簡化示意）
SUBMITTED {
    @Override public Set<UnderwritingStatus> nextStates() {
        return EnumSet.of(UNDER_REVIEW, WITHDRAWN);
    }
},
UNDER_REVIEW {
    @Override public Set<UnderwritingStatus> nextStates() {
        return EnumSet.of(APPROVED, REJECTED, PENDING_INFO);
    }
}
```

非法跳轉拋 `IllegalStateTransitionException` → 翻成 `409 Conflict`。

選用理由：規則穩定（6 個動作）時，表驅動比每個動作一個策略 class 更易讀；若狀態超過 15 種再考慮 Spring StateMachine。

---

### 2. 樂觀鎖 + `@Transactional`（M5）

```
GET /policies/{id}     → 回應 ETag: "N"（版本號）
PATCH /policies/{id}/address
  Header: If-Match: "N"   ← client 帶版本
  Body:   { "expectedVersion": N, ... }

Service 流程：
  assertVersionMatches(N)  → N 過期 → 412 Precondition Failed
  loadEntity + sleep(demo) → A、B 都讀到 v=N
  saveAndFlush             → WHERE version=N → 0 rows → 409 OPTIMISTIC_LOCK_CONFLICT
```

| | 412 | 409 |
|---|---|---|
| 觸發點 | `assertVersionMatches()` — 應用層前置檢查 | Hibernate flush — DB `WHERE version=N` 0 rows |
| 語意 | Client cache 太舊 | 真實併發衝突 |
| HTTP 標準 | RFC 7232 §4.2 | 慣例 |

---

### 3. 冪等性（M5）

```
idempotency_record (PK = idempotency_key)
  ├── request_hash  (SHA-256 of body)
  ├── response_body (上次的回應快照)
  └── endpoint      (防止同一 key 打不同 endpoint)
```

流程：SELECT → miss → 執行業務 → INSERT；hit → 比對 hash → replay 或 422。
併發雙 INSERT：PK constraint 擋住，其中一個回 5xx；client 重試時 hit 第一個的紀錄 → replay。

---

### 4. 稽核日誌（M5）— JSONB before/after snapshot

```sql
CREATE TABLE policy_change_log (
    id              UUID PRIMARY KEY,
    policy_id       UUID NOT NULL REFERENCES policy(id),
    change_type     VARCHAR(50) NOT NULL,     -- ADDRESS / BENEFICIARIES / PAYMENT_METHOD
    before_snapshot JSONB NOT NULL,
    after_snapshot  JSONB NOT NULL,
    reason          TEXT,
    actor           VARCHAR(255) NOT NULL,
    after_version   INTEGER NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL
);
```

與主交易共生死（同一個 `@Transactional`）：業務操作 rollback → log 也 rollback，避免「log 說改了但 DB 沒改」的稽核錯亂。

---

### 5. 統一回應格式（M6）

```json
// 成功
{ "code": "SUCCESS", "message": "OK", "data": { ... }, "traceId": "a3f..." }

// 失敗（ApiError，不包 ApiResponse）
{ "status": 409, "error": "Conflict", "code": "OPTIMISTIC_LOCK_CONFLICT",
  "message": "...", "traceId": "a3f..." }
```

`ResponseBodyAdvice<Object>` 自動包裝所有 Controller 回傳，20+ endpoint 零入侵。MDC `traceId` 同時寫進 log 與 response header，方便跨服務追蹤。

---

### 6. 整合測試（M8）— Testcontainers

13 支測試，用真實 PostgreSQL 16 alpine container：

| 測試類 | 涵蓋重點 |
|---|---|
| `BancassuranceApplicationTests` | Context smoke test，驗 Flyway DDL |
| `PolicyOptimisticLockConcurrencyTest` ★ | `CountDownLatch` 同步起跑，斷言「一勝一敗、version 只進 1」 |
| `PolicyChangeIdempotencyTest` | 同 key replay / 同 key 不同 body 422 / 無 key 正常執行 |
| `PolicyChangeNegativeTest` | 412 / 409 / 422 / 404 / 400 共 8 個反向 case |

static `@ServiceConnection` PostgreSQLContainer — 整個 JVM 共享一個 container，ContextCache 命中，比每個 test class 重啟省 ~6x 時間。

---

## 🚀 快速啟動

```bash
# 1. 前置：JDK 21、Maven、Docker Desktop
java --version   # 須為 21.x
docker info

# 2. 啟動 PostgreSQL
docker compose up -d

# 3. 編譯並啟動
./mvnw spring-boot:run

# 4. 驗證
curl http://localhost:8080/api/health

# 5. Swagger UI
open http://localhost:8080/swagger-ui/index.html

# 6. 跑所有整合測試（須確認 Docker daemon 在跑）
./mvnw test
```

---

## 📡 API 總覽

### 核保（Underwriting）

| Method | Path | 說明 |
|---|---|---|
| POST | `/api/underwriting/cases` | 新增核保案件（保戶送件） |
| GET | `/api/underwriting/cases` | 查詢清單（分頁） |
| GET | `/api/underwriting/cases/{id}` | 查單筆 |
| PATCH | `/api/underwriting/cases/{id}/claim` | 核保員認領案件 |
| PATCH | `/api/underwriting/cases/{id}/approve` | 核保通過 |
| PATCH | `/api/underwriting/cases/{id}/reject` | 核保退件 |
| PATCH | `/api/underwriting/cases/{id}/request-info` | 要求補件 |
| PATCH | `/api/underwriting/cases/{id}/resubmit` | 補件重送 |
| PATCH | `/api/underwriting/cases/{id}/withdraw` | 撤件 |
| GET | `/api/underwriting/cases/{id}/events` | 查審查歷史軌跡 |

### 保單（Policy）

| Method | Path | 說明 |
|---|---|---|
| GET | `/api/policies` | 查詢清單（動態條件 + 分頁） |
| GET | `/api/policies/{id}` | 查單筆（含 ETag 版本 header） |
| GET | `/api/policies/number/{policyNumber}` | 依保單號查詢 |
| PATCH | `/api/policies/{id}/address` | 變更通訊地址（樂觀鎖 + 冪等） |
| PATCH | `/api/policies/{id}/beneficiaries` | 變更受益人（集合替換） |
| PATCH | `/api/policies/{id}/payment-method` | 變更繳費方式 |
| GET | `/api/policies/{id}/changes` | 查變更歷史（JSONB 稽核） |

---

## 📚 重要文件

| 文件 | 說明 |
|---|---|
| `docs/PLAN.md` | 完整學習路線圖與面試考點 |
| `docs/M17_M18_SMOKE_TEST.md` | 前端整合驗收：核保管理頁 + 保單查詢/變更頁 |
| `docs/M3_SMOKE_TEST.md` | 核保狀態機 curl 驗證 + 面試話術 |
| `docs/M5_SMOKE_TEST.md` | 樂觀鎖 / 冪等性完整 demo 腳本 |
| `docs/M8_SMOKE_TEST.md` | Testcontainers 測試執行說明 |
| `INTERVIEW_QA.md` | 分級面試問答（本 README 旁邊） |

---

## 🎯 學習心得

這個專案最有價值的部分是 **M5 的「412 vs 409」設計**：

兩種錯誤都是「版本衝突」，但觸發點不同 —— 412 是「client 帶了舊版本來敲門，在應用層就被擋回」；409 是「兩個 client 都以為自己有最新版本，最後在 DB 寫入時才撞上」。分開處理讓維運人員能從監控指標讀出「是 client cache 問題」還是「真實併發過高需要換策略」。

把理論落地到實際的 curl demo 並用 `CountDownLatch` 寫自動化測試去「捕捉」這個瞬間，是這次最大的收穫。
