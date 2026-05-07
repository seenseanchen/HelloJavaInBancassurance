# M6 Smoke Test — 全域例外處理 + 統一回應格式

## §0 變數約定

| 變數 | 意義 | 範例值 |
|---|---|---|
| `{{host}}` | API base URL | `http://localhost:8080` |
| `{{case_id}}` | 核保案件 UUID | (從 POST /cases 回應取得) |
| `{{policy_id}}` | 保單 UUID | (從 GET /policies 回應取得) |
| `{{policy_number}}` | 對外保單號 | `BANK-LIFE-20260507-0001` |

```bash
export host="http://localhost:8080"
export policy_number="BANK-LIFE-20260507-0001"
```

---

## §1 編譯與啟動

```bash
# 確認 Java 版本
java -version   # 應輸出 openjdk 21

# 確認 DB 已起 (M0 建立的 docker-compose)
docker compose ps   # bancassurance-db 應為 running

# 編譯 + 啟動
cd HelloJavaInBancassurance
./mvnw -q -DskipTests compile
./mvnw spring-boot:run
```

啟動成功時 console 應看到如下格式的 log（M6 新增 `[traceId=no-trace]`）：

```
12:00:00.001 [main] INFO  [traceId=no-trace] c.s.b.BancassuranceApplication - Started BancassuranceApplication
```

---

## §2 驗證一：成功回應現在帶 ApiResponse 包裝

### 2.1 GET 單筆保單

```bash
# {{policy_number}} = 保單號，例如 BANK-LIFE-20260507-0001
curl -s "$host/api/policies/by-number/$policy_number" | jq .
```

**預期 (M6 後)**：

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "id": "...",
    "policyNumber": "BANK-LIFE-20260507-0001",
    "holderName": "陳小明",
    ...
  },
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}
```

- `code` = "SUCCESS"
- `data` 裡是原本的 `PolicyResponse` 欄位
- `traceId` 是本次請求的 UUID

### 2.2 ETag header 仍然存在

```bash
curl -v "$host/api/policies/by-number/$policy_number" 2>&1 | grep -i etag
```

**預期**：仍有 `ETag: "0"` header（`ResponseBodyAdvice` 只改 body，不動 header）。

### 2.3 GET 清單 (分頁)

```bash
curl -s "$host/api/policies?status=IN_FORCE&size=3" | jq .
```

**預期**：

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "content": [ ... ],
    "totalElements": 5,
    "totalPages": 2,
    "pageable": { ... }
  },
  "traceId": "..."
}
```

`data` 裡是 Spring `Page` 物件（含 `content` / `totalElements` 等）。

---

## §3 驗證二：traceId 出現在 log 和 response header

### 3.1 Response header 帶 X-Trace-Id

```bash
curl -v "$host/api/policies/by-number/$policy_number" 2>&1 | grep -i "x-trace-id"
```

**預期**：`X-Trace-Id: 550e8400-...`（與 response body traceId 相同）

### 3.2 Log 每行都帶 traceId

發送一個請求後，在 terminal 觀察 Spring log 輸出：

```
12:00:01.123 [http-nio-8080-exec-1] DEBUG [traceId=550e8400-...] c.s.b.policy.service.PolicyService - ...
12:00:01.456 [http-nio-8080-exec-1] DEBUG [traceId=550e8400-...] o.h.SQL - select ...
```

同一次請求的所有 log 都帶相同的 `traceId`。

### 3.3 外部 traceId 複用

```bash
curl -s -H "X-Trace-Id: my-custom-trace-001" \
  "$host/api/policies/by-number/$policy_number" | jq .traceId
```

**預期**：`"my-custom-trace-001"`（服務複用外部帶入的 traceId）

---

## §4 驗證三：錯誤回應帶 traceId

### 4.1 404 Not Found

```bash
curl -s "$host/api/policies/by-number/INVALID-000" | jq .
```

**預期**：

```json
{
  "status": 404,
  "error": "Not Found",
  "code": "RESOURCE_NOT_FOUND",
  "message": "Policy not found: INVALID-000",
  "path": "/api/policies/by-number/INVALID-000",
  "timestamp": "2026-05-07T...",
  "traceId": "...",
  "details": []
}
```

> 注意：錯誤回應**沒有** `code/message/data` 的外層包裝，直接是 `ApiError` 扁平格式。

### 4.2 400 Validation Failed

```bash
curl -s -X POST "$host/api/underwriting/cases" \
  -H "Content-Type: application/json" \
  -d '{}' | jq .
```

**預期**：

```json
{
  "status": 400,
  "code": "VALIDATION_FAILED",
  "traceId": "...",
  "details": [
    { "field": "applicantName", "message": "must not be blank" },
    ...
  ]
}
```

### 4.3 Response header 也有 X-Trace-Id（錯誤時）

```bash
curl -v "$host/api/policies/by-number/INVALID-000" 2>&1 | grep -i "x-trace-id"
```

**預期**：`X-Trace-Id: ...`（GlobalExceptionHandler 也會設 header）

---

## §5 完成檢查單

| 項目 | 預期 | 確認 |
|---|---|---|
| 成功回應有 `code: "SUCCESS"` | GET `/api/policies/...` response | [ ] |
| 成功回應有 `data` 欄位包裝原始 DTO | data 裡有 policyNumber 等欄位 | [ ] |
| 成功回應有 `traceId` | UUID 格式 | [ ] |
| Response header 有 `X-Trace-Id` | 與 body traceId 相同 | [ ] |
| ETag header 仍然存在 | `ETag: "0"` | [ ] |
| 錯誤回應 `ApiError` 有 `traceId` | 404 response 有 traceId | [ ] |
| Log 每行帶 `[traceId=...]` | console log pattern 有效 | [ ] |
| 外部 `X-Trace-Id` header 被複用 | 自訂 header 回傳相同值 | [ ] |

---

## §6 面試話術

### Q：「線上 API 出錯怎麼定位？」

> 「我們在入口 filter 為每個 request 生成 traceId，寫入 SLF4J MDC，
> log pattern 用 `%X{traceId}` 讓每行 log 都帶上這個 id，
> response header 也回傳 `X-Trace-Id`，
> 客服收到用戶回報的 traceId 後，在 ELK / Grafana 搜一筆就能看到該次請求完整 log 鏈路。」

### Q：「`ResponseBodyAdvice` 是什麼？怎麼用？」

> 「`ResponseBodyAdvice` 是 Spring 的 SPI，在 MessageConverter 序列化 body 之前介入。
> 我用它做統一回應包裝，讓 20 多個 endpoint 的 Controller 完全不需要改動，
> 同時對 ApiError 例外直接放行（錯誤有自己的扁平格式），
> 對 Page 等物件照包，traceId 從 MDC 自動取，不用傳參。」

### Q：「如果 Controller 回 `ResponseEntity`（帶 ETag header），`ResponseBodyAdvice` 會破壞 header 嗎？」

> 「不會。Spring 在呼叫 `beforeBodyWrite` 之前已把 `ResponseEntity` 拆開，
> response 的 status 和 header 已設好，`beforeBodyWrite` 只能修改 body，
> 所以 ETag header 完全不受影響。」

### Q：「MDC 的 traceId 為什麼要在 finally 清掉？」

> 「因為 Spring 用 thread pool (Tomcat NIO)。線程用完後歸還 pool 給下一個請求用，
> 如果不清 MDC，舊的 traceId 會殘留在 ThreadLocal 裡，
> 下一個請求的 log 會混到前一次的 traceId — 這在 production 會讓 log 追蹤完全失效。」
