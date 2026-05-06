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
- [ ] **M3 狀態機與業務規則 — 核保流程** ← **下一個**
- [ ] M4 Domain Model — 保單查詢
- [ ] M5 保單變更 + 樂觀鎖 + `@Transactional` (核心練習)
- [ ] M6 全域例外處理 + 統一回應格式
  - 註：M2 已先做了基本版 (`GlobalExceptionHandler` + `ApiError`)，M6 主要是「統一回應結構 + traceId/MDC」
- [ ] M7 OpenAPI / Swagger UI 整合
- [ ] M8 整合測試 (Testcontainers)
- [ ] M9 (選配) Spring Security + JWT
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
| `docs/M{N}_SMOKE_TEST.md` | 每個階段完成時新增 | 該階段的編譯/啟動/curl/SQL 驗證；新對話進來能快速確認狀態 |
