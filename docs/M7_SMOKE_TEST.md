# M7 Smoke Test — OpenAPI / Swagger UI 整合

## §0 變數約定

| 變數 | 意義 | 範例值 |
|---|---|---|
| `{{host}}` | API base URL | `http://localhost:8080` |

```bash
export host="http://localhost:8080"
```

---

## §1 編譯與啟動

```bash
# 首次加入 springdoc 依賴，需要下載
./mvnw -q -DskipTests compile

# 啟動（springdoc 依賴需網路，若 sandbox 環境請在本機執行）
./mvnw spring-boot:run
```

啟動成功時 console 會看到：

```
INFO  [traceId=no-trace] o.s.b.w.e.t.TomcatWebServer - Tomcat started on port 8080
INFO  [traceId=no-trace] c.s.b.BancassuranceApplication - Started BancassuranceApplication
```

---

## §2 驗證一：Swagger UI 可用

### 2.1 瀏覽器開 Swagger UI

```
http://localhost:8080/swagger-ui
```

預期畫面：
- 頂部標題：「銀保通路後端 API (Bancassurance Backend)」
- 版本：0.7.0
- 三個 Tag 群組：
  - **保單變更 (Policy Endorsement)**
  - **保單查詢 (Policy Query)**
  - **核保案件 (Underwriting Cases)**

### 2.2 API 操作說明確認

展開 **核保案件** → `POST /api/underwriting/cases/{id}/approve`：

- Summary 應顯示「核准：核保員審查通過」
- Description 應顯示狀態流程說明
- Responses 含 200 / 409 兩行

展開 **保單變更** → `PATCH /api/policies/{policyId}/beneficiaries`：

- Description 應顯示業務規則（受益人加總 = 100）
- Responses 含 200 / 409 / 412 / 422 四種

---

## §3 驗證二：OpenAPI JSON 規格可取得

```bash
# 取得 JSON 規格
curl -s "$host/api-docs" | jq '{
  title: .info.title,
  version: .info.version,
  paths: (.paths | keys | length)
}'
```

**預期**：

```json
{
  "title": "銀保通路後端 API (Bancassurance Backend)",
  "version": "0.7.0",
  "paths": 17
}
```

> paths 數量依實際 endpoint 數而定（約 14–20 支）

---

## §4 驗證三：從 Swagger UI 匯入 Postman

1. 取得 OpenAPI JSON 規格 URL：`http://localhost:8080/api-docs`
2. 打開 Postman → **Import** → **Link**
3. 輸入：`http://localhost:8080/api-docs`
4. 按 **Continue** → **Import**

預期結果：
- Postman 自動建立 Collection「銀保通路後端 API」
- 所有 endpoint 依 Tag 分組出現
- path variable 和 request body 範例自動填入

---

## §5 驗證四：Swagger UI 互動測試

### 5.1 直接從 Swagger UI 打 API

1. 展開 **保單查詢** → `GET /api/policies/by-number/{policyNumber}`
2. 點 **Try it out**
3. `policyNumber` 輸入 `BANK-LIFE-20260507-0001`
4. 點 **Execute**

**預期回應 (200)**：

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "policyNumber": "BANK-LIFE-20260507-0001",
    ...
  },
  "traceId": "..."
}
```

Response headers 應包含：
- `ETag: "0"`
- `X-Trace-Id: <uuid>`

### 5.2 確認錯誤格式也顯示

展開 **保單查詢** → `GET /api/policies/by-number/{policyNumber}`
輸入 `policyNumber = NOT-EXIST-000` → Execute

**預期回應 (404)**：

```json
{
  "status": 404,
  "code": "RESOURCE_NOT_FOUND",
  "traceId": "...",
  ...
}
```

---

## §6 完成檢查單

| 項目 | 預期 | 確認 |
|---|---|---|
| `http://localhost:8080/swagger-ui` 能開 | Swagger UI 有三個 Tag 群組 | [ ] |
| API 標題、版本正確 | 銀保通路後端 API v0.7.0 | [ ] |
| 核保案件的 transition endpoint 有說明 | approve/reject 等有 description | [ ] |
| 保單變更 PATCH 有列出 412/422 回應 | 四種 response code | [ ] |
| `GET /api-docs` 回傳 JSON | `.info.title` 正確 | [ ] |
| Postman 能從 URL 匯入 Collection | 所有 endpoint 出現 | [ ] |
| Swagger UI 互動測試回 ApiResponse 包裝 | data / traceId 欄位存在 | [ ] |

