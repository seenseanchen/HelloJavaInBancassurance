# CLAUDE.md — HelloJavaInBancassurance

> 這份檔案是給 AI 助手 (Claude) 在每次對話中先讀取的「專案備忘」。
> 命名規範：Claude Code / Cowork 預設讀取 `CLAUDE.md`(大寫)，使用者口語上的「.claude.md」即此檔。

---

## 0. 每次新對話的標準流程 (Workflow)

> ⚠️ **這一節是給未來每一個對話開頭的 Claude 看的。請務必依序執行。**

### 0.1 開工前（先讀，再動）

1. **讀 `docs/PLAN.md`**：確認整體里程碑路線圖與每個 M 階段的學習要點 / 面試考點。
2. **讀本檔 §7「待辦進度」**：找出**第一個未打勾的項目**——那就是當前要做的階段。
3. **掃 `docs/` 其他相關 .md**：依當前階段挑讀（清單見 §9 文件索引）。
   - 環境問題 → `INSTALL.md`、`HELP.md`
   - 學習方法 / 面試話術 → `LEARNING_TIPS.md`
   - 已完成階段的驗證指令 → `M{N}_SMOKE_TEST.md`
4. **若使用者沒明說做哪個階段**：直接告知「目前正在 M{N}，準備開始 X」並徵詢確認。

### 0.2 進行中

- 遵守 §8「協作守則」：先解釋，再寫程式；annotation 要說副作用與常見坑；附面試題分級。
- 對應到 §4「編碼規範」：DTO/Entity 分離、`@Transactional`、`@Version`、稽核欄位等強制規則。
- 一個 milestone 一個段落，不要跨階段跳寫。

### 0.3 收尾（**重要：每次完成階段都要做**）

1. **產出該階段的冒煙測試文件** `docs/M{N}_SMOKE_TEST.md`，內容包含：
   - 編譯指令、啟動指令、curl 範例、預期回應、DB 驗證 SQL、完成檢查單
   - **curl 範例必須用 placeholder 變數**（不要寫死 host 與 path id），約定：
     - `{{host}}` → API base URL（範例值 `http://localhost:8080`）
     - `{{id}}` → URL path 上的識別子；依 endpoint 不同可能是 UUID、保單號、案件號等。在 curl 上方用註解註明該欄位實際代表什麼，例如 `# {{id}} = policyNumber，例如 BANK-LIFE-20260507-0001`
     - query string 上的具體值（`status=IN_FORCE` 等）保留具體值即可，那是「示範過濾條件」不是 resource identity
   - 文件開頭固定加一個「§0 變數約定」小節，列出 placeholder 表格 + shell `export` 範例（讓使用者用 `"$host"` / `"$id"` 直接跑 curl）
2. **更新本檔 §7「待辦進度」**：
   - 把剛完成的 `[ ]` 改為 `[x]`，後綴加上完成日期 (YYYY-MM-DD) 與一句話成果。
   - 若有「規劃中包含但這次沒做完」的子項，在該 milestone 下用 `  - 延後：...` 註記，避免被遺忘。
3. **若新增了重要設計決策**：補進 §6 ADR 表格。
4. **若新增了 docs/ 文件**：補進 §9 文件索引。

---

## 1. 專案目標

學習者 Sean (livebreeze@gmail.com) 正在準備**銀行/人壽保險業的 Java 後端工程師**面試。
本專案以「人壽 + 銀保通路 (Bancassurance)」為情境，透過實作兩個關鍵業務功能，深入理解 Java 與 Spring Boot 在金融業的實務應用。

### 業務功能 (Domain)

1. **人壽審查契約 (Underwriting / Contract Review)**
   - 客戶投保 → 業務員送件 → 核保員審查 → 通過 / 退件 / 補件
   - 重點：狀態機 (State Machine)、多角色權限、稽核軌跡 (Audit Log)

