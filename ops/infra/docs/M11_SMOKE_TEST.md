# M11 Smoke Test — Gateway (Nginx Reverse Proxy)

完成日期：2026-05-11

## 1) 目的

驗證 M11 Gateway 基礎層可正常運作：
- Nginx HTTP 入口可用
- HTTP 會轉址到 HTTPS
- HTTPS 入口可用（self-signed cert）
- `/api/` 可反向代理到 `host.docker.internal:8080`
- 轉發標頭（`X-Forwarded-For` / `X-Forwarded-Proto` / `X-Forwarded-Host` / `X-Real-IP`）可傳遞給後端

## 2) 前置條件

1. 後端 Spring Boot 已啟動在本機 `8080`
2. Docker / Docker Compose 可用

## 3) 啟動 Gateway

```bash
cd /Users/livebreeze/Documents/Claude/Projects/HelloJavaInBancassurance
bash ops/infra/scripts/generate-local-certs.sh
docker compose -f ops/infra/docker-compose.infra.yml up -d
```

確認容器狀態：

```bash
docker compose -f ops/infra/docker-compose.infra.yml ps
```

預期：`m11-gateway` 為 `Up`，並對外提供：
- `0.0.0.0:18081->80/tcp`（HTTP）
- `0.0.0.0:18443->443/tcp`（HTTPS）

## 4) 驗證步驟

### 4.1 Nginx 自身健康檢查

```bash
curl -i http://localhost:18081/healthz
```

預期：
- HTTP status `200`
- body 為 `ok`

### 4.2 反向代理到後端 `/api/`

```bash
curl -i http://localhost:18081/api/actuator/health
```

預期：
- HTTP status `301`，`Location` 指向 `https://localhost:18443/...`

再測 HTTPS：

```bash
curl -k -i https://localhost:18443/api/actuator/health
```

預期：
- 若後端未啟動，HTTP status `502`（Nginx proxy 正常，但 upstream 不可達）
- 未帶認證時，HTTP status `401`（目前 `/actuator/**` 受 Spring Security 保護）
- 若用已授權身分呼叫，則回應 `200` 並包含 `{"status":"UP"}`

### 4.3 檢查代理路徑行為

```bash
curl -i http://localhost:18081/
```

預期：
- HTTP status `301`（轉址到 HTTPS）

## 5) 關閉 Gateway

```bash
docker compose -f ops/infra/docker-compose.infra.yml down
```

## 6) TLS（SEE-13）

目前已啟用 dev TLS（self-signed）：
- compose 掛載：`./config/nginx/certs:/etc/nginx/certs:ro`
- Nginx `listen 443 ssl`
- HTTP 入口自動轉址到 HTTPS

憑證維護：
- 生成：`bash ops/infra/scripts/generate-local-certs.sh`
- 強制重建：`bash ops/infra/scripts/generate-local-certs.sh --force`
