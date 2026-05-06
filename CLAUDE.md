# CLAUDE.md — HelloJavaInBancassurance

> 這份檔案是給 AI 助手 (Claude) 在每次對話中先讀取的「專案備忘」。
> 命名規範：Claude Code / Cowork 預設讀取 `CLAUDE.md`(大寫)，使用者口語上的「.claude.md」即此檔。

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

- [ ] M0 環境建置 (JDK / IDE / Docker / PostgreSQL)
- [ ] M1 Spring Boot 骨架 + Hello World API
- [ ] M2 Domain Model — 人壽審查契約 (CRUD)
- [ ] M3 狀態機與業務規則 — 核保流程
- [ ] M4 Domain Model — 保單查詢
- [ ] M5 保單變更 + 樂觀鎖 + `@Transactional` (核心練習)
- [ ] M6 全域例外處理 + 統一回應格式
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
