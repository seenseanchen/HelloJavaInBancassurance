# PLAN.md — 學習與開發計畫

## 一、學習路徑全景圖

```
[ 後端 / Backend Track ]
M0 環境  ──►  M1 骨架  ──►  M2 領域 (核保)  ──►  M3 狀態機
                                                    │
                                                    ▼
M8 測試  ◄──  M7 Swagger  ◄──  M6 例外處理  ◄──  M4 領域 (保單)
   │                                                │
   │                                                ▼
   │                                            M5 保單變更 (核心)
   ▼
M9 Security  ──►  M10 商品/投保

[ 維運 / Infra Track — 完成後端後接著做 ]
M11 Nginx  ──►  M12 OTel + Jaeger + Prometheus + Grafana
                       │
                       ▼
M13 ELK (Kibana 日誌中央化)  ──►  M14 Jenkins CI/CD  ──►  M15 n8n 工作流

[ 前端 / Frontend Track — 與 Infra Track 可平行 ]
M16 Vue 骨架 + Login  ──►  M17 核保頁  ──►  M18 保單查詢/變更頁
```

> M5 是後端的高潮 — 交易控制、樂觀鎖、冪等性、稽核全部出場，也是面試最常被深挖的環節。
> M12 (OpenTelemetry) 是 Infra 的高潮 — 把後端的 traceId / log / metrics 一次串起，現代金融業 SRE 必修。

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

## 二之二、Infra Track (M11–M15)

> 後端 API 跑通之後，把現代 Java 生態的維運基建一個一個疊上來。
> 全部用 docker compose 統一管理，原則上 **不污染** 現有 `docker-compose.yml`，新開 `docker-compose.infra.yml` 用 profile 隔離。

### Infra 整體佈局原則

```
┌─────────────────────────────────────────────────────────────┐
│                          Browser                            │
└────────────────────────┬────────────────────────────────────┘
                         │ HTTPS :443
                ┌────────▼────────┐
                │  Nginx (M11)    │  ← TLS / 反向代理 / 靜態檔
                └──┬───────────┬──┘
                   │           │
        /api/*  ┌──▼──┐   /*  ┌▼──────┐
                │ API │       │ Vue   │
                │:8080│       │ build │
                └──┬──┘       └───────┘
                   │ OTLP gRPC :4317
        ┌──────────▼──────────┐
        │ OTel Collector (M12)│  ← 收 traces / metrics / logs
        └──┬────────┬────────┬┘
           │        │        │
      ┌────▼──┐ ┌──▼────┐ ┌─▼─────────────┐
      │Jaeger │ │Promet.│ │ Elasticsearch │  ← M13 ELK 也吃 OTel logs
      └───────┘ └───┬───┘ └──┬────────────┘
                    │        │
                ┌───▼────────▼────┐
                │ Grafana / Kibana │  ← 視覺化
                └──────────────────┘

[ 旁支 ]
Jenkins (M14) ── 拉 GitHub → 跑 mvn test → build image → push
n8n     (M15) ── 收 Webhook → 通知 / 對帳 / 排程
```

> **共通注意事項**：
> 1. **網路隔離**：infra 容器全部接到自訂 bridge network `bancassurance-net`，避免用 `host` 模式。
> 2. **資料持久化**：所有 stateful 服務 (ES、Prometheus、Jenkins home、n8n DB) 都掛 named volume，不要用 bind mount 進專案資料夾（避免 git 誤追）。
> 3. **secrets 管理**：所有密碼用 `.env` 注入，`.env` 加進 `.gitignore`，提供 `.env.example`。
> 4. **資源限制**：本機 docker desktop 給 8GB RAM，每個 container 用 `mem_limit` 限量，避免 ELK 把 mac 吃爆。
> 5. **healthcheck**：每個 service 都寫 `healthcheck`，配合 `depends_on.condition: service_healthy`。

---

### M11 — Nginx Reverse Proxy (基建第一塊)

> **為何優先**：之後所有 infra (Grafana / Kibana / Jenkins / n8n) 都會用 Nginx 統一掛在 `*.local.bancassurance` 子網域，一次學會省 N 次。

- 用 `nginx:1.27-alpine`，掛 `nginx.conf` + `conf.d/*.conf` + 自簽 TLS 憑證
- 三個 server block：
  - `api.local.bancassurance` → proxy_pass `http://api:8080`
  - `app.local.bancassurance` → 靜態檔 (Vue build 出來的 `dist/`)
  - `tools.local.bancassurance` → 之後 Grafana / Kibana / Jenkins 子路徑掛載
