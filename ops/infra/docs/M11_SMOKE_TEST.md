# M11 Smoke Test — Gateway (Nginx Reverse Proxy)

完成日期：2026-05-11

## 1) 目的

驗證 M11 Gateway 基礎層可正常運作：
- Nginx HTTP 入口可用
- `/api/` 可反向代理到 `host.docker.internal:8080`
- 轉發標頭（`X-Forwarded-For` / `X-Forwarded-Proto` / `X-Forwarded-Host` / `X-Real-IP`）可傳遞給後端

## 2) 前置條件

1. 後端 Spring Boot 已啟動在本機 `8080`
2. Docker / Docker Compose 可用

## 3) 啟動 Gateway

```bash
cd /Users/livebreeze/Documents/Claude/Projects/HelloJavaInBancassurance
docker compose -f ops/infra/docker-compose.infra.yml up -d
```

確認容器狀態：

```bash
docker compose -f ops/infra/docker-compose.infra.yml ps
```

預期：`m11-gateway` 為 `Up`，並對外提供 `0.0.0.0:8081->80/tcp`。

## 4) 驗證步驟

### 4.1 Nginx 自身健康檢查

```bash
curl -i http://localhost:8081/healthz
```

預期：
- HTTP status `200`
- body 為 `ok`

### 4.2 反向代理到後端 `/api/`

```bash
curl -i http://localhost:8081/api/actuator/health
```

預期：
- 未帶認證時，HTTP status `401`（目前 `/actuator/**` 受 Spring Security 保護）
- 若用已授權身分呼叫，則回應 `200` 並包含 `{"status":"UP"}`

### 4.3 檢查代理路徑行為

```bash
curl -i http://localhost:8081/
```

預期：
- HTTP status `404`（僅開放 `/api/` 與 `/healthz`）

## 5) 關閉 Gateway

```bash
docker compose -f ops/infra/docker-compose.infra.yml down
```

## 6) TLS 預留（SEE-13）

目前僅先預留，不啟用：
- `ops/infra/docker-compose.infra.yml` 已掛載 `./config/nginx/certs:/etc/nginx/certs:ro`
- `ops/infra/config/nginx/conf.d/default.conf` 已保留 `listen 443 ssl` 與 `ssl_certificate` 註解

後續 SEE-13 只需：
1. 放入實際憑證檔（例如 `fullchain.pem`, `privkey.pem`）到 `ops/infra/config/nginx/certs/`
2. 開啟 compose 的 `8443:443` port mapping
3. 解除 Nginx TLS 註解並 reload
