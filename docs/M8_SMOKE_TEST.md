# M8 Smoke Test — 整合測試 (Testcontainers)

## §0 變數約定

本階段不需 curl，全部測試以 `./mvnw test` 觸發。為了能單獨跑某支 test，列出常用的 Maven 指令變數：

| 變數 | 意義 | 範例值 |
|---|---|---|
| `{{class}}` | 測試類全名（`-Dtest=...`） | `PolicyOptimisticLockConcurrencyTest` |
| `{{method}}` | 單一方法（`-Dtest=Class#method`） | `PolicyChangeNegativeTest#changePaymentMethodToSinglePay_returns422` |

```bash
# 跑全部測試（推薦這條當每天的 sanity check）
./mvnw test

# 只跑某個 class
./mvnw test -Dtest=PolicyOptimisticLockConcurrencyTest

# 只跑某個方法
./mvnw test -Dtest='PolicyChangeNegativeTest#beneficiariesAllocationSumNot100_returns422'
```

> ⚠️ **第一次跑會 docker pull `postgres:16-alpine` (~80MB)**，可能要等 30 秒。
> 之後本機 image cache 命中，每次測試啟動 ~5 秒。

---

## §1 環境前置

```bash
# 1. 確認 Docker Desktop 正在跑 (Testcontainers 需要 docker daemon)
docker info | grep "Server Version"

# 2. 確認 JDK 21
./mvnw -v
# 預期看到：Java version: 21.x
```

不必本機 docker compose 起 PG — Testcontainers 會自己起一份 Postgres 16 alpine container，跑完自動銷毀（Ryuk reaper 守護）。

---

## §2 跑全部測試 — 一條指令驗 M0~M8 全程

```bash
./mvnw -q test
```

### 2.1 預期輸出（Tests run summary 範例）

```
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

13 支拆解：

| 測試類 | 數量 | 涵蓋 |
|---|---:|---|
| `BancassuranceApplicationTests`         | 1 | Context 啟動、Bean wiring、Flyway DDL |
| `PolicyOptimisticLockConcurrencyTest`   | 1 | M5 樂觀鎖併發 ★ 旗艦 |
| `PolicyChangeIdempotencyTest`           | 3 | M5 Idempotency-Key replay / 不同 body / 沒帶 key |
| `PolicyChangeNegativeTest`              | 8 | 412 / 409 / 422 / 404 / 400 反向案例 |

### 2.2 第一次跑特別慢的原因

1. `docker pull postgres:16-alpine` — 約 80 MB
2. PostgreSQL container 啟動 — 約 3-5 秒
3. Spring ApplicationContext 第一次建構 — 約 5-8 秒
4. Flyway migrate V1~V5 — 約 1 秒

第二次以後：步驟 1 跳過、步驟 2 仍要 3 秒、步驟 3 因 ContextCache 大幅縮短，整體 < 15 秒。

---

## §3 驗證一：樂觀鎖併發測試 ★ 旗艦

### 3.1 單跑這支

```bash
./mvnw test -Dtest=PolicyOptimisticLockConcurrencyTest
```

### 3.2 行為流程

1. `application-test.yml` 預設 `app.demo.optimistic-lock-sleep-ms=0`
2. 但本 class 標 `@TestPropertySource(properties="app.demo.optimistic-lock-sleep-ms=500")` 覆寫成 500ms
3. 測試方法：
   - 起 2 個 thread，都載入 Policy 1 的當前 version (e.g. V0)
   - `CountDownLatch(1)` 當起跑線，主線程 `countDown()` 後兩 thread 同時呼叫 `service.changeAddress()`
   - 兩 thread 都通過 `assertVersionMatches(V0, V0)`，都進 `saveAndFlush` 之前的 sleep
   - 先醒的 thread：`UPDATE policy SET version=V0+1 WHERE version=V0` → 1 row → commit
   - 後醒的 thread：`UPDATE policy SET version=V0+1 WHERE version=V0` → 0 rows → `OptimisticLockingFailureException`
4. 斷言：1 成功 + 1 衝突；DB version 只進 1 次；change_log 只 1 筆

### 3.3 怎麼確認測試「真的」測到併發

把 sleep 設回 0：

```bash
./mvnw test -Dtest=PolicyOptimisticLockConcurrencyTest \
            -Dapp.demo.optimistic-lock-sleep-ms=0
