# M13 Smoke Test — EFK Platform + ILM

完成日期：2026-05-11（更新）

## §0 變數約定

| 變數 | 說明 | 範例值 |
|---|---|---|
| `{{host}}` | Elasticsearch base URL | `http://localhost:9200` |
| `{{kibanaHost}}` | Kibana base URL | `http://localhost:5601` |

```bash
export host="http://localhost:9200"
export kibanaHost="http://localhost:5601"
```

## 1) 啟動 EFK

```bash
docker compose -f ops/infra/docker-compose.efk.yml up -d
docker compose -f ops/infra/docker-compose.efk.yml ps
```

預期：
- `bancassurance-elasticsearch` 為 `healthy`
- `bancassurance-kibana` 為 `running`
- `bancassurance-fluent-bit` 為 `running`

## 2) Elasticsearch / Kibana 健康檢查

```bash
curl -fsS "$host" | jq '.version.number'
curl -fsS "$host/_cluster/health?pretty"
curl -fsS "$kibanaHost/api/status" | jq '.status.overall.level'
```

預期：
- ES `number_of_nodes: 1`
- cluster `status` 為 `yellow` 或 `green`
- Kibana status 為 `"available"`（啟動初期短暫 `degraded` 可接受）

## 3) 產生測試 log 並驗證索引

```bash
docker run --rm --name efk-smoke-log alpine:3.20 \
  sh -c 'for i in 1 2 3; do echo "m13 smoke log $(date -Iseconds) #$i"; sleep 2; done'

curl -fsS "$host/_cat/indices/bancassurance-logs-*?v"

curl -fsS "$host/bancassurance-logs-*/_search" \
  -H "Content-Type: application/json" \
  -d '{"size":1,"sort":[{"@timestamp":{"order":"desc"}}],"query":{"match_all":{}}}' | jq '.hits.total'
```

預期：
- 出現 `bancassurance-logs-YYYY.MM.DD` 索引
- `hits.total` 大於 0

## 4) 套用 ILM policy + Index Template + Kibana Data View

```bash
bash ops/infra/scripts/setup-efk-ilm.sh
```

此腳本會建立：
- ILM policy：`bancassurance-logs-ilm`（hot 0d → warm 7d → delete 30d）
- index template：`bancassurance-logs-template`（套用到 `bancassurance-logs-*`）
- Kibana data view：`bancassurance-logs-*`

## 5) 驗證 ILM 生效狀態

```bash
curl -fsS "$host/_ilm/policy/bancassurance-logs-ilm?pretty"
curl -fsS "$host/bancassurance-logs-*/_ilm/explain?human" | jq '.indices'
```

預期：
- policy 查得到 `hot / warm / delete` 三階段
- `_ilm/explain` 回傳 `managed: true`，且 `policy` 為 `bancassurance-logs-ilm`

## 6) Kibana Data View 驗證

```bash
curl -fsS "$kibanaHost/api/data_views" \
  -H "kbn-xsrf: true" | jq -r '.data_view[]?.title'
```

預期：
- 列表包含 `bancassurance-logs-*`

## 7) 清理（可選）

```bash
docker compose -f ops/infra/docker-compose.efk.yml down -v
```

## 注意事項（dev-only）

- `xpack.security.enabled=false`、`XPACK_SECURITY_ENABLED=false` 僅供本地開發使用。
- 正式環境必須開啟 Elasticsearch / Kibana 安全機制（TLS、帳密、權限）。