- **與 Spring Boot 的串接眉角**：
  - Nginx 加 `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` 等三件套
  - Spring Boot `application.yml` 加 `server.forward-headers-strategy: native`，否則 `request.getRemoteAddr()` 抓到的會是 Nginx IP 不是真實 client
  - HSTS / gzip / brotli 在 Nginx 處理，不要回 Spring Boot 做（效能差）
- **學會**：reverse proxy vs forward proxy；`proxy_pass` 末尾有/沒有 `/` 的差別；Nginx worker model
- **面試題**：
  - 初級：「Nginx 跟 Apache 差在哪？」
  - 中級：「為什麼 reverse proxy 通常前置 SSL 而不是後端 app 處理？」
  - 資深：「Nginx 怎麼做 graceful reload？」「`worker_connections` 怎麼算？」

### M12 — OpenTelemetry + Jaeger + Prometheus + Grafana ★ 可觀測性核心

> **2026 年的做法跟 5 年前完全不同**：以前要分別裝 Zipkin agent、Micrometer push gateway，現在 **OTLP** 是統一通訊協定，所有後端只發 OTLP，下游收什麼都行。

- **後端接入方式**：用 `opentelemetry-spring-boot-starter`（Spring Boot 4.x 官方推薦，比裝 javaagent 更可控）。一行 `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317` 啟動就有 traces 自動採。
- **元件分工**：
  - **OTel Collector** (`otel/opentelemetry-collector-contrib`)：收所有遙測資料的中介，下游分流到 Jaeger / Prometheus / Loki
  - **Jaeger** 2.x（`jaegertracing/jaeger:2.x`，2024 年改名 Jaeger v2 後內建 OTLP receiver，可不經 Collector）：traces 視覺化
  - **Prometheus** (`prom/prometheus:v3`)：拉 metrics（Spring Boot Actuator + Micrometer Prometheus exporter）
  - **Grafana** (`grafana/grafana:11`)：統一儀表板，同時讀 Prometheus + Jaeger
- **與 M6 traceId 的串接眉角** ★：
  - OTel SDK 會自動把 `trace_id` / `span_id` 寫入 SLF4J MDC
  - 把 `application.yml` log pattern 改成 `[traceId=%X{trace_id:-} spanId=%X{span_id:-}]`，舊的 `TraceIdFilter` 要保留但只當「外部 X-Trace-Id 注入」的 fallback
  - **面試重點**：解釋為何 W3C Trace Context 的 `traceparent` header 取代了你自幹的 `X-Trace-Id`
- **與 M5 樂觀鎖的串接眉角**：
  - Hibernate 的 SQL 可以開 `otel.instrumentation.jdbc.statement-sanitizer.enabled=true`，自動把 SQL 帶進 span，但要注意 SQL 內參數不能含 PII（身分證、姓名）— 已有的身分證遮罩繼續適用
- **Sampling**：本機 100% 採、生產 Demo 用 `parentbased_traceidratio=0.1` 即 10%
- **學會**：W3C Trace Context、Span vs Trace、Pull vs Push metrics
- **面試題**：
  - 初級：「APM 是什麼？跟 Log 差在哪？」
  - 中級：「分散式追蹤的 traceId 怎麼跨服務傳？」「為何用 OTel 不直接用 Jaeger client？」
  - 資深：「100% sampling 在 prod 為什麼不可行？head sampling vs tail sampling 怎麼選？」

### M13 — ELK / EFK (Elasticsearch + Kibana + Fluent Bit) — 集中化日誌

> 2026 年的選擇：用 **Fluent Bit** 取代 Logstash（吃資源少 10 倍、寫 C），組合慣稱 **EFK**。
> Elasticsearch 8.x 預設開啟 security，第一次跑要處理 ca cert，照官方 `setup.elasticsearch.security` 流程做。

- 元件：`elasticsearch:8.15` + `kibana:8.15` + `fluent/fluent-bit:3`
- Spring Boot 改用 **JSON structured logging**：加 `logstash-logback-encoder`，每行 log 直接吐 JSON（含 traceId、level、thread、自訂欄位）
- Fluent Bit 透過 docker log driver 收 container stdout → 推進 ES
- Kibana 建 index pattern `bancassurance-*`，做：
  - Saved search：「最近 1 小時所有 ERROR」
  - Dashboard：每分鐘 4xx / 5xx 數量、各 endpoint p95 延遲
  - Alerting：5xx 連續 3 分鐘 > 10 次發 webhook
