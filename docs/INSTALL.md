# INSTALL.md — macOS 開發環境建置

> 目標：讓你 30 分鐘內能跑起一支 Spring Boot Hello World，並連上本機 PostgreSQL。

---

## 0. 必要工具總覽

| 工具 | 用途 | 安裝方式 |
|---|---|---|
| Homebrew | macOS 套件管理器 | 必裝 |
| SDKMAN! | Java 版本管理 | 強烈推薦 |
| JDK 21 (Temurin) | Java 編譯/執行環境 | 透過 SDKMAN |
| Maven 3.9+ | 建置工具 | 透過 SDKMAN |
| IntelliJ IDEA Community | IDE | 免費版即可 |
| Docker Desktop | 跑 PostgreSQL | 必裝 |
| Postman | 測 API | 已知你會用 |

---

## 1. 安裝 Homebrew (若尚未安裝)

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

驗證：
```bash
brew --version
```

---

## 2. 安裝 SDKMAN! (Java 版本管理神器)

> **為什麼用 SDKMAN 而不直接 `brew install openjdk`?**
> 將來你會接觸到不同公司用 Java 8、11、17、21，用 SDKMAN 可以一行切換版本：
> `sdk use java 21.0.x-tem` — 對面試/接專案非常實用。

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
```

驗證：
```bash
sdk version
```

---

## 3. 透過 SDKMAN 安裝 JDK 21 與 Maven

```bash
# 列出可用的 Java 21 LTS 版本
sdk list java | grep "21\." | head -20

# 安裝 Eclipse Temurin (Adoptium) 21 — 業界最流行的 OpenJDK 發行版
sdk install java 21.0.5-tem

# 設為預設
sdk default java 21.0.5-tem

# 安裝 Maven
sdk install maven
```

> **小常識**：`tem` 是 Temurin 的縮寫；其他常見發行版有 `amzn` (Amazon Corretto)、`zulu`、`oracle`。
> 銀行業多半要求商業支援，所以 Corretto / Temurin / Oracle JDK 都常見。

驗證：
```bash
java --version
# 應顯示: openjdk 21.0.5 ...

javac --version
mvn --version
# 應顯示 Maven 3.9.x，runtime 指向 21
```

設定 `JAVA_HOME` (SDKMAN 會自動處理，但確認一下)：
```bash
echo $JAVA_HOME
# 應該是 ~/.sdkman/candidates/java/current
```

---

## 4. 安裝 IntelliJ IDEA Community Edition

選一種：

**A. Homebrew (推薦)**
```bash
brew install --cask intellij-idea-ce
```

**B. 官網下載**
- https://www.jetbrains.com/idea/download/?section=mac

第一次開啟後建議的設定：
1. **Settings → Build → Build Tools → Maven**：確認 Maven home directory 指到 `~/.sdkman/candidates/maven/current`
2. **Settings → Build → Build Tools → Maven → Runner**：JRE 選 21
3. **Plugins** 安裝：Lombok (內建)、Spring Boot (內建)、Database Navigator (選配)
4. **Settings → Editor → Code Style → Java**：Tab size = 4、Continuation indent = 8 (Google Java Style)
5. **啟用 Annotation Processing**：Settings → Build → Compiler → Annotation Processors → Enable

> 💡 為什麼要 Enable Annotation Processing？
> 因為 Lombok / MapStruct / Spring 的某些功能仰賴編譯期生成程式碼。

---

## 5. 安裝 Docker Desktop

```bash
brew install --cask docker
```

啟動 Docker Desktop (第一次要開 GUI 同意條款)，驗證：
```bash
docker --version
docker compose version
```

---

## 6. 用 Docker 跑 PostgreSQL

在專案根目錄建立 `docker-compose.yml`(我會在 M0 完成時自動產生，這裡先給內容預覽)：

```yaml
services:
  postgres:
    image: postgres:16-alpine
    container_name: bancassurance-pg
    environment:
      POSTGRES_DB: bancassurance
      POSTGRES_USER: dev
      POSTGRES_PASSWORD: dev
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data

volumes:
  pgdata:
```

啟動：
```bash
cd /Users/livebreeze/Documents/Claude/Projects/HelloJavaInBancassurance
docker compose up -d
```

驗證：
```bash
docker ps
# 應看到 bancassurance-pg

# 連進去看一下
docker exec -it bancassurance-pg psql -U dev -d bancassurance -c "SELECT version();"
```

> **常見問題**：5432 port 被本機原生 PostgreSQL 占用？
> `lsof -i :5432` 找到 PID，用 `brew services stop postgresql` 停掉，或在 docker-compose 改成 `5433:5432`。

---

## 7. 安裝 Postman

```bash
brew install --cask postman
```

或下載：https://www.postman.com/downloads/

> M7 完成後，我會帶你從 Swagger 匯出 OpenAPI JSON 直接匯入 Postman，省去手動建 collection。

---

## 8. 驗證整體環境

跑這段指令，全綠就 OK：
```bash
echo "=== Java ==="; java --version
echo "=== Maven ==="; mvn --version
echo "=== Docker ==="; docker --version
echo "=== Postgres in Docker ==="; docker ps --filter "name=bancassurance-pg" --format "{{.Names}} {{.Status}}"
```

---

## 9. 推薦的 IDE 快捷鍵 (IntelliJ on macOS)

| 動作 | 快捷鍵 |
|---|---|
| 全域搜尋類別 | ⌘O |
| 全域搜尋檔案 | ⇧⌘O |
| 全域搜尋符號 | ⌥⌘O |
| Search Everywhere | ⇧⇧ (按兩下 Shift) |
| 重構改名 | ⇧F6 |
| 提取方法 | ⌥⌘M |
| 顯示快速文件 | F1 |
| 跳到實作 | ⌘⌥B |
| 顯示參數提示 | ⌘P |
| 執行 main | ⌃⇧R |

> 學會 `⇧⇧` (Search Everywhere) 跟 `⌘E` (Recent Files)，工程效率會大幅提升。

---

## 完成後請回報

跑完後告訴我以下三項輸出：

1. `java --version`
2. `mvn --version`
3. `docker ps`

我接著就帶你進入 **M1 — Spring Boot 骨架**。
