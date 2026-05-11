# M12 Smoke Test — Observability (Jaeger + OTel Collector + Prometheus + Grafana)

完成日期：2026-05-11（更新）

## §0 變數約定

| 變數 | 說明 | 範例值 |
|---|---|---|
| `{{host}}` | 後端 API base URL | `http://localhost:8080` |
| `{{id}}` | 保單 UUID（IN_FORCE，用於 412/422 測試） | `44444444-4444-4444-4444-444444444444` |

```bash
export host="http://localhost:8080"
export id="44444444-4444-4444-4444-444444444444"
export lapsedId="33333333-3333-3333-3333-333333333333"   # 409 測試用
```

## 1) 啟動 Observability Stack

```bash
docker compose -f ops/infra/docker-compose.observability.yml up -d
docker compose -f ops/infra/docker-compose.observability.yml ps
```

預期：
- `bancassurance-jaeger`、`bancassurance-otel-collector`、`bancassurance-prometheus`、`bancassurance-grafana` 都是 `Up`

## 2) 平台入口

- Jaeger UI: [http://localhost:16686](http://localhost:16686)
- Prometheus UI: [http://localhost:9090](http://localhost:9090)
- Grafana UI: [http://localhost:13000](http://localhost:13000)（`admin/admin`）

## 3) 基本健康檢查

### 3.1 Collector metrics endpoint

```bash
curl -sS http://localhost:8888/metrics | head
```

預期：有 Prometheus 格式輸出。

### 3.2 Prometheus targets

```bash
curl -sS http://localhost:9090/api/v1/targets \
  | jq -r '.data.activeTargets[] | [.labels.job,.health,.lastError] | @tsv'
```

預期：
- `prometheus` = `up`
- `otel-collector` = `up`
- `bancassurance-backend`：後端啟動時應為 `up`

## 4) Grafana provisioning 驗證（SEE-19）

```bash
curl -sS -u admin:admin 'http://localhost:13000/api/search?query=' \
  | jq -r '.[] | [.uid,.title,.type] | @tsv'
```

預期至少包含：
- `Bancassurance - Policy Change Overview`（自製 dashboard）
- `SpringBoot APM Dashboard`（Grafana 官方 dashboard，ID 12900）

## 5) 觸發流量並驗證 trace / metric 關聯

### 5.1 登入取得 token

```bash
LOGIN_JSON=$(curl -sS -X POST "$host/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"csr01","password":"csr123"}')
TOKEN=$(printf '%s' "$LOGIN_JSON" | jq -r '.data.accessToken')
```

### 5.2 打一筆查詢流量，取得 `X-Trace-Id`

```bash
curl -sS -D /tmp/m12.headers \
  -H "Authorization: Bearer $TOKEN" \
  "$host/api/policies?status=IN_FORCE&page=0&size=1" >/tmp/m12.body.json

TRACE_ID=$(awk 'BEGIN{IGNORECASE=1}/^X-Trace-Id:/{print $2}' /tmp/m12.headers | tr -d '\r')
echo "$TRACE_ID"
```

預期：
- `TRACE_ID` 不為空
- 後端 log 可以看到同一個 traceId（M6/M12 串接）

### 5.3 Jaeger 檢查（Tracing）

在 Jaeger UI：
1. Service 選 `Bancassurance`
2. 查最近 15 分鐘 traces
3. 點進任一 trace，確認有 SQL 子 span（`db`/`sql` 相關 span）

## 6) 故意打出 412 / 409 / 422（SEE-20）

### 6.1 先取當前 version

```bash
CUR_VER=$(curl -sS -H "Authorization: Bearer $TOKEN" "$host/api/policies/$id" \
  | jq -r '.data.version')
LAPSED_VER=$(curl -sS -H "Authorization: Bearer $TOKEN" "$host/api/policies/$lapsedId" \
  | jq -r '.data.version')
```

### 6.2 412 PRECONDITION_FAILED（故意 stale version）

```bash
curl -sS -i -X PATCH "$host/api/policies/$id/address" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"expectedVersion\":$((CUR_VER+999)),\"newAddress\":\"台北市中山區南京東路100號\",\"reason\":\"m12-412\"}"
```

預期：HTTP `412`

### 6.3 409 INVALID_POLICY_STATE（對 LAPSED 保單做變更）

```bash
curl -sS -i -X PATCH "$host/api/policies/$lapsedId/address" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"expectedVersion\":$LAPSED_VER,\"newAddress\":\"新竹市東區光復路100號\",\"reason\":\"m12-409\"}"
```

預期：HTTP `409`

### 6.4 422 BUSINESS_RULE_VIOLATION（受益人比例加總 != 100）

```bash
curl -sS -i -X PATCH "$host/api/policies/$id/beneficiaries" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"expectedVersion\":$CUR_VER,
    \"beneficiaries\":[
      {\"name\":\"配偶\",\"idNumber\":\"B123456789\",\"relationship\":\"SPOUSE\",\"allocationPercentage\":60.00,\"priority\":1},
      {\"name\":\"子女\",\"idNumber\":\"C123456788\",\"relationship\":\"CHILD\",\"allocationPercentage\":39.00,\"priority\":1}
    ],
    \"reason\":\"m12-422\"
  }"
```

預期：HTTP `422`

### 6.5 Prometheus / Grafana 檢查（Metrics）

在 Prometheus 或 Grafana Explore 查：
- `http_server_requests_seconds_count`
- `http_server_requests_seconds_bucket`（可算 p99）
- 自訂 `policy_change_total`

預期：打完上述流量後，request count 與錯誤分佈會變化。

## 7) 關閉（可選）

```bash
docker compose -f ops/infra/docker-compose.observability.yml down
```

## 面試話術（SEE-20）

- 「Tracing / Metrics / Logging 三柱閉環」：
  同一個 request 可在 log（traceId）、trace（Jaeger）、metric（Prometheus/Grafana）互相對照。
- 「為什麼選 OTel 而不是 Jaeger client」：
  OTel 是標準協定（OTLP），Collector 可換後端，不綁單一 vendor。
