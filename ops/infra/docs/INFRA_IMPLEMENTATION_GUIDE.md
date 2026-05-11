# Infra Implementation Guide (M11-M13)

本文件說明本專案 Infra 是如何實作、各元件在做什麼、以及哪些參數可以調整。

## 1. 目標與範圍

目前已落地範圍：
- M11: Nginx Gateway（反向代理）
- M12: Observability（Jaeger + OTel Collector + Prometheus + Grafana）
- M13: EFK（Elasticsearch + Kibana + Fluent Bit）

不在本文件範圍：
- Jenkins CI/CD（M14）
- n8n workflow（M15）

## 2. 檔案佈局（集中管理）

所有 Infra 工程檔已集中到 `ops/infra/`：

```text
ops/infra/
├── docker-compose.infra.yml
├── docker-compose.observability.yml
├── docker-compose.efk.yml
├── config/
│   ├── nginx/
│   ├── otel/
│   ├── prometheus/
│   ├── grafana/
│   └── efk/
└── docs/
    ├── M11_SMOKE_TEST.md
    ├── M12_SMOKE_TEST.md
    ├── M13_SMOKE_TEST.md
    └── INFRA_IMPLEMENTATION_GUIDE.md
```

## 3. 實作架構與資料流

### 3.1 Gateway（M11）

- 對外入口：`http://localhost:8081`
- 反代規則：`/api/* -> host.docker.internal:8080`
- 標頭轉發：`X-Forwarded-For` / `X-Forwarded-Proto` / `X-Forwarded-Host` / `X-Real-IP`
- 健康檢查：`/healthz`

Java 配合設定：
- `server.forward-headers-strategy: framework`

### 3.2 Observability（M12）

- OTel Collector 接收 OTLP：`4317(gRPC)` / `4318(HTTP)`
- Collector 轉送 trace 到 Jaeger
- Prometheus 抓：
  - `prometheus` 自身
  - `otel-collector` metrics
  - `host.docker.internal:8080/actuator/prometheus`（backend）
- Grafana 以 provisioning 預載 Prometheus datasource

Java 配合設定：
- `spring-boot-starter-opentelemetry`
- `spring-boot-starter-actuator`
- `micrometer-registry-prometheus`
- `management.endpoints.web.exposure.include=health,info,metrics,prometheus`
- `management.tracing.sampling.probability=1.0`（dev）

### 3.3 Logging（M13）

- Fluent Bit tail Docker container logs
- 送往 Elasticsearch（`bancassurance-logs-*`）
- Kibana 用來查詢與可視化

Java 配合設定：
- `logback-spring.xml`
  - dev: 可讀文字 log
  - docker/prod: JSON structured logging

## 4. 啟動與關閉

```bash
# M11 Gateway
docker compose -f ops/infra/docker-compose.infra.yml up -d

# M12 Observability
docker compose -f ops/infra/docker-compose.observability.yml up -d

# M13 EFK
docker compose -f ops/infra/docker-compose.efk.yml up -d
```

```bash
# 關閉
docker compose -f ops/infra/docker-compose.infra.yml down
docker compose -f ops/infra/docker-compose.observability.yml down
docker compose -f ops/infra/docker-compose.efk.yml down
```

## 5. 參數調整建議（重點）

### 5.1 Nginx

| 參數 | 目前值 | 何時調整 | 建議 |
|---|---:|---|---|
| Gateway port | `8081` | 與本機服務衝突 | 改成未占用 port（如 `18081`） |
| Upstream | `host.docker.internal:8080` | Linux 原生 Docker | 改 `extra_hosts` 或改用同 network service 名稱 |
| TLS cert path | `./config/nginx/certs` | 啟用 HTTPS（SEE-13） | 放 `fullchain.pem` / `privkey.pem` |

### 5.2 OTel + Jaeger + Prometheus + Grafana

| 參數 | 目前值 | 何時調整 | 建議 |
|---|---:|---|---|
| Jaeger image | `jaegertracing/all-in-one:1.76.0` | 版本升級 | 鎖定 minor、升級前先 smoke |
| Collector image | `otel/opentelemetry-collector-contrib:latest` | 穩定性需求 | 建議改固定 tag，避免 latest 破壞 |
| Sampling | `1.0` | 近似 production 流量 | 降至 `0.1` 或更低 |
| Backend scrape target | `host.docker.internal:8080` | backend 要求認證 | 開內網白名單或加 basic auth |
| Grafana admin | `admin/admin` | 團隊共用或正式環境 | 改強密碼並改由 secret 注入 |

### 5.3 EFK

| 參數 | 目前值 | 何時調整 | 建議 |
|---|---:|---|---|
| ES heap | `-Xms512m -Xmx512m` | 索引量增長、查詢慢 | 增至 `1g`（看機器 RAM） |
| ES security | `false` | 非本機開發 | 正式環境必須啟用 TLS + auth |
| Fluent Bit `Mem_Buf_Limit` | `50MB` | log burst/drop | 提高至 `100-200MB` 並搭配 backpressure |
| Index prefix | `bancassurance-logs` | 多環境分流 | 加環境尾碼（例 `bancassurance-dev-logs`） |

## 6. 目前已知待辦

- Prometheus 抓 backend metrics 目前是 `401`（`/actuator/**` 受 Spring Security 保護）
- EFK 尚未加 ILM / retention 策略
- OTel Collector 建議改固定版本 tag（避免 latest 漂移）

## 7. 建議下一步

1. 先解 backend metrics 授權（讓 Prometheus backend target 變 `UP`）
2. 補 M11 TLS（SEE-13）
3. 補 M13 ILM policy（SEE-23）