---

## §7 常見問題排除

### Q: 啟動後 `/swagger-ui` 回 404

確認 `pom.xml` 有加 `springdoc-openapi-starter-webmvc-ui`：

```bash
./mvnw dependency:tree | grep springdoc
```

應看到：`org.springdoc:springdoc-openapi-starter-webmvc-ui:jar:2.8.x`

### Q: springdoc 版本不相容 Spring Boot 4.0.x

若啟動時出現 `ClassNotFoundException` 或 `NoSuchMethodError`，
請至 [springdoc 相容性矩陣](https://springdoc.org/#what-is-the-compatibility-matrix-between-springdoc-openapi-and-spring-boot)
確認對應 Spring Boot 4.0.x 的最新版本，並更新 `pom.xml`：

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version><!-- 查官網最新版 --></version>
</dependency>
```

### Q: `ApiResponseWrapper` 包裝後 Swagger UI schema 顯示是內層 DTO

這是預期行為。springdoc 讀 Controller 的**靜態回傳型別**（`PolicyResponse`），
但 runtime 已被 `ResponseBodyAdvice` 包成 `ApiResponse<PolicyResponse>`。
文件中已在 API 總覽 description 說明此 envelope 格式。
進階解法：讓 Controller 改成顯式回傳 `ApiResponse<T>`，springdoc 就能正確推斷。

---

## §8 面試話術

### Q：「OpenAPI 跟 Swagger 的差別？」

> 「OpenAPI 是 REST API 的描述規格標準（YAML/JSON 格式），由 OpenAPI Initiative 維護；
> Swagger 是 SmartBear 的工具集（UI、Editor、Codegen），最早由 SmartBear 提出並貢獻給社群演變成 OpenAPI 標準。
> 嚴格說應該叫『OpenAPI 規格』和『Swagger UI』，但業界口語常混用。」

### Q：「API 版本化有哪些方式？你怎麼選？」

> 「三種主流：(1) URL 路徑版本 `/v1/policies`、(2) Header 版本 `Accept: vnd.app.v1+json`、(3) Query String `?version=1`。
> 我們銀行系統選 URL 路徑版本，因為 LB 路由規則和 log 分析最直覺。
> 原則上 additive changes（加欄位、加 endpoint）不需升版；breaking changes 才升，
> 舊版跑 deprecation period 讓 client 遷移。」

### Q：「springdoc 是怎麼自動掃描 Controller 產生文件的？」

> 「springdoc 用 Spring ApplicationContext 拿到所有 `@RestController` bean，
> 反射讀取 method signature 和 annotation（`@RequestMapping`、`@PathVariable`、`@RequestBody` 等），
> 再結合 Jackson ObjectMapper 推斷 DTO 的 JSON Schema，最後組成 OpenAPI 3.1 規格輸出。
> `@Operation`、`@ApiResponse`、`@Schema` 是補充說明，不加 springdoc 也能掃，加了更精確。」

### Q: 執行失敗案例

> 「ResponseBodyAdvice 在生產環境有什麼陷阱？」
> 1. byte[] 陷阱：框架元件用預先序列化的 byte[] + ByteArrayHttpMessageConverter 輸出 JSON，content-type 仍是 application/json，用 content-type 過濾抓不到，要用 body instanceof byte[] 擋。
> 2. 內部 endpoint 陷阱：springdoc 的 /api-docs/swagger-config 回傳 Java 物件，content-type 是 application/json，兩個過濾條件都過不了，結果被包裝成 ApiResponse<SwaggerUiConfigParameters>，Swagger UI 解析失敗。
> 3. 根本解法：用請求路徑過濾，把框架內部 endpoint 的前綴（/api-docs、/swagger-ui、/actuator）整個排除，業務路徑才做包裝。