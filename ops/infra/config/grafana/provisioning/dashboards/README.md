# Grafana Dashboards Provisioning

本目錄透過檔案方式自動載入 dashboard：

- provider: `dashboard-provider.yml`
- dashboard JSON: `json/*.json`

目前內建：

- `bancassurance-policy-change.json`
  - Policy change RPS
  - Policy change success ratio
  - HTTP p99 latency
  - JVM heap used

若要補官方 Spring Boot dashboard（SEE-19），可把匯出的 JSON 放進 `json/`，
重啟 Grafana 後會自動載入到 `Bancassurance` folder。
