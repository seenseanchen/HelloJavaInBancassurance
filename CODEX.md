# CODEX.md — HelloJavaInBancassurance

> 這份檔案是給 AI 助手 (Codex) 在每次對話中先讀取的「專案作業指令」。
> 目的：確保每次協作都遵守同一套流程、教學節奏、編碼規範與收尾標準。

---

## 0. 每次新對話標準流程 (Workflow)

### 0.1 開工前（先讀，再動）

1. 先讀 `docs/PLAN.md`：確認目前整體里程碑、學習目標與面試考點。
2. 讀本檔 §7「待辦進度」：找出第一個尚未完成 (`[ ]`) 的 milestone，作為預設當前任務。
3. 依當前任務補讀 `docs/` 相關文件（見 §9 文件索引）。
   - 環境/安裝問題：`docs/INSTALL.md`、`docs/HELP.md`
   - 學習方法與面試話術：`docs/LEARNING_TIPS.md`
   - 已完成階段驗證：`docs/M{N}_SMOKE_TEST.md`
4. 若使用者未指定階段，主動回報：
   - 目前位於哪個 milestone（M{N}）
   - 準備執行的下一步
   - 再請使用者確認或調整

### 0.2 進行中

- 嚴格遵守 §8「協作守則」：先教學解釋、再實作。
- 嚴格遵守 §4「編碼規範」：DTO/Entity 分離、交易、樂觀鎖、稽核欄位等。
- 一次只處理一個 milestone 範圍，不跨階段跳做。

### 0.3 收尾（每完成一個階段必做）

1. 產出該階段冒煙測試文件：`docs/M{N}_SMOKE_TEST.md`
2. 文件必備內容：
   - 編譯與啟動指令
   - `curl` 請求範例
   - 預期回應
   - DB 驗證 SQL
   - 完成檢查清單
3. `curl` 變數規範（禁止寫死 host 與 path id）：
   - `{{host}}`：API base URL（例：`http://localhost:8080`）
   - `{{id}}`：URL 路徑識別子（UUID/保單號/案件號等）；需在範例前註解說明實際語意
   - query string（如 `status=IN_FORCE`）可保留具體值
4. 文件開頭固定加「§0 變數約定」：
   - placeholder 對照表
   - shell `export` 範例（可直接用 `"$host"` / `"$id"`）
5. 更新本檔 §7「待辦進度」：
   - 將對應項目從 `[ ]` 改為 `[x]`
   - 補完成日期 `YYYY-MM-DD`
   - 補一句成果摘要
   - 未完成子項以 `- 延後：...` 註記
6. 若新增重大設計決策，更新 §6 ADR。
7. 若新增 docs 文件，更新 §9 文件索引。

---

## 1. 專案目標

本專案用「人壽 + 銀保通路 (Bancassurance)」情境，協助學習者 Sean 準備銀行/人壽保險業 Java 後端面試與實作能力。

### 1.1 主要業務模組

1. 人壽審查契約 (Underwriting / Contract Review)
   - 投保送件、核保審查、通過/退件/補件
   - 重點：狀態機、多角色權限、稽核軌跡
2. 保單查詢與變更 (Policy Inquiry & Endorsement)
   - 查詢保單明細、受益人、保額、繳費狀態
   - 變更地址、受益人、繳費方式、保額
   - 重點：交易控制、樂觀鎖、冪等性、稽核

### 1.2 後續可延伸

保險電商（商品上下架、試算、線上投保、訂單）。

---

## 2. 技術棧基線

- Java：21 LTS
- Framework：Spring Boot 4.0.x
- Build：Maven
- ORM：Spring Data JPA + Hibernate
- DB：PostgreSQL 16（Docker）
- Migration：Flyway
- API 文件：springdoc-openapi (Swagger UI)
- 工具：Lombok、MapStruct
- 驗證：Jakarta Bean Validation
- 安全：Spring Security（Basic Auth → JWT）
- 測試：JUnit 5 + Mockito + Testcontainers

---

## 3. 專案結構原則

採用 **Package by Feature**，而非純 Layer 分包。

- `underwriting/`：核保領域
- `policy/`：保單領域
- `common/`：跨域共用（exception/audit/config）

禁止把所有 controller/service/repository 全塞到單一共用層級。

---

## 4. 編碼規範 (必遵守)

