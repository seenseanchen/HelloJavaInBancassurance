# Observability / EFK 問題定位與手動 Smoke Guide

更新日期：2026-05-12

## 1. 這套系統在看什麼

資料流（本專案）：

1. **Metrics**
   - Backend 暴露 `/actuator/prometheus`
   - Prometheus 抓取 metrics
   - Grafana 用 Prometheus 畫圖

2. **Tracing**
   - Backend 送 OTLP trace 到 OTel Collector (`4317/4318`)
   - OTel Collector 轉發到 Jaeger
   - Jaeger 以 `service` + span 顯示請求路徑

3. **Logs**
   - Fluent Bit 目前只 tail **Docker container logs**
   - 送到 Elasticsearch (`9200`)
   - Kibana (`5601`) 查詢與視覺化

---

## 2. 你提的 5 個問題：根因與解法

### Q1. Prometheus 查詢方式

先看 Prometheus UI：
- `http://localhost:9090/targets`：先確定 target 是 `up`
- `http://localhost:9090/graph`：輸入 PromQL 查詢

常用 PromQL（本專案）：

```promql
up
up{job="bancassurance-backend"}
policy_change_total
sum(rate(policy_change_total[5m]))
sum(rate(policy_change_total{outcome="success"}[5m]))
http_server_requests_seconds_count
histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))
```

---

### Q2. Grafana `Policy Change RPS / Success Ratio / HTTP p99` 沒資料

已確認有三種常見原因：

1. `Success Ratio` 標籤大小寫不一致
   - 後端實際 tag：`outcome=success`（小寫）
   - dashboard 原查詢用 `SUCCESS`（大寫）會查不到
   - 已修正檔案：`ops/infra/config/grafana/provisioning/dashboards/json/bancassurance-policy-change.json`

2. `policy_change_total` 是「事件型」counter
   - 你沒打變更 API（address/beneficiaries/payment-method）就不會有資料
   - 只打查詢 API 不會增加這個 counter

3. `HTTP p99` 需要 `*_bucket`
   - p99 查詢用的是 `http_server_requests_seconds_bucket`
   - 若沒開 histogram bucket，這條會空白
   - 已補設定：`management.metrics.distribution.percentiles-histogram.http.server.requests=true`
   - 檔案：`src/main/resources/application.yml`

> 套用上述設定後，請重啟 **backend** 與 **grafana**（讓 app 設定與 provisioning 生效）。

---

### Q3. `9200` Elasticsearch 是幹嘛？怎麼驗證

`9200` 是 Elasticsearch HTTP API，負責：
- index 建立/查詢
- cluster health
- ILM policy / template / settings 查詢

基本驗證：

```bash
curl -fsS http://localhost:9200 | jq '.version.number'
curl -fsS http://localhost:9200/_cluster/health?pretty
curl -fsS 'http://localhost:9200/_cat/indices/bancassurance-logs-*?v'
```

---

### Q4. Elastic 搜不到 API 調用記錄

目前設定的關鍵限制是：

- Fluent Bit input 是 `/var/lib/docker/containers/*/*.log`
- 代表只會收 Docker 容器 log
- 如果 backend 是 `./mvnw spring-boot:run`（主機程序），**不會**被 Fluent Bit 收到

所以你會看到：
- Jaeger / Prometheus 有 backend 資料（因為是連線抓取/匯出）
- Elasticsearch 主要是 infra 容器 log（jaeger/prometheus/kibana/collector...）
- 卻找不到 backend API 調用 log

要讓 API log 進 Elastic，需二選一：

1. 把 backend 也容器化，讓它 log 進 Docker containers 目錄（最直覺）
2. 改 Fluent Bit 新增 host log file input（tail 主機檔案），不只讀 Docker logs

---

### Q5. Jaeger 只有 `jaeger-all-in-one`，找不到 API 請求

先釐清 Jaeger「service」的概念：
- `service` 不是 API 名稱
- `service` 是「應用服務名」（例如 `bancassurance`）
- API 路徑會出現在 span 的 `operationName`（例如 `http get /api/policies`）

如果只有 `jaeger-all-in-one`，通常是：

1. backend 沒啟動（或不是你以為那個進程）
2. backend 有啟動，但沒產生任何 HTTP 流量
3. backend trace 沒送到 collector（endpoint/設定錯誤）

快速檢查：

```bash
curl -sS http://localhost:16686/api/services | jq
curl -sS 'http://localhost:16686/api/traces?service=Bancassurance&lookback=1h&limit=20' | jq '.data | length'
```

如果 `service` 有值，再看 operationName：

```bash
curl -sS 'http://localhost:16686/api/traces?service=Bancassurance&lookback=1h&limit=5' \
  | jq -r '.data[]?.spans[]?.operationName'
```

---

## 3. 手動 Smoke Test（不靠自動化）

### §0 先決條件

1. 啟動資料庫：

```bash
docker compose -f docker-compose.yml up -d
```

2. 啟動 observability：

```bash
docker compose -f ops/infra/docker-compose.observability.yml up -d
```

3. 啟動 EFK：

```bash
docker compose -f ops/infra/docker-compose.efk.yml up -d
```

4. 啟動 backend：

```bash
SPRING_PROFILES_ACTIVE=docker ./mvnw spring-boot:run
```

### §1 Metrics / Grafana

1. Prometheus targets

```bash
curl -sS http://localhost:9090/api/v1/targets \
  | jq -r '.data.activeTargets[] | [.labels.job,.health,.lastError] | @tsv'
```

2. 確認關鍵 metrics 存在

```bash
curl -sS 'http://localhost:9090/api/v1/query?query=policy_change_total' | jq '.status,.data.result|length'
curl -sS 'http://localhost:9090/api/v1/query?query=http_server_requests_seconds_bucket' | jq '.status,.data.result|length'
```

3. Grafana UI
- 開 `http://localhost:13000`
- Dashboard:
  - `Bancassurance - Policy Change Overview`
  - `SpringBoot APM Dashboard`

### §2 Tracing / Jaeger

1. 先打一筆 API（手動用 Postman 或 curl）
2. Jaeger：
   - `http://localhost:16686`
   - Service 以 `http://localhost:16686/api/services` 回傳為準（常見 `Bancassurance` 或 `bancassurance`）
   - 看 `http ... /api/...` spans
3. 驗證 DB spans（SQL）：

```bash
curl -sS 'http://localhost:16686/api/traces?service=Bancassurance&lookback=1h&limit=20' \
  | jq -r '[.data[]?.spans[] | select(any(.tags[]?; .key=="db.system" or .key=="db.statement" or .key=="db.query.text")) | .operationName] | unique | .[]'
```

### §3 Logs / Elasticsearch / Kibana

1. 檢查 ES/Kibana 健康

```bash
curl -fsS http://localhost:9200/_cluster/health?pretty
curl -fsS http://localhost:5601/api/status | jq '.status.overall.level'
```

2. 套 ILM + data view（可重複）

```bash
bash ops/infra/scripts/setup-efk-ilm.sh
```

3. 查最新 logs

```bash
curl -fsS 'http://localhost:9200/bancassurance-logs-*/_search' \
  -H 'Content-Type: application/json' \
  -d '{"size":5,"sort":[{"@timestamp":{"order":"desc"}}],"query":{"range":{"@timestamp":{"gte":"now-15m"}}}}' \
  | jq '.hits.total,.hits.hits[]._source | {ts:."@timestamp",platform,environment,log}'
```

4. 目前限制提醒
- 如果 backend 不是 Docker container，這裡查不到 backend API log 屬正常現象（不是 Elastic 壞掉）。
