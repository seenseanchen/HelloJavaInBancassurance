# Frontend (M16)

Vue 3 + Vite + TypeScript + Pinia + Vue Router + Tailwind + Element Plus。

## Prerequisites

- Node.js 20+
- pnpm（本專案已固定使用 pnpm）

## Install

```bash
pnpm install
```

## Run (Dev)

```bash
pnpm dev --host 127.0.0.1 --port 5173
```

Dev server 預設網址：`http://127.0.0.1:5173`

## Build

```bash
pnpm run build
```

## M16 Login

- 登入策略：
  - 先嘗試後端 `/api/auth/login`（JWT）
  - 失敗時才 fallback 到本地 mock 帳號
- 可用帳密（本地 mock）：
  - `demo / demo`
  - `underwriter / 1234`
  - `agent / 1234`
- 可用帳密（後端 JWT，需啟動 backend）：
  - `underwriter01 / uw123`
  - `csr01 / csr123`
  - `admin / admin123`
- 路由守衛：
  - 未登入訪問 `/home` 會導回 `/login`
  - 已登入訪問 `/login` 會導到 `/home`
  - `/dashboard` 會導向 `/home`（相容 `docs/PLAN.md` 命名）

## API Proxy

`vite.config.ts` 已設定：

- `/api/*` -> `http://localhost:8080`

## Key Files

- `src/router/index.ts`: 路由與 `beforeEach` 守衛
- `src/stores/auth.ts`: Auth Store（login/logout/localStorage hydrate）
- `src/api/http.ts`: axios instance + request/response interceptors
- `src/styles/tokens.css`: Cathay 設計 token + Element Plus 主題色覆蓋
- `src/components/BaseCard.vue`: 卡片風格基礎元件

## Verification

完整驗收步驟請見：

- `../docs/M16_SMOKE_TEST.md`