- **與 M12 OTel 的關係** ★：
  - 兩種做法可選：(A) Fluent Bit 收 docker log 推 ES (傳統)；(B) Spring Boot 用 OTel Logs Bridge → OTel Collector → ES exporter
  - 建議 (A)，因為 (B) 設定門檻高，且 OTel Logs spec 還在演化
- **與 M5 稽核的對比**：log（運維用，可丟）vs `policy_change_log`（業務稽核，永久保留），別搞混
- **面試題**：
  - 初級：「為什麼要把 log 集中？散落各台機器有什麼問題？」
  - 中級：「Logstash vs Fluent Bit 怎麼選？」「JSON structured log 比 plain text 好在哪？」
  - 資深：「ES 的 ILM (Index Lifecycle Management) 為何重要？」「7 天熱資料 / 30 天溫資料怎麼分？」

### M14 — Jenkins CI/CD (Pipeline as Code)

> 金融業現實：雖然新案越來越多走 GitHub Actions / GitLab CI，**Jenkins 在台灣銀行 in-house 仍然壓倒性多數**。學一輪划算。
> 現代化做法：**JCasC (Jenkins Configuration as Code)** + **Jenkinsfile (Declarative Pipeline)**，沒有人工點 GUI 設定。

- 容器：`jenkins/jenkins:lts-jdk21`，跑在 docker-in-docker 模式（Jenkins container 內可以 build docker image）
- 預先用 JCasC YAML 設定：admin user、global tools (Maven/JDK)、credentials (GitHub token、Docker Hub token)
- `Jenkinsfile` 在專案 root，五個 stage：
  ```
  Checkout → Build (mvn package) → Test (mvn verify, Testcontainers)
           → Build Docker Image → Push to Registry
  ```
- 觸發：GitHub webhook (PR / push to main)
- **與 M8 整合測試的串接眉角** ★：
  - Testcontainers 在 Jenkins 容器內要能起 Docker，需要把 host 的 `/var/run/docker.sock` 掛進 Jenkins container（DooD 模式）
  - Maven cache：用 named volume 掛 `~/.m2/repository`，不然每次 build 都重抓 dependencies
- **與 M14→M11 部署的串接眉角**：build 完的 image push 到本地 registry (`registry:2`)，Nginx 後面的 API container 用 `watchtower` 自動拉新 image 重啟（學習階段夠用）
- **面試題**：
  - 初級：「CI 跟 CD 差在哪？」
  - 中級：「Jenkinsfile 為什麼要 Pipeline-as-Code 不要點 GUI 設定？」「Shared Library 用過嗎？」
  - 資深：「Build agent 隔離怎麼做？」「Blue/Green vs Canary 部署 Jenkins 怎麼支援？」

### M15 — n8n 工作流自動化 (低代碼整合)

> n8n 在 2024–2025 爆紅，定位是「self-hosted 的 Zapier」。銀保業常用場景：保單變更通過後通知 / 對帳檔產生後 ftp 給對方 / 排程跑日結。

- 容器：`n8nio/n8n:latest`，**外掛 PostgreSQL** 做 persistence（預設 SQLite 不適合多 worker），共用 M0 的 `bancassurance-pg` 但開新 schema `n8n`
- 場景設計（與後端串接）：
  - **保單變更通知**：M5 的 `PolicyChangeService` 完成後 publish event → 呼叫 n8n webhook → n8n 分流到 Slack / Email / SMS
  - **核保 SLA 監控**：n8n cron 每 10 分鐘 query `GET /api/underwriting/cases?status=PENDING_INFO&olderThan=24h`，超過 SLA 自動 escalate
- **與 Outbox Pattern 的關係**（接續 M10）：直接 in-process 呼叫 n8n webhook 簡單但有風險（n8n 掛了訊息丟失）；正規做法是 Outbox table → 排程 publish → n8n
- **與 M14 Jenkins 的對比**：n8n = 業務流程編排；Jenkins = 開發流程編排。別搞混。
- **注意事項**：n8n 的 workflow JSON 要 commit 進 git（n8n 1.x 開始支援 source control 整合 GitHub）
- **面試題**：
  - 初級：「Webhook 跟 polling 差在哪？」
  - 中級：「為什麼通知不直接寫在 PolicyChangeService 裡，要透過 n8n？」（解耦 / 業務人員可改 flow / 不用發版）
  - 資深：「Outbox Pattern 為什麼比 in-process 直送可靠？」「冪等性如何在 n8n 的重試下維持？」