2. **保單查詢與變更 (Policy Inquiry & Endorsement)**
   - 查詢保單明細、受益人、保額、繳費狀態
   - 變更：地址、受益人、繳費方式、保額調整
   - **重點：交易控制 (Transaction)、樂觀鎖 (Optimistic Locking)、冪等性 (Idempotency)、稽核**

### 業務情境延伸 — 保險電商 (Insurance e-Commerce)

商品上下架、報價試算、線上投保、訂單建立 — 後續可延伸的模組。

---

## 2. 技術棧 (Tech Stack)

| 類別 | 選用 | 為什麼 |
|---|---|---|
| 語言 | Java 21 LTS | 業界主流 LTS、支援 record / sealed class / virtual thread |
| 框架 | Spring Boot 4.0.x | 2025/Q4 釋出的最新主版本，要求 Java 17+；多數銀行 in-house 還在 3.3.x，學 4.0 同時對比差異 |
| Build | Maven | 金融業最常見、`pom.xml` 結構穩定 |
| ORM | Spring Data JPA + Hibernate | 標準解，搭配交易控制 |
| DB | PostgreSQL 16 (Docker) | 開源、ACID、銀行 POC 常用 |
| Migration | Flyway | 版控 DDL，金融業必備 |
| API Doc | springdoc-openapi (Swagger UI) | 自動產生，可匯入 Postman |
| 工具庫 | Lombok、MapStruct | 減少樣板程式碼、DTO ↔ Entity 安全轉換 |
| 驗證 | Jakarta Bean Validation | `@NotNull`, `@Valid` |
| 安全 | Spring Security (Basic Auth → JWT) | 後期再加 |
| 測試 | JUnit 5 + Mockito + Testcontainers | 業界標準 |

---

## 3. 專案結構 (Package by Feature)

```
src/main/java/com/sean/bancassurance/
├── BancassuranceApplication.java
├── common/                      # 共用元件
│   ├── exception/               # 全域例外處理
│   ├── audit/                   # AuditorAware、稽核欄位
│   └── config/                  # OpenAPI、Jackson、Security 等設定
├── underwriting/                # 人壽審查契約
│   ├── api/                     # Controller + DTO
│   ├── domain/                  # Entity、Enum、領域服務
│   ├── repository/
│   └── service/
└── policy/                      # 保單查詢與變更
    ├── api/
    ├── domain/
    ├── repository/
    └── service/

src/main/resources/
├── application.yml
├── application-local.yml
└── db/migration/                # Flyway: V1__init.sql, V2__...
```

> **Package by Feature 而非 by Layer**：每個業務領域自成一包。
> 銀行業專案動輒上百個 Controller，依功能分包能避免「Controller 包 1000 個 class」的災難。

---

## 4. 編碼規範 (Coding Conventions)

- **DTO 與 Entity 嚴格分離**：API 層只回傳 DTO，禁止把 JPA Entity 直接序列化到 JSON。
- **Service 標註 `@Transactional`**：寫操作的方法必須有交易；查詢方法用 `@Transactional(readOnly = true)`。
- **Entity 使用 `@Version` 樂觀鎖**：保單變更必加，避免 lost update。
- **不用 `@Autowired` field injection**：一律用 constructor injection (Lombok `@RequiredArgsConstructor`)。
- **不丟 `RuntimeException`**：自訂業務例外 (`PolicyNotFoundException`, `IllegalStateTransitionException`)，由 `@RestControllerAdvice` 統一翻成 HTTP status。
- **稽核欄位**：每張表都要有 `created_at`, `created_by`, `updated_at`, `updated_by`，金融業必備。
- **時間一律用 `Instant` / `LocalDateTime` (UTC)**：禁用 `java.util.Date`。
- **金額一律用 `BigDecimal`**：禁用 `double` / `float`，浮點數誤差在金融業是大忌。

---

## 5. 學習者背景

- Sean 有 Java 基礎語法經驗，**沒寫過 Spring Boot**。
- 工作環境：macOS (Apple Silicon)。
- 偏好：Maven、Docker、Swagger/OpenAPI、Postman。

### 教學風格約定

