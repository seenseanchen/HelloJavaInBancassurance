#!/usr/bin/env bash
set -euo pipefail

ES_URL="${ES_URL:-http://localhost:9200}"
KIBANA_URL="${KIBANA_URL:-http://localhost:5601}"

POLICY_NAME="${POLICY_NAME:-bancassurance-logs-ilm}"
TEMPLATE_NAME="${TEMPLATE_NAME:-bancassurance-logs-template}"
INDEX_PATTERN="${INDEX_PATTERN:-bancassurance-logs-*}"
DATA_VIEW_NAME="${DATA_VIEW_NAME:-bancassurance-logs}"

echo "[1/5] wait Elasticsearch..."
curl -fsS "${ES_URL}/_cluster/health?wait_for_status=yellow&timeout=60s" >/dev/null

echo "[2/5] upsert ILM policy: ${POLICY_NAME}"
curl -fsS -X PUT "${ES_URL}/_ilm/policy/${POLICY_NAME}" \
  -H "Content-Type: application/json" \
  -d '{
    "policy": {
      "phases": {
        "hot": {
          "min_age": "0ms",
          "actions": {
            "set_priority": { "priority": 100 }
          }
        },
        "warm": {
          "min_age": "7d",
          "actions": {
            "set_priority": { "priority": 50 }
          }
        },
        "delete": {
          "min_age": "30d",
          "actions": {
            "delete": {}
          }
        }
      }
    }
  }' >/dev/null

echo "[3/5] upsert index template: ${TEMPLATE_NAME}"
curl -fsS -X PUT "${ES_URL}/_index_template/${TEMPLATE_NAME}" \
  -H "Content-Type: application/json" \
  -d "{
    \"index_patterns\": [\"${INDEX_PATTERN}\"],
    \"priority\": 200,
    \"template\": {
      \"settings\": {
        \"index.lifecycle.name\": \"${POLICY_NAME}\",
        \"number_of_shards\": 1,
        \"number_of_replicas\": 0
      }
    }
  }" >/dev/null

echo "[4/5] apply policy to existing indices (if any)"
if curl -fsS "${ES_URL}/_cat/indices/${INDEX_PATTERN}?h=index" | grep -q .; then
  curl -fsS -X PUT "${ES_URL}/${INDEX_PATTERN}/_settings" \
    -H "Content-Type: application/json" \
    -d "{
      \"index\": {
        \"lifecycle\": {
          \"name\": \"${POLICY_NAME}\"
        }
      }
    }" >/dev/null
else
  echo "  no existing indices matched ${INDEX_PATTERN}, skip."
fi

echo "[5/5] upsert Kibana data view: ${DATA_VIEW_NAME}"
# Kibana cold start can return 503 for a short time.
for i in $(seq 1 30); do
  if curl -fsS "${KIBANA_URL}/api/status" >/dev/null 2>&1; then
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "  Kibana is not ready after 60s: ${KIBANA_URL}/api/status" >&2
    exit 1
  fi
  sleep 2
done

curl -fsS -X POST "${KIBANA_URL}/api/data_views/data_view" \
  -H "Content-Type: application/json" \
  -H "kbn-xsrf: true" \
  -d "{
    \"override\": true,
    \"data_view\": {
      \"name\": \"${DATA_VIEW_NAME}\",
      \"title\": \"${INDEX_PATTERN}\",
      \"timeFieldName\": \"@timestamp\"
    }
  }" >/dev/null

echo
echo "ILM and data view setup done."
echo "Policy:   ${POLICY_NAME}"
echo "Template: ${TEMPLATE_NAME}"
echo "Pattern:  ${INDEX_PATTERN}"
echo
echo "Check:"
echo "  curl -fsS '${ES_URL}/_ilm/policy/${POLICY_NAME}?pretty'"
echo "  curl -fsS '${ES_URL}/${INDEX_PATTERN}/_ilm/explain?human' | jq"