---

## 二之三、Frontend Track (M16–M18) — Vue 3 + 國泰風 UI

> Style Guide 推薦 Next.js + React，但你要學 Vue，所以走 **Vue 3 + Vite + TypeScript + Pinia + Vue Router + Tailwind CSS + Element Plus**。
> 國泰人壽風格的核心：**Cathay Green `#00a160`** + 大量留白 + 卡片式區塊 + Mobile First。
> 前端跑在 `frontend/` 子資料夾，跟後端 monorepo 共存。

### M16 — Vue 骨架 + Design System + Login Flow

- `npm create vite@latest frontend -- --template vue-ts`
- 安裝：`vue-router pinia axios @vueuse/core element-plus tailwindcss postcss autoprefixer`
- 目錄結構：
  ```
  frontend/
  ├── src/
  │   ├── api/              # axios instance + 各模組 API client
  │   ├── stores/           # Pinia stores (auth, policy, underwriting)
  │   ├── router/           # Vue Router + 守衛
  │   ├── views/            # 頁面元件
  │   ├── components/       # 可複用元件 (CardBlock, StatusBadge...)
  │   ├── styles/
  │   │   ├── tokens.css    # Cathay Green / spacing / shadow / radius CSS vars
  │   │   └── tailwind.css
  │   └── main.ts
  ├── tailwind.config.ts    # extend colors → cathay primary palette
  └── vite.config.ts        # proxy /api → http://localhost:8080
  ```
- **Design Token (對齊國泰 Style Guide)**：
  ```css
  :root {
    --cathay-green: #00a160;          /* Primary */
    --cathay-green-hover: #00c474;     /* Accent */
    --cathay-green-pale: #e6f7ee;      /* Pale Green */
    --neutral-900: #1a1a1a;
    --neutral-500: #707070;
    --neutral-100: #f5f6f7;
    --radius-card: 16px;
    --shadow-card: 0 4px 16px rgba(0, 0, 0, 0.06);
    --space-section: 64px;
  }
  ```
- **Login Flow (預設帳密硬編碼)** — M9 還沒做 JWT，先用前端模擬：
  - `src/config/users.ts` 寫死兩組帳密（`underwriter / 1234` 走核保員角色、`agent / 1234` 走業務員角色）
  - Login form → 比對 → 寫進 `useAuthStore` (Pinia) → 同步到 `sessionStorage`（**註明：生產絕對不能這樣，等 M9 接真的 JWT**）
  - `router.beforeEach`：未登入跳 `/login`，已登入訪問 `/login` 跳 `/dashboard`
  - axios interceptor：所有請求自動帶 `X-Actor: <username>` header（對齊 M5 後端的 actor 欄位）
- **頁面**：
  - `/login` — 卡片置中、左半 brand visual、右半表單
  - `/dashboard` — Hero + 三張服務卡 (核保 / 保單 / 變更歷史)，卡片風格嚴格遵守 Style Guide
- **與後端 M11 Nginx 的串接眉角**：開發走 Vite dev server proxy，正式環境 Vue build 出的 `dist/` 由 Nginx 直接服務
- **面試題**：
  - 初級：「Vue 3 vs Vue 2 主要差在哪？」「Composition API 解決什麼問題？」
  - 中級：「Pinia vs Vuex 為什麼要換？」「為什麼 axios interceptor 比每個 request 手動帶 header 好？」
  - 資深：「SPA 路由守衛只能擋前端，安全性還是要靠後端 — 為什麼？」

### M17 — 核保案件管理頁

- 對應後端：`/api/underwriting/cases` 全套 CRUD + 6 個 transition (M2/M3)
- 頁面：
  - `/underwriting/cases` — 列表，篩選 status (Element Plus `el-select`) + 分頁 (`el-pagination`)，每筆案件用卡片 + StatusBadge 元件
  - `/underwriting/cases/new` — 新增表單，`el-form` + Validation
  - `/underwriting/cases/:id` — 詳情，**狀態流轉按鈕區**根據當前狀態 dynamic render（後端 `nextStates` 拿，前端不要硬寫）
  - `/underwriting/cases/:id/events` — 事件 timeline（Element Plus `el-timeline`）