1. **每段程式碼都要解釋「為什麼這樣寫」**，而非僅展示語法。
2. **對應到面試題**：每導入一個概念，補充「面試官可能問的延伸題」。
3. **對比 Java 寫法 vs Spring 簡化後的寫法**，理解框架幫忙做了什麼。
4. **金融業情境舉例**：例如解釋 `@Transactional` 時用「轉帳扣款 + 入帳」案例。

---

## 6. 重要決策紀錄 (ADR — Architecture Decision Record)

| 決策 | 選擇 | 替代方案 | 為何選此 |
|---|---|---|---|
| Java 版本 | 21 LTS | 17 LTS | 21 是更新的 LTS，銀行新案逐漸採用，virtual thread 是亮點 |
| 交易隔離 | `READ_COMMITTED` (PG 預設) | `SERIALIZABLE` | 預設能滿足保單變更，極端衝突再升級 |
| 並行控制 | 樂觀鎖 (`@Version`) | 悲觀鎖 (`SELECT FOR UPDATE`) | 銀行寫入頻率不算極高，樂觀鎖效能較好；高衝突場景再切悲觀鎖 |
| ID 策略 | UUID v7 (時間排序) 或 BIGINT identity | 單純 UUID v4 | UUID 對外不洩漏序號 (商業機密)，v7 仍可索引友善 |
| 軟刪除 | `is_deleted` flag + `@Where` | 真實刪除 | 金融業稽核要求保留歷史 |

---

## 7. 待辦進度 (對話間追蹤用)

> 完成一個階段時：把 `[ ]` 改成 `[x]`，後接 `(YYYY-MM-DD) 一句話成果`。
> 若有部分子項延後，在該行下用 `  - 延後：...` 標明。

- [x] **M0 環境建置** (JDK 21 / IntelliJ / Docker / PostgreSQL 16)
  - 完成：`docker-compose.yml` 啟得起 PG 16；`./mvnw -v` 指向 JDK 21
- [x] **M1 Spring Boot 骨架 + Hello World API** (2026-05-05)
  - 完成：`GET /api/health` 回 `{status: UP, ...}`；專案能用 `./mvnw spring-boot:run` 啟動
- [x] **M2 Domain Model — 人壽審查契約 (CRUD)** (2026-05-06)
  - 完成：`UnderwritingCase` Entity、6 種狀態 Enum、Flyway V1 DDL、JPA Auditing、Repository/Service/Controller、`/api/underwriting/cases` 四支 API、全域例外處理、`docs/M2_SMOKE_TEST.md`
  - 延後：`Applicant` / `MedicalDeclaration` / `CaseAttachment` 三張子表（M3 或之後依需要拆）
  - 註：實際採用的狀態為 `SUBMITTED / UNDER_REVIEW / PENDING_INFO / APPROVED / REJECTED / WITHDRAWN`，與 PLAN.md 早期草稿略有差異 — 以本檔為準
- [x] **M3 狀態機與業務規則 — 核保流程** (2026-05-06)
  - 完成：`UnderwritingStatus` 加表驅動狀態機 (`canTransitionTo` / `nextStates` / `isTerminal`)；6 個 transition endpoint (`claim` / `request-info` / `resubmit` / `approve` / `reject` / `withdraw`)；`underwriting_case_event` 稽核表 (V2 migration，含 backfill)；`IllegalStateTransitionException` → 409；`OptimisticLockingFailureException` handler → 409；`GET /cases/{id}/events` 列出歷史軌跡；`docs/M3_SMOKE_TEST.md`
  - 設計選擇：表驅動 (EnumMap) > 策略模式 / Spring StateMachine（規則穩定、6 個動作不需要每個一個 class）
