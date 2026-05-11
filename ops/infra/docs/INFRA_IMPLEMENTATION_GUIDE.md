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
├── scripts/
│   └── setup-efk-ilm.sh
├── config/
│   ├── nginx/
│   ├── otel/
│   ├── prometheus/
│   ├── grafana/
│   │   └── provisioning/
│   │       └── dashboards/
│   └── efk/
└── docs/
    ├── M11_SMOKE_TEST.md
    ├── M12_SMOKE_TEST.md
    ├── M13_SMOKE_TEST.md
    └── INFRA_IMPLEMENTATION_GUIDE.md
```

## 3. 實作架構與資料流

### 3.1 Gateway（M11）

- 對外入口（HTTP）：`http://localhost:18081`（會 301 轉址到 HTTPS）
- 對外入口（HTTPS）：`https://localhost:18443`（dev self-signed）
- 反代規則：`/api/* -> host.docker.internal:8080`
- 標頭轉發：`X-Forwarded-For` / `X-Forwarded-Proto` / `X-Forwarded-Host` / `X-Real-IP`
- 健康檢查：`/healthz`
- dev cert 生成：`bash ops/infra/scripts/generate-local-certs.sh`

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
- Grafana 以 provisioning 預載 dashboard：
  - `Bancassurance - Policy Change Overview`（自製）
  - `SpringBoot APM Dashboard`（Grafana.com ID 12900）
- Grafana host port：`13000`（container 內仍是 `3000`）

Java 配合設定：
- `spring-boot-starter-opentelemetry`
- `datasource-micrometer-spring-boot` + `datasource-micrometer-opentelemetry`（JDBC spans）
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
| Gateway port | `18081` | 與本機服務衝突 | 改成未占用 port（如 `28081`） |
| Gateway TLS port | `18443` | 與本機服務衝突 | 改成未占用 port（如 `28443`） |
| Upstream | `host.docker.internal:8080` | Linux 原生 Docker | 改 `extra_hosts` 或改用同 network service 名稱 |
| TLS cert path | `./config/nginx/certs` | 啟用 HTTPS（SEE-13） | 放 `fullchain.pem` / `privkey.pem` |

### 5.2 OTel + Jaeger + Prometheus + Grafana

| 參數 | 目前值 | 何時調整 | 建議 |
|---|---:|---|---|
| Jaeger image | `jaegertracing/all-in-one:1.76.0` | 版本升級 | 鎖定 minor、升級前先 smoke |
| Collector image | `otel/opentelemetry-collector-contrib:0.151.0` | 版本升級 | 固定 tag，升版前先 smoke |
| Sampling | `1.0` | 近似 production 流量 | 降至 `0.1` 或更低 |
| Backend scrape target | `host.docker.internal:8080` | 認證策略改動時 | 保持 `GET /actuator/prometheus` 給 Prometheus 專用放行，其他 actuator 維持保護 |
| Grafana host port | `13000` | 與本機服務衝突 | 改成未占用 port（如 `23000`） |
| Grafana admin | `admin/admin` | 團隊共用或正式環境 | 改強密碼並改由 secret 注入 |

### 5.3 EFK

| 參數 | 目前值 | 何時調整 | 建議 |
|---|---:|---|---|
| ES heap | `-Xms512m -Xmx512m` | 索引量增長、查詢慢 | 增至 `1g`（看機器 RAM） |
| ES security | `false` | 非本機開發 | 正式環境必須啟用 TLS + auth |
| Fluent Bit `Mem_Buf_Limit` | `50MB` | log burst/drop | 提高至 `100-200MB` 並搭配 backpressure |
| Index prefix | `bancassurance-logs` | 多環境分流 | 加環境尾碼（例 `bancassurance-dev-logs`） |

## 6. 目前完成狀態（2026-05-12）

- ✅ 2026-05-11：已放行 `GET /actuator/prometheus`，Prometheus backend scrape 不再受 `401` 阻擋
- ✅ 2026-05-12：M12 dashboard provisioning 完成（自製 + 官方），Prometheus target 全 `up`
- ✅ 2026-05-12：Jaeger 可查到 `Bancassurance` traces，且有 SQL/DB 子 span
- ✅ 2026-05-12：M12 smoke 驗證 412 / 409 / 422 已可重現
- ✅ 2026-05-12：M13 ILM + template + Kibana data view 腳本化（`ops/infra/scripts/setup-efk-ilm.sh`）
- ✅ 2026-05-12：OTel Collector image 已改固定 tag `0.151.0`

## 7. 建議下一步

1. 進入 M14（Jenkins CI/CD）或 M16（Frontend）主線任務
2. 把 `admin/admin`、ES 無安全模式等 dev-only 設定整理成 production hardening 清單
3. 規劃 compose `project name`，避免多 stack 共享專案名稱造成 orphan / DNS 混淆