- **狀態機 UI 串接眉角** ★：呼叫 transition endpoint 拿到 409 IllegalStateTransitionException 時，要 catch 住跳「狀態已被他人變更，請重新讀取」的 modal（這是 M3 的設計重點，前端不能吞）
- **面試題**：
  - 中級：「為什麼狀態流轉按鈕要由後端決定能按哪些，而不是前端寫死？」（領域邏輯只能在後端，前端只是 view）

### M18 — 保單查詢與變更頁 ★ 對應後端 M5

- 對應後端：`/api/policies` 查詢 + 三支 PATCH (M4/M5)
- 頁面：
  - `/policies` — 列表 + Specifications 動態篩選（保單號 / 狀態 / 投保人姓氏）
  - `/policies/:id` — 詳情卡片（保單資訊 / 受益人 / 繳費），**從 response header 取 ETag 存進 Pinia**
  - `/policies/:id/change/address` — 地址變更表單
  - `/policies/:id/change/beneficiaries` — 受益人變更，**前端先檢核 priority sum = 100**
  - `/policies/:id/change/payment-method` — 繳費方式
  - `/policies/:id/changes` — 變更歷史 timeline
- **樂觀鎖 UI 串接眉角** ★ (M5 整套搬到前端)：
  - PATCH 時自動帶 `If-Match: <ETag>` header
  - PATCH 時自動產生 `Idempotency-Key: <uuid v4>` header（用 `crypto.randomUUID()`）
  - 412 Precondition Failed → 「資料已被他人異動」modal + 自動重抓
  - 409 Conflict (OptimisticLock or Illegal state) → 同上
  - 422 BusinessRule (受益人加總 ≠ 100) → 表單欄位下方紅字
  - **面試重點**：解釋為何 `If-Match` 用 ETag 比直接傳 `version` 欄位更 RESTful（HTTP 標準語意）
- **面試題**：
  - 中級：「前端為什麼還要算 priority sum，後端不是會算嗎？」（UX：早回饋 / 省 round-trip；安全：後端依然要算，前端只是輔助）
  - 資深：「PATCH 全替換受益人 vs JSON Patch 增量，怎麼選？」（協議簡單度 vs payload 大小）

---

## 二之四、Infra / Frontend 建置優先順序總表

| 優先 | Milestone | 為什麼這個順位 | 預估工時 |
|---|---|---|---|
| 1 | **M11 Nginx** | 後續所有 infra 的入口都掛這裡，先做避免重工；前端 build 出來也要 Nginx 服務 | 2h |
| 2 | **M16 Vue 骨架 + Login** | 與 M11 平行：先有可看的畫面，後端做的東西「看得到」學習動機強 | 3h |
| 3 | **M12 OTel + Jaeger + Prometheus + Grafana** | 可觀測性是現代 SRE 第一張牌，且能串到 M5/M6 已寫的 traceId | 4h |
| 4 | **M17 核保頁** | 串 M2/M3 已寫好的後端，鞏固狀態機觀念 | 3h |
| 5 | **M18 保單變更頁** | 串 M5 樂觀鎖 / 冪等性，**前後端整套打通的高潮** | 4h |
| 6 | **M13 ELK / EFK** | 日誌中央化是 prod 必要、面試常問，但本機學習價值低於 OTel | 3h |
| 7 | **M14 Jenkins CI/CD** | 學完後端跟前端再來自動化，不然會重複改 pipeline | 3h |
| 8 | **M15 n8n** | 加分項，補強「業務流程自動化」面試話術 | 2h |

> **建議節奏**：M11 → M16 → M17 → M18（前後端打通）→ M12（觀測） → M13 → M14 → M15
> **或者** 偏 SRE 路線：M11 → M12 → M13 → M14 → M15 → M16 → M17 → M18（先把 infra 全部疊好再做前端）

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

後端 M0–M8 已完成。可選的入口：

- 「**M9 開始**」— 接 Spring Security + JWT（後端線）
- 「**M10 開始**」— 商品上下架 / 線上投保（後端線）
- 「**M11 開始**」— Nginx 反向代理（infra 線，建議第一個）
- 「**M16 開始**」— Vue 骨架 + Login Flow（前端線，可與 infra 平行）

每個 milestone 都會走「先解釋 → 寫一段 → 跑得起來 → 補面試題」的節奏，等你說可以再進下一個。