- [x] **M4 上半 — 保單查詢 (Entity + 查詢 API)** (2026-05-07)
  - 完成：`Policy` / `Beneficiary` Entity + `PolicyStatus` / `PremiumPaymentMethod` / `BeneficiaryRelationship` 三個 enum；Flyway V3 (policy + policy_beneficiary 表，含索引/CHECK/comment) + V4 (5 筆種子 + 6 筆受益人)；`PolicyRepository` 同時 extends `JpaRepository` + `JpaSpecificationExecutor`，並示範 method naming / `@Query` JPQL JOIN FETCH 兩種寫法；`PolicySpecifications` 動態查詢工廠；`PolicyService` (class 級 `@Transactional(readOnly=true)`)；`PolicyController` 三支 GET API；`PolicyResponse` (含 beneficiaries) / `PolicySummaryResponse` (列表精簡) 雙 DTO 避免 N+1；身分證遮罩；`docs/M4_SMOKE_TEST.md`
  - 設計選擇：list / detail 分兩個 DTO、單筆查走 JOIN FETCH、`@OneToMany` 一律 LAZY + `cascade=ALL` + `orphanRemoval=true`；`@Version` 欄位先預埋給 M5 用
  - 延後：完整核保→保單關聯 (V5 加 FK)、Applicant/Insured 切獨立 customer 表 (M10 之後再考慮)
- [x] **M5 保單變更 + 樂觀鎖 + `@Transactional`** (2026-05-07)
  - 完成：三支 PATCH endpoint (`/api/policies/{id}/address` / `/beneficiaries` / `/payment-method`) + `GET /api/policies/{id}/changes` 變更歷史；`PolicyChangeService` 把 `@Transactional`、`@Version` 樂觀鎖 (saveAndFlush)、`If-Match` header (412) 與 OptimisticLockingFailureException (409) 區分、Idempotency-Key 全套 (request_hash 防重用 + replay)、業務規則 (受益人加總=100 / priority=1 / SINGLE_PAY 禁回頭)、JSONB 變更稽核 (`policy_change_log`) 全部串起來；GET 單筆保單回應加 `ETag` header
  - V5 migration：`policy_change_log` (JSONB before/after snapshot, append-only) + `idempotency_record` (PK = key, 24h TTL)；新例外 `PreconditionFailedException` (412) / `IllegalPolicyStateException` (409) / `BusinessRuleViolationException` (422) + GlobalExceptionHandler 對應 handler；`PolicyRepository.findByIdForUpdate` (`LockModeType.PESSIMISTIC_WRITE`) 純對比教學用，service 預設樂觀鎖路徑
  - 設計選擇：(1) 集合替換 > 增量 PATCH (協議簡單)；(2) 樂觀鎖 > 悲觀鎖 (銀保場景衝突低)；(3) audit log 與主交易共生死 (不用 REQUIRES_NEW)；(4) Hibernate 6 `@JdbcTypeCode(SqlTypes.JSON)` 接 JSONB；(5) `clear() + addAll()` 而非 `setBeneficiaries(...)` 才能觸發 orphanRemoval；(6) 每個變更類型一個 sub-resource (URL 自帶語意)
  - 延後：actor 仍從 `X-Actor` header 取，M9 接 Spring Security 後改 SecurityContext；idempotency TTL GC job 不寫；`policy.underwriting_case_id` 仍 nullable (M10 才補 FK)
  - **驗證代辦**：sandbox 沒法跑 mvnw，請在本機執行 `./mvnw -q -DskipTests compile` 確認；新檔的 `@JdbcTypeCode(SqlTypes.JSON)` 需 Hibernate 6+ (Spring Boot 4.0.x 自帶 6.6+，OK)
- [x] **M6 全域例外處理 + 統一回應格式** (2026-05-07)
  - 完成：`ApiResponse<T>` record (code/message/data/traceId)；`TraceIdFilter`（`OncePerRequestFilter`，MDC 寫入 + 清除，支援外部 X-Trace-Id 複用）；`ApiResponseWrapper`（`ResponseBodyAdvice<Object>`，自動包裝所有 Controller 回傳，ApiError/ApiResponse/null/String 放行）；`ApiError` 加 traceId 欄位；`GlobalExceptionHandler` 重構提取 `buildError()` + `traceHeader()` 工具方法；`application.yml` logging pattern 加 `[traceId=%X{traceId:-no-trace}]`；`docs/M6_SMOKE_TEST.md`
  - 設計選擇：(1) ResponseBodyAdvice 自動包 > Controller 手動改（20+ endpoint 零入侵）；(2) 錯誤回應 ApiError 不包成 ApiResponse（扁平格式，前端解析更直覺）；(3) MDC finally 清理防 thread pool 污染；(4) 外部 X-Trace-Id header 優先複用（Distributed Tracing 基礎）