```

預期：測試會「不穩定地通過」(thread 排程順 = 真的串行 = 不衝突 = 失敗)。
這個觀察證明 demo sleep 的角色就是「放大衝突視窗」，不是製造假衝突。

> ⚠️ 上面這個「故意失敗」的反向實驗是教學用，不要 commit 進 CI 設定。

---

## §4 驗證二：Idempotency-Key Replay

### 4.1 單跑

```bash
./mvnw test -Dtest=PolicyChangeIdempotencyTest
```

### 4.2 三個 case 在驗什麼

| 方法名 | 場景 | 斷言 |
|---|---|---|
| `sameKeySameBody_secondCallReplaysWithoutDoubleWrite` | 同 key 同 body 重送 | 200 + DB 沒進版、log 沒增量 |
| `sameKeyDifferentBody_returns422` | 同 key 不同 body | 422 IDEMPOTENCY_KEY_REUSED |
| `noIdempotencyKey_eachCallExecutes` | 不帶 key 兩次 | 兩次都真的執行（對照組） |

### 4.3 想了解第二次呼叫到底走哪段 code

打開 `application-test.yml` 把 `com.sean.bancassurance` log level 改 `DEBUG`，重跑 idempotency test，會看到：

```
... PolicyChangeService - Idempotency replay: key=..., policyId=...
```

這行只在「命中 replay」時印，是業務邏輯被跳過的證據。

---

## §5 驗證三：反向案例 (412 / 409 / 422 / 404 / 400)

### 5.1 單跑

```bash
./mvnw test -Dtest=PolicyChangeNegativeTest
```

### 5.2 八個 case 對應的 HTTP 錯誤碼

| 方法名 | HTTP | code | 場景 |
|---|---:|---|---|
| `bodyExpectedVersionTooLarge_returns412`   | 412 | `PRECONDITION_FAILED` | body 帶過大 version |
| `ifMatchHeaderTakesPrecedence_andTriggers412` | 412 | `PRECONDITION_FAILED` | header 優先於 body |
| `changeAddressOnLapsedPolicy_returns409`   | 409 | `INVALID_POLICY_STATE` | 對 LAPSED 變更 |
| `beneficiariesAllocationSumNot100_returns422` | 422 | `BUSINESS_RULE_VIOLATION` | 受益人加總 99 |
| `beneficiariesNoPriorityOne_returns422`    | 422 | `BUSINESS_RULE_VIOLATION` | 沒人 priority=1 |
| `changePaymentMethodToSinglePay_returns422` | 422 | `BUSINESS_RULE_VIOLATION` | 改成 SINGLE_PAY |
| `changeAddressOnNonexistentPolicy_returns404` | 404 | `RESOURCE_NOT_FOUND` | 找不到的 policyId |
| `negativeExpectedVersion_returns400`       | 400 | `VALIDATION_FAILED` | Bean Validation `@Min(0)` |

### 5.3 為什麼這些反向案例值得寫測試

面試常被挑戰：「你寫的測試怎麼證明 code 真的擋下來？」這些案例證明：
- **412 跟 409 是兩道防線**：前者在 service 第一行擋下、後者在 DB UPDATE 後才擋下
- **400 跟 422 是兩種驗證**：前者是格式 (Bean Validation)、後者是業務語意 (service 自訂規則)
- **404 跟 409 不重疊**：保單不存在 vs. 保單存在但狀態不對

---

## §6 完成檢查單 (M8 ✓ Done)

- [ ] `./mvnw test` 全綠（13 支全過）
- [ ] 第一次跑時 console 出現 `Creating container for image: postgres:16-alpine`
- [ ] `docker ps` 在測試跑完後**沒有**殘留 `pg-bancassurance-*` 容器（Ryuk 已清理）
- [ ] `PolicyOptimisticLockConcurrencyTest` 連續跑 5 次都過（驗證不 flaky）
- [ ] 修改 `PolicyChangeService.assertVersionMatches` 把 `if` 換成永遠 `return` 後，
      `bodyExpectedVersionTooLarge_returns412` 會 RED — 驗證測試「真的」測到 service 邏輯
      （測完記得改回來）

---

## §7 面試話術（口頭可講出來的版本）

### 7.1 「為什麼用 Testcontainers 而非 H2？」(高頻)

> 「H2 不是 PostgreSQL — JSONB、`gen_random_uuid()`、`SELECT FOR UPDATE` 行為都不一樣。
> 我這個專案用了 JSONB 存 change_log 的 before/after snapshot，H2 根本跑不起 V5 migration。
> 用 Testcontainers 起跟 production 一模一樣的 PG 16，CI 不留垃圾，問題能在 commit 前被抓
> 而不是 deploy 後才炸。」

### 7.2 「樂觀鎖併發你怎麼測？」

> 「我用 `@SpringBootTest` 起完整 context、`@TestPropertySource` 把
> `optimistic-lock-sleep-ms` 改成 500ms，在 service 的 `saveAndFlush` 前留時間視窗。
> 測試方法用 `CountDownLatch(1)` 當起跑線，兩個 `Future` 同時衝，先醒的 UPDATE
> `version=N→N+1` 成功，後醒的 `UPDATE WHERE version=N` 找不到列、Hibernate 拋
> `OptimisticLockingFailureException`。我斷言『一勝一敗』、`policy.version` 只進 1 次、
> change_log 只 1 筆 — 失敗那邊連 audit log 也跟著 rollback，證明 `@Transactional` 真的在保護
> audit 與主交易一起進退。」

### 7.3 「為什麼測試不用 `@Transactional` ?」(中級)

> 「這支樂觀鎖測試剛好是反例。如果在測試方法上標 `@Transactional`，整個測試會包在
> 一個 TX 裡，service 的 `@Transactional` 預設 `Propagation.REQUIRED` 會 join 進來，
> 兩個 thread 共用同一個 TX 看不到衝突，最後測試結束 rollback。
> 樂觀鎖『必須』讓 service 的 TX 真的 commit 才驗得到，所以這支測試沒標 @Transactional。
> 其他純查詢的測試我會標，省得手動 cleanup。」

### 7.4 「怎麼避免測試之間互相干擾？」(中級)

> 「兩條路：(1) `@Transactional` 自動 rollback、(2) `@AfterEach` 手動清理。
> 我這個專案因為 service 自帶 commit，路 (1) 不適用，採路 (2) — 每支測試類用『不同保單』
> 當作測試隔離單位，併發測試用 Policy 1、idempotency 用 Policy 2、negative 用 Policy 3/4，
> 互不干擾。`PolicyChangeIdempotencyTest.@AfterEach` 還會清掉本測試寫的 idempotency_record
> 跟 change_log，下次重跑不會被歷史污染。」

### 7.5 「測試金字塔你怎麼分層？」(中級)

> 「金字塔頂層 (E2E) 我這個專案沒做 — 那要起整個 Tomcat + 真 client。中層 (Integration)
> 是這個 M8 — `@SpringBootTest` + Testcontainers，驗整個 application context、AOP proxy、
> `@Transactional`、Flyway 都對。底層 (Unit) 我刻意省略 — service 邏輯都 inline 了，
> 拆出來 mock 反而沒測到值得測的東西。實務上會補 unit test 在『純函數型』邏輯
> （e.g. `validateBeneficiaryRules`）— 可以一句話完成不需要起 context。」

### 7.6 「Spring 的 ContextCache 是什麼？影響測試速度怎麼辦？」(資深)

> 「Spring TestContext 看 `@SpringBootTest` 的『配置指紋』（profiles、TestPropertySource、
> bean overrides）。配置一樣的測試類重用同一個 ApplicationContext，不重新 build。
> 我這個專案有意識地把『不同 properties』獨立成一個 class
> （`PolicyOptimisticLockConcurrencyTest` 標 `@TestPropertySource`），其他測試類共享同一個
> context，cache 命中。實務上一個大專案常因為 `@MockBean` 用太多、context 指紋分得太細，
> 200 個測試類起 50 個 context — Maven test phase 從 5 分鐘飆到 30 分鐘，這是要謹慎優化的點。」
