# PLAN.md — 學習與開發計畫

## 一、學習路徑全景圖

```
M0 環境  ──►  M1 骨架  ──►  M2 領域 (核保)  ──►  M3 狀態機
                                                    │
                                                    ▼
M8 測試  ◄──  M7 Swagger  ◄──  M6 例外處理  ◄──  M4 領域 (保單)
                                                    │
                                                    ▼
                                                 M5 保單變更 (核心)
```

> M5 是整個專案的高潮 — 交易控制、樂觀鎖、冪等性、稽核全部出場，也是面試最常被深挖的環節。

---

## 二、里程碑詳細表 (含學習要點 / 面試考點)

### M0 — 環境建置
- 安裝 JDK 21、IntelliJ IDEA Community、Maven、Docker Desktop
- 用 docker-compose 跑 PostgreSQL
- **學會**：JDK / JRE / JVM 差別；`JAVA_HOME` 環境變數
- **面試題**：「JVM 記憶體模型有哪些區域？」「為何選 Java 21 而不是 8？」

### M1 — Spring Boot 骨架
- 用 [start.spring.io](https://start.spring.io) 產生專案
- 依賴：`spring-boot-starter-web`, `data-jpa`, `validation`, `flyway`, `postgresql`, `lombok`, `springdoc-openapi-starter-webmvc-ui`
- 寫第一支 `GET /api/health` API
- **學會**：`@SpringBootApplication` 包含什麼、Auto-Configuration 原理
- **面試題**：「Spring Boot 跟 Spring 差在哪？」「`@ComponentScan` 預設掃描範圍？」

### M2 — Domain Model (人壽審查契約)
- Entity：`UnderwritingCase`, `Applicant`, `MedicalDeclaration`, `CaseAttachment`
- 列舉：`UnderwritingStatus { SUBMITTED, REVIEWING, APPROVED, REJECTED, REQUEST_INFO }`
- Repository、Service、Controller
- API：建立送件、查詢清單、查單筆、上傳健告
- **學會**：JPA `@Entity` / `@OneToMany` / `@ManyToOne` / Cascade / Lazy vs Eager
- **面試題**：「N+1 問題是什麼？怎麼解？」「`@OneToMany` 預設 fetch type？」

### M3 — 狀態機與業務規則
- 狀態流轉：`SUBMITTED → REVIEWING → (APPROVED | REJECTED | REQUEST_INFO)`
- 不允許的狀態跳轉要丟 `IllegalStateTransitionException`
- 加上稽核：誰在何時把案件改成哪個狀態
- **學會**：用 `enum` 內建方法或 Spring StateMachine 實作；策略模式 (Strategy Pattern)
- **面試題**：「業務狀態怎麼防止非法跳轉？」「if-else 寫了 100 行怎麼重構？」

### M4 — Domain Model (保單)
- Entity：`Policy`, `PolicyHolder`, `Insured`, `Beneficiary`, `Premium`
- 查詢 API：`GET /api/policies/{id}`、`GET /api/policies?holderId=...`
- 分頁：`Pageable` + `Page<T>`
- **學會**：DTO Projection、`@Query` JPQL、Specification 動態查詢
- **面試題**：「分頁查詢怎麼避免深分頁問題 (deep pagination)？」

### M5 — 保單變更 (核心練習)
這個 milestone 會獨立做完，是面試最會被問的部分。

- API：`PATCH /api/policies/{id}/beneficiary`
- 流程：
  1. 客戶送出變更請求 (帶 `If-Match: ETag` 或 `version` 欄位)
  2. Service 層 `@Transactional`
  3. 查 Policy → 檢核業務規則 → 寫入 `PolicyChangeLog` → 更新 Policy
  4. 樂觀鎖衝突 → 回 `409 Conflict`
- **學會**：
  - `@Transactional` 的 propagation / isolation / rollbackFor
  - 樂觀鎖 `@Version` vs 悲觀鎖 `LockModeType.PESSIMISTIC_WRITE`
  - 冪等性鍵 (Idempotency Key)
  - 為何不能用 `try/catch` 把 SQLException 吞掉
- **面試題 (高頻)**：
  - 「`@Transactional` 在同一個 class 內呼叫會生效嗎？為什麼？」
  - 「樂觀鎖跟悲觀鎖怎麼選？」
  - 「`Propagation.REQUIRES_NEW` 跟 `REQUIRED` 差在哪？」
  - 「如果第三方 API 在交易中呼叫失敗，怎麼處理？」(分散式交易、Saga、Outbox Pattern)

### M6 — 全域例外處理 + 統一回應格式
- `@RestControllerAdvice` + `@ExceptionHandler`
- 統一回應結構 `ApiResponse<T> { code, message, data, traceId }`
- 用 MDC 寫 traceId 進 log，方便跨服務追蹤
- **面試題**：「線上 API 出錯怎麼定位？」「Log level 怎麼分？」

### M7 — OpenAPI / Swagger
- 加 `springdoc-openapi-starter-webmvc-ui`
- 啟動後存取 `http://localhost:8080/swagger-ui/index.html`
- 從 Swagger UI 匯出 JSON → 匯入 Postman
- **面試題**：「API 怎麼版本化？(URL versioning vs Header versioning)」

### M8 — 整合測試
- `@SpringBootTest` + Testcontainers (真的起 PostgreSQL)
- 測「保單變更在併發下會不會 lost update」
- **學會**：單元測試 vs 整合測試；Mock 的時機；測試金字塔
- **面試題**：「為什麼要用 Testcontainers 而非 H2 跑測試？」

### M9 (選配) — 安全
- Spring Security 基本流程：Filter Chain、AuthenticationManager、UserDetailsService
- 加 JWT
- **面試題**：「Session vs JWT 差在哪？」「JWT 怎麼處理登出？」

### M10 (選配) — 商品上下架 / 線上投保
- 報價試算 → 投保訂單 → 付款狀態回拋 (Webhook)
- 引入 Outbox Pattern 模擬「跨系統最終一致性」

---

## 三、面試話術延伸

每個 milestone 完成後，建議用以下框架自我練習口頭說明：

```
情境 (Situation) → 任務 (Task) → 行動 (Action) → 結果 (Result)
```

例如 M5 的口頭答覆：
> 「在我做的銀保系統中(S)，需要做保單受益人變更(T)。
> 為了避免兩個業務員同時改同一張保單造成 lost update，
> 我用 JPA `@Version` 加樂觀鎖，並在 Service 用 `@Transactional` 保證原子性，
> 客戶端傳 `If-Match: version` 觸發 412 或 409(A)。
> 結果在壓測下沒有資料覆寫，且效能比悲觀鎖好約 30%(R)。」

---

## 四、預估時程 (Sean 實際排程：2 天 × 6 小時 = 12 小時)

> 衝刺模式 — 牲牲讀書計畫的整體性，換取「面試前能完整跑出 demo」。
> 重點：M5 是核心，**就算其他削，M5 不能削**。

### Day 1 (6h)

| 區段 | 時間 | 內容 | 產出 |
|---|---|---|---|
| 09:00–10:30 | 1.5h | **M0** 環境建置 + **M1** Spring Boot 骨架 | `GET /api/health` 跑通 |
| 10:30–12:30 | 2h | **M2** Underwriting Domain (Entity / Repository / Controller) | 4 支核保 CRUD API |
| 13:30–15:00 | 1.5h | **M3** 狀態機 (核保流程 + 非法跳轉防呆) | `PATCH /cases/{id}/transition` |
| 15:00–16:00 | 1h | **M4 上半** 保單查詢 (Entity + 查詢 API) | `GET /policies/{id}` |

### Day 2 (6h)

| 區段 | 時間 | 內容 | 產出 |
|---|---|---|---|
| 09:00–12:00 | **3h** | **M5** 保單變更 ★ 樂觀鎖 + `@Transactional` + 冪等性 | `PATCH /policies/{id}/beneficiary` + 衝突測試 |
| 13:00–14:30 | 1.5h | **M6** 全域例外處理 + 統一回應 + **M7** Swagger UI | OpenAPI 可匯入 Postman |
| 14:30–15:30 | 1h | **M8 縮水版** 整合測試 (只測 M5 的併發) | 一個會跑 lost update 防護的 test |
| 15:30–16:00 | 0.5h | 整理 README + 自我面試問答 | GitHub-ready 的作品集 |

### 削掉的部分

- M9 (Security/JWT) → 跳過，面試被問再口頭講原理即可
- M10 (商品/投保) → 跳過
- 完整的測試金字塔 → 只測 M5 的關鍵併發場景

---

## 五、下一步

回到對話視窗告訴我「**M0 開始**」或「**M1 開始**」，我會帶你一步步做完每個里程碑。
