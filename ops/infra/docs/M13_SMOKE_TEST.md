# M13 Smoke Test (Draft) — EFK Platform + Fluent Bit

完成日期：2026-05-11（Draft）

本文件驗證：
- `ops/infra/docker-compose.efk.yml` 可啟動 Elasticsearch / Kibana / Fluent Bit
- Elasticsearch 與 Kibana 健康狀態
- Fluent Bit 能把 Docker logs 送進 `bancassurance-logs-*` index

## 1) 啟動 EFK

```bash
docker compose -f ops/infra/docker-compose.efk.yml up -d
docker compose -f ops/infra/docker-compose.efk.yml ps
```

預期：
- `bancassurance-elasticsearch` 為 `healthy`
- `bancassurance-kibana` 為 `running`
- `bancassurance-fluent-bit` 為 `running`

## 2) Elasticsearch health 檢查

```bash
curl -fsS http://localhost:9200 | jq
curl -fsS "http://localhost:9200/_cluster/health?pretty"
```

預期：
- `cluster_name` 與 `version.number` 正常回傳
- `number_of_nodes: 1`
- `status` 為 `yellow` 或 `green`（single-node 常見 `yellow`）

## 3) Kibana health 檢查

```bash
curl -fsS http://localhost:5601/api/status | jq '.status.overall.level'
```

預期：
- 回傳 `"available"`（啟動初期短暫 `"degraded"` 可接受）

Web UI：
- 開啟 [http://localhost:5601](http://localhost:5601)

## 4) Fluent Bit shipping 檢查

先產生一筆可被 Docker logging driver 捕捉的測試 log：

```bash
docker run --rm --name efk-smoke-log alpine:3.20 \
  sh -c 'echo "m13 smoke log $(date -Iseconds)"'
```

查詢 index：

```bash
curl -fsS "http://localhost:9200/_cat/indices/bancassurance-logs-*?v"
```

預期：
- 出現 `bancassurance-logs-YYYY.MM.DD` 類型索引

可再抽樣查詢文件：

```bash
curl -fsS "http://localhost:9200/bancassurance-logs-*/_search" \
  -H "Content-Type: application/json" \
  -d '{"size":1,"sort":[{"@timestamp":{"order":"desc"}}],"query":{"match_all":{}}}' | jq '.hits.total'
```

預期：
- `hits.total` 大於 0

## 5) 清理（可選）

```bash
docker compose -f ops/infra/docker-compose.efk.yml down -v
```

## 注意事項（dev-only）

- 目前 compose 內 `xpack.security.enabled=false`、`XPACK_SECURITY_ENABLED=false` 僅供本地開發使用。
- 上線前必須啟用 Elasticsearch / Kibana 安全機制（TLS、帳密、權限）。