- [x] **M7 OpenAPI / Swagger UI 整合** (2026-05-07)
  - 完成：`pom.xml` 加 `springdoc-openapi-starter-webmvc-ui:2.8.9`；`OpenApiConfig`（API Info/版本/聯絡/servers/BasicAuth SecurityScheme 佔位）；`UnderwritingCaseController` / `PolicyController` / `PolicyChangeController` 全部加 `@Tag` / `@Operation` / `@ApiResponse` / `@Parameter` 注解；`application.yml` 加 springdoc 路徑設定（`/swagger-ui`、`/api-docs`）與 UI 排序設定；`docs/M7_SMOKE_TEST.md`
  - 設計選擇：(1) Swagger UI 路徑改為 `/swagger-ui`（預設 `/swagger-ui.html` 較長）；(2) API-docs 路徑 `/api-docs`（方便 Postman 匯入記憶）；(3) SecurityScheme 先宣告 BasicAuth 佔位，M9 接 JWT 再換 BearerAuth；(4) `ResponseBodyAdvice` 包裝後 springdoc 顯示內層 DTO schema，在 API 總覽 description 加說明補充
  - 延後：`@Schema` annotation 到 DTO record 欄位（example 值、description）— M8 之後有空再補；springdoc OperationCustomizer 自動把 ApiResponse 包裝反映到 schema 屬於進階
- [x] **M8 整合測試 (Testcontainers)** (2026-05-07)
  - 完成：pom.xml 加 spring-boot-testcontainers + testcontainers junit-jupiter + postgresql；`application-test.yml`（demo sleep=0、log 安靜）；`IntegrationTestBase` 抽象基類（`@SpringBootTest` + `@Testcontainers` + `@ServiceConnection` PostgreSQLContainer，static 共享 — 整個 JVM 起一份）；4 支測試類共 13 個 `@Test`：
      - `BancassuranceApplicationTests`（context smoke test，已切回繼承 IntegrationTestBase）
      - `PolicyOptimisticLockConcurrencyTest` ★ 旗艦 — `@TestPropertySource` 開 demo sleep 500ms + `CountDownLatch` 同步起跑，斷言「一勝一敗 + version 只進一次 + change_log 只 1 筆」
      - `PolicyChangeIdempotencyTest` — 同 key 同 body / 同 key 不同 body / 沒 key 三組對照
      - `PolicyChangeNegativeTest` — 412 / 409 / 422 / 404 / 400 五種錯誤碼共 7 個 case
  - 設計選擇：(1) static `@ServiceConnection` 不用 `@Container` → JVM 共享一個 container 不重啟（速度提升 6×）；(2) 樂觀鎖測試「不」標 `@Transactional` — service 的 TX 必須真實 commit 才驗得到衝突；(3) 不同測試類用「不同保單編號」當測試隔離單位（policy 1/2/3/4），@AfterEach 清自家寫進去的稽核紀錄；(4) `@TestPropertySource` 只給 1 支 class 用，其他共享 ContextCache 避免 build 多份 context；(5) 反向案例用 MockMvc + jsonPath，併發案例直接注入 service（控制 thread 時序）
  - 延後：`@DataJpaTest` Repository slice、`@WebMvcTest` Controller slice — 中階範圍未涵蓋；M3 狀態機 409 整合測試（既有 M3_SMOKE_TEST.md curl 驗過，沒寫成自動化）
  - **驗證代辦**：sandbox 沒法跑 mvnw + Docker，請在本機執行：
      1. `docker info` 確認 Docker daemon 起來
      2. `./mvnw -q -DskipTests compile` 先確認新依賴下載成功
      3. `./mvnw test` 全部測試（第一次會 docker pull `postgres:16-alpine` ~80MB，~30s）
      4. 期望 `Tests run: 13, Failures: 0, Errors: 0`
