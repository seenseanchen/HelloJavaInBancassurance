# M12 Smoke Test Draft — Observability Platform (Jaeger + OTel Collector + Prometheus + Grafana)

建立日期：2026-05-11

本文件是 M12 最小可運行 smoke test 草稿，目標先確保平台服務可起、UI 可開、資料鏈路可基本驗證。

## 1. 啟動服務

```bash
docker compose -f ops/infra/docker-compose.observability.yml up -d
```

檢查容器狀態：

```bash
docker compose -f ops/infra/docker-compose.observability.yml ps
```

預期：`bancassurance-jaeger`、`bancassurance-otel-collector`、`bancassurance-prometheus`、`bancassurance-grafana` 都是 `Up`。

## 2. UI 與入口

- Jaeger UI: [http://localhost:16686](http://localhost:16686)
- Prometheus UI: [http://localhost:9090](http://localhost:9090)
- Grafana UI: [http://localhost:3000](http://localhost:3000)
  - 預設帳密：`admin / admin`

## 3. 基本驗證

### 3.1 OTel Collector receiver

```bash
# Collector metrics endpoint
curl -s http://localhost:8888/metrics | head
```

預期：回傳 Prometheus 格式 metrics 文字，代表 collector 正常對外。

### 3.2 Prometheus targets

打開 `http://localhost:9090/targets`，預期：
- `prometheus` job = `UP`
- `otel-collector` job = `UP`
- `bancassurance-backend` job
  - 若後端未啟動或未開 `/actuator/prometheus`，會 `DOWN`（可接受）
  - 若後端已啟動且 endpoint 可抓，應 `UP`

### 3.3 Grafana datasource provisioning

登入 Grafana 後，進 `Connections -> Data sources`，預期看到：
- `Prometheus`（URL 指向 `http://prometheus:9090`）

### 3.4 Jaeger traces（最小驗證）

若你的 backend（或任一 client）有把 OTLP trace 送到 `http://localhost:4317`（gRPC）或 `http://localhost:4318`（HTTP），
打開 Jaeger UI 後應該能在 service 清單看到對應服務並查到 trace。

## 4. 常用指令

```bash
# 看 collector log
docker logs -f bancassurance-otel-collector

# 關閉服務
docker compose -f ops/infra/docker-compose.observability.yml down

# 若要連同資料卷一起清掉
docker compose -f ops/infra/docker-compose.observability.yml down -v
```

## 5. 已知限制（MVP）

- 目前 Prometheus 對 backend scrape 先採靜態目標 `host.docker.internal:8080/actuator/prometheus`。
- backend 若開啟 Spring Security 於 `/actuator/**`，需另外配置可供 Prometheus 抓取的認證策略。
- 目前只配置 trace pipeline（OTel Collector -> Jaeger），未加入 logs backend 與進階 dashboards。