1. DTO 與 Entity 嚴格分離：API 回 DTO，禁止直接回 JPA Entity。
2. Service 寫操作必有 `@Transactional`；查詢用 `@Transactional(readOnly = true)`。
3. 變更流程需使用 `@Version`（樂觀鎖）防 lost update。
4. 禁用 field injection：不用 `@Autowired` 欄位注入，一律 constructor injection。
5. 禁丟裸 `RuntimeException`：使用業務例外 + `@RestControllerAdvice` 統一轉 HTTP。
6. 所有核心表要有稽核欄位：`created_at`, `created_by`, `updated_at`, `updated_by`。
7. 時間型別用 `Instant` / `LocalDateTime`（UTC）；禁用 `java.util.Date`。
8. 金額用 `BigDecimal`；禁用 `double` / `float`。

---

## 5. 教學與互動模式

學習者背景：有 Java 基礎，尚未實戰 Spring Boot。

Codex 回應要求：

1. 每段程式碼都要解釋「為什麼這樣寫」。
2. 每個概念附對應面試延伸題。
3. 盡量對比「純 Java 寫法」與「Spring 寫法」，說明框架代勞內容。
4. 優先使用金融業案例解釋（如交易、稽核、一致性）。

---

## 6. ADR 準則（重要決策需記錄）

遇到下列情況要更新 ADR：

- 版本與框架級決策（Java/Spring/DB）
- 交易隔離級別變更
- 並行控制策略切換（樂觀鎖 ↔ 悲觀鎖）
- ID 策略變更
- 軟刪除/資料保存策略變更

記錄格式：決策、替代方案、取捨理由、影響範圍。

---

## 7. 待辦進度（唯一進度來源）

> 規則：
> - 完成後改 `[x]` + 日期 + 一句成果
> - 未完成子項標 `- 延後：...`

- [x] M0 環境建置
- [x] M1 Spring Boot 骨架 + Hello World API
- [x] M2 Domain Model — 人壽審查契約 (CRUD)
- [x] M3 狀態機與業務規則 — 核保流程
- [x] M4 上半 — 保單查詢 (Entity + 查詢 API)
- [x] M5 保單變更 + 樂觀鎖 + `@Transactional`
- [x] M6 全域例外處理 + 統一回應格式
- [x] M7 OpenAPI / Swagger UI 整合
- [x] M8 整合測試 (Testcontainers)
- [x] M9 (選配) Spring Security + JWT (2026-05-11，JWT + RBAC + Swagger BearerAuth + M9 smoke test)
- [ ] M10 (選配) 商品上下架 / 線上投保
- [x] M11 Nginx 反向代理 (2026-05-11，infra reverse proxy 已完成)
- [ ] M12 OpenTelemetry + Jaeger + Prometheus + Grafana
- [ ] M13 ELK / EFK (集中化日誌)
- [ ] M14 Jenkins CI/CD
- [ ] M15 n8n 工作流自動化
- [x] M16 Vue 骨架 + Design Token + Login Flow (2026-05-12，pnpm + Tailwind/Element Plus + JWT-first login + mock fallback + route guard + M16 smoke test)
- [ ] M17 核保案件管理頁
- [ ] M18 保單查詢與變更頁

執行預設：若使用者未另行指定，從第一個未完成項目開始（目前是 M9）。

---

## 8. 協作守則（Codex 行為約束）

1. 先解釋，再寫程式。
2. 每次新增檔案時，說明其在架構分層中的角色。
3. 任何 annotation（如 `@Transactional`, `@Entity`）都需補充：
   - 副作用
   - 常見坑
4. 面試題需標註難度：初級 / 中級 / 資深。
5. 每次只推進一個 milestone，避免一次灌入大量程式碼。
6. 每個階段完成後，必做 §0.3 收尾（smoke test + 進度更新）。

---

## 9. 文件索引（`docs/` 挑讀原則）

- `docs/PLAN.md`：每次對話必讀。
- `docs/INSTALL.md`：環境建置與排錯。
- `docs/HELP.md`：官方文件入口。
- `docs/LEARNING_TIPS.md`：學習法與面試話術。
- `docs/M2_SMOKE_TEST.md` ~ `docs/M8_SMOKE_TEST.md`：已完成里程碑驗證與回顧。
- `docs/M{N}_SMOKE_TEST.md`：每個新完成階段都要新增。

預留文件（後續階段）：
- `docs/M11_SMOKE_TEST.md` ~ `docs/M18_SMOKE_TEST.md`
- `docs/UI_STYLE.md`
- `docs/INFRA_OVERVIEW.md`

---

## 10. 執行優先順序（未指定任務時）

1. 先確認 §7 第一個未完成 milestone。
2. 讀對應 docs，整理本次目標。
3. 先給使用者「本次要做什麼 + 為什麼」。
4. 進行最小可驗證實作（small batch）。
5. 提供可直接執行的驗證步驟。
6. 完成後做 §0.3 收尾。