- [ ] M9 (選配) Spring Security + JWT ← **下一個（選配）**
- [ ] M10 (選配) 商品上下架 / 線上投保

---

## 8. 給 AI 助手的協作守則

- **先解釋，再寫程式**。Sean 是來學的，不是來收交付的。
- 每次新增檔案，列出該檔在「分層架構」中的角色。
- 提到任何 annotation (e.g. `@Transactional`, `@Entity`)，**都要說明它的副作用與常見坑**。
- 引用面試題時，標註「初級 / 中級 / 資深」難度分級。
- 不要一次倒太多程式碼進來，**每次以一個 milestone 為單位**，等 Sean 跑得起來再往下。
- **每完成一個階段，務必執行 §0.3「收尾」步驟**：產出 `M{N}_SMOKE_TEST.md` + 更新本檔 §7 進度。

---

## 9. 文件索引 (`docs/`)

> 每次新對話依需要挑讀；別一次全部讀完浪費 context。

| 檔案 | 何時讀 | 用途 |
|---|---|---|
| `docs/PLAN.md` | **每次對話開頭必讀** | 全景路線圖、每個 M 階段的學習要點與面試考點、衝刺時程 |
| `docs/INSTALL.md` | M0 階段、環境出問題時 | macOS 開發環境建置 (SDKMAN / JDK 21 / Maven / Docker) |
| `docs/HELP.md` | 找 Spring Boot 4.0.x 官方文件連結時 | start.spring.io 自動產生的參考連結 |
| `docs/LEARNING_TIPS.md` | 學習方法卡關時、整理面試話術時 | 費曼學習法、每階段該口頭講出來的版本 |
| `docs/M2_SMOKE_TEST.md` | 驗證 M2 / 回顧核保 CRUD API | 本機編譯/啟動指令、curl 範例、完成檢查單 |
| `docs/M3_SMOKE_TEST.md` | 驗證 M3 / 回顧狀態機與事件軌跡 | 完整正向流程 + 非法跳轉 / 樂觀鎖 等反向案例 + 面試話術 |
| `docs/M4_SMOKE_TEST.md` | 驗證 M4 上半 / 回顧保單查詢 | 三支 GET API curl 範例、JOIN FETCH N+1 觀察、`@OneToMany` / Specification / Pageable 面試話術 |
| `docs/M5_SMOKE_TEST.md` | 驗證 M5 / 回顧樂觀鎖與冪等性 | 三支 PATCH 完整正向流程 + 412/409/422 三種反向案例 + 兩支 curl 同時撞鎖示範 + Idempotency-Key replay + `@Transactional` / 樂觀鎖 vs 悲觀鎖 / 412 vs 409 / 冪等併發處理 等資深面試話術 |
| `docs/M6_SMOKE_TEST.md` | 驗證 M6 / 回顧統一回應格式 | ApiResponse 包裝驗證、traceId log/header 確認、錯誤回應帶 traceId、面試話術 |
| `docs/M7_SMOKE_TEST.md` | 驗證 M7 / 回顧 OpenAPI 整合 | Swagger UI 可用、api-docs JSON、Postman 匯入、@Operation/@Tag 說明、面試話術 |
| `docs/M8_SMOKE_TEST.md` | 驗證 M8 / 回顧整合測試 | `./mvnw test` 全綠、四個測試類角色、Testcontainers vs H2 / 樂觀鎖併發測試方法 / ContextCache 等資深面試話術 |
| `docs/M{N}_SMOKE_TEST.md` | 每個階段完成時新增 | 該階段的編譯/啟動/curl/SQL 驗證；新對話進來能快速確認狀態 |
