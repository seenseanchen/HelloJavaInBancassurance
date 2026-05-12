# M16 Smoke Test — Vue 骨架 + Design Token + Login Flow

完成於 2026-05-12。本文件涵蓋：
- M16-B：Vue 3 + Vite + TypeScript + Pinia + Router scaffold
- M16-C：Tailwind + Element Plus + Cathay token 套用
- M16-D：Login flow + Auth Store + 路由守衛 + axios 401 自動登出

---

## §0 變數約定

| 變數 | 意義 | 範例值 |
|---|---|---|
| `{{frontend_host}}` | 前端 dev server URL | `http://127.0.0.1:5173` |
| `{{api_host}}` | 後端 API base URL | `http://localhost:8080` |
| `{{login_user}}` | mock 帳號 | `demo` |
| `{{login_password}}` | mock 密碼 | `demo` |

```bash
export frontend_host="http://127.0.0.1:5173"
export api_host="http://localhost:8080"
export login_user="demo"
export login_password="demo"
```

---

## §1 環境前置

```bash
# 1) 後端先啟動（M9 已有 Spring Security）
./mvnw spring-boot:run

# 2) 前端安裝依賴（已切換 pnpm）
cd frontend
pnpm install
```

---

## §2 啟動與編譯驗證

```bash
# 1) 型別檢查 + production build
pnpm run build
# 預期：build 成功 (exit code 0)

# 2) 啟動前端 dev server
pnpm dev --host 127.0.0.1 --port 5173
# 預期看到：
# VITE v8.x ready
# Local: http://127.0.0.1:5173/
```

---

## §3 Vite Proxy 驗證（/api → backend）

```bash
curl -i "$frontend_host/api/health"
```

預期：
- HTTP status 為後端實際回應（目前會是 `401 Unauthorized`，因為後端 security 設定）
- 回應 header 可看到後端產生的 `x-trace-id`
- 重點是「不是 404 / 502」，代表 proxy 已轉發成功

---

## §4 Login Flow + 路由守衛驗收

> 建議用瀏覽器操作；截圖可省略（SEE-37 規格允許）。

### §4.1 未登入直接開 `/home`

1. 開啟 `{{frontend_host}}/home`
2. 預期被導向 `/login?redirect=/home`

### §4.2 正向登入

1. 在 Login 頁輸入：
   - username: `demo`
   - password: `demo`
2. 點「立即登入」
3. 預期導向 `/home`

備註：
- 本地 mock 也可用 `underwriter / 1234` 或 `agent / 1234` 驗證。
- 若後端已啟動，亦可用 JWT 帳號 `underwriter01 / uw123`、`csr01 / csr123`、`admin / admin123`。

### §4.3 反向登入（帳密錯誤）

1. 密碼改成錯誤值（例如 `wrong`）
2. 點「立即登入」
3. 預期：
   - 留在 `/login`
   - 顯示 Element Plus 錯誤訊息：`帳號或密碼錯誤`

### §4.4 登出與守衛回歸

1. 在 `/home` 點「登出」
2. 預期導向 `/login`
3. 再手動輸入 `{{frontend_host}}/home`
4. 預期再次被踢回 `/login`

### §4.6 `/dashboard` 相容路由驗證

1. 登入後手動訪問 `{{frontend_host}}/dashboard`
2. 預期導向 `/home`

### §4.5 localStorage 驗證

1. 登入後打開 DevTools → Application → Local Storage
2. 預期存在 key：`banca.auth`
3. 登出後預期 key 被清除

---

## §5 UI Style 驗收清單（Cathay 風）

### §5.1 主色驗收

- [ ] Login 頁「立即登入」按鈕是 Cathay green (`#00a160`)
- [ ] Home 頁 header 的輔助底色為淡綠 (`#e6f7ee`)
- [ ] `--el-color-primary` 已覆寫為 `#00a160`

### §5.2 字級驗收

- [ ] Login 主標使用 `text-h3`
- [ ] Hero/品牌區標題使用 `text-h2`
- [ ] 次要文字使用 `text-caption`

### §5.3 間距與卡片驗收

- [ ] `BaseCard.vue` 有 `rounded-lg`（16px）+ `shadow-card`
- [ ] 卡片內距為 `p-6`（24px）
- [ ] 區塊間距有 `space-y-6`（24px）/ `py-8`（32px）等層級

### §5.4 Responsive 驗收

- [ ] `< 768px`：Login 頁為單欄
- [ ] `>= 768px`：Login 頁切為雙欄（品牌區 + 表單區）

---

## §6 面試話術

### §6.1 Composition API 心智模型（中級）

> 「我把狀態與行為以『功能聚合』來拆，而不是 Vue 2 options API 的 data/method/computed 分散寫法。  
> 例如 `useAuthStore` 直接把 `token/user/isAuthenticated/login/logout` 放在一起，邏輯共置、可抽共用 composable，也更好測試。」

### §6.2 Pinia vs Vuex（中級）

> 「Pinia 是 Vue 官方推薦的新一代狀態管理：語法更接近 Composition API、型別推導更自然，不必寫一堆 mutation boilerplate。  
> Vuex 強在歷史生態，但在 Vue 3 專案我優先選 Pinia，開發體驗跟可維護性更好。」

### §6.3 Vite proxy 為何省 CORS（中級）

> 「開發時前端打 `http://127.0.0.1:5173/api/*`，Vite dev server 會在 server 端轉發到 `http://localhost:8080`。  
> 對瀏覽器來說請求同源，因此不用在每個開發階段都處理跨域 preflight。  
> 到正式環境再交由 Nginx 反向代理整合前後端入口。」

---

## §7 完成檢查單

- [ ] `pnpm run build` 成功
- [ ] `{{frontend_host}}/api/health` 能打到後端（非 404/502）
- [ ] `demo / demo` 可登入並導向 `/home`
- [ ] 未登入訪問 `/home` 會被導回 `/login`
- [ ] 點 logout 後 localStorage 清除、再訪 `/home` 會再被導回 `/login`
- [ ] `BaseCard` 呈現正確 radius + shadow
- [ ] Element Plus 主色已變更為 Cathay green
