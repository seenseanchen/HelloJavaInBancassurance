# 面試問答集 — HelloJavaInBancassurance

> 根據本專案 M0~M8 的實作內容整理。難度標示：🟢 初級 / 🟡 中級 / 🔴 資深
> 建議搭配 STAR 法則練習口頭表達：**情境 → 任務 → 行動 → 結果**

---

## 一、Spring Boot 基礎

### 🟢 Q1：Spring Boot 跟 Spring Framework 差在哪？

**A：**
Spring Framework 是基礎框架，提供 DI（依賴注入）、AOP、MVC 等核心功能，但設定繁瑣（大量 XML 或 Java Config）。Spring Boot 在此之上加了三件事：

1. **Auto-Configuration**：根據 classpath 上有哪些依賴，自動幫你設定好常用 Bean（例如有 `spring-boot-starter-data-jpa` 就自動設 DataSource、EntityManagerFactory）。
2. **Starter 依賴**：一個 `starter-web` 就幫你拉好 Spring MVC + Tomcat + Jackson 等。
3. **內嵌伺服器**：`java -jar app.jar` 直接跑，不用部署到外部 Tomcat。

本專案用 Spring Boot 4.0.x，對比 3.3.x 主要差異是要求 Java 17+、Hibernate 6 預設。

---

### 🟢 Q2：`@SpringBootApplication` 包含了什麼？

**A：**
等同於三個注解合一：

- `@SpringBootConfiguration`：標示這是 Spring 的配置類（繼承 `@Configuration`）。
- `@EnableAutoConfiguration`：開啟 Auto-Configuration 機制。
- `@ComponentScan`：預設掃描**當前類所在的 package 及其子包**。

> 坑：如果把 `BancassuranceApplication.java` 放錯位置（例如放進子包），`@ComponentScan` 就掃不到其他包的 Bean，導致 404 或 NoSuchBeanDefinition。

---

### 🟢 Q3：`@Autowired` field injection 為何不推薦？本專案怎麼處理？

**A：**
Field injection 的問題：

1. 無法做 `final` 宣告，可能被意外賦值。
2. 測試時無法直接 new 物件並注入 mock，必須依賴反射。
3. 遮蔽了循環依賴問題（Spring 4.3 以後 constructor injection 循環依賴會直接報錯）。

本專案**一律用 constructor injection**，搭配 Lombok `@RequiredArgsConstructor` 自動產生：

```java
@Service
@RequiredArgsConstructor
public class PolicyService {
    private final PolicyRepository policyRepository;
    // 自動產生 constructor，Spring 自動注入
}
```

---

## 二、JPA 與 Hibernate

### 🟢 Q4：N+1 問題是什麼？本專案怎麼解？

**A：**
查詢 1 筆 `Policy` 時，如果 `beneficiaries` 是 LAZY，Hibernate 不會一起撈。但當你迴圈存取 `policy.getBeneficiaries()` 時，每一筆 policy 會再發一條 SELECT——查 N 筆 policy 就多 N 條 SQL，共 1+N 條。

本專案的解法：

1. **列表 API** 用 `PolicySummaryResponse`（不帶受益人），避免觸發 N+1。
2. **單筆 API** 用 `@Query` + `JOIN FETCH`：

```java
@Query("SELECT p FROM Policy p LEFT JOIN FETCH p.beneficiaries WHERE p.id = :id")
Optional<Policy> findByIdWithBeneficiaries(@Param("id") UUID id);
```

這樣一條 SQL 就把 policy 跟 beneficiaries 一起撈出來。

---

### 🟡 Q5：`@OneToMany` 的 cascade、orphanRemoval、fetch type 怎麼設定？有什麼坑？

**A：**

```java
@OneToMany(mappedBy = "policy", cascade = CascadeType.ALL,
           orphanRemoval = true, fetch = FetchType.LAZY)
private List<Beneficiary> beneficiaries = new ArrayList<>();
```

- `cascade = ALL`：對 `Policy` 的持久化操作會級聯到 `Beneficiary`（save/delete 都傳播）。
- `orphanRemoval = true`：從集合中移除的 `Beneficiary` 會自動被 DELETE。
- `fetch = LAZY`（預設）：不需要時不撈，避免無謂 JOIN。

**兩個常見坑：**

1. **`clear() + addAll()` vs `setBeneficiaries(newList)`**：`setBeneficiaries(newList)` 把整個 collection reference 換掉，Hibernate 失去對原 `PersistentBag` 的追蹤，`orphanRemoval` 不會發 DELETE。正確做法是修改原 collection 的內容：`beneficiaries.clear(); beneficiaries.addAll(newList);`

2. **LazyInitializationException**：在 `@Transactional` 結束後才存取 LAZY 集合，Hibernate Session 已關閉，會拋這個例外。解法：(a) 在 `@Transactional` 內完成所有存取；(b) 用 JOIN FETCH 提前載入；(c) DTO 轉換在 service 層做完。

---

### 🟡 Q6：`@Transactional` 在同一個 class 內呼叫會生效嗎？為什麼？

**A：**
**不會**。Spring 的 `@Transactional` 靠 **AOP Proxy** 實現——Spring 幫你的 class 生成一個代理物件，外部呼叫時走代理（代理在方法前後開關交易）。

但 `this.helper()` 直接呼叫同 class 的方法，繞過了代理，切面不生效。

本專案的做法：把需要獨立交易的邏輯抽到**另一個 Bean**，讓 Spring 的 proxy 介入。

```java
// ❌ 這樣 helper() 的 @Transactional 不生效
@Service
public class PolicyChangeService {
    @Transactional
    public void updateAddress(...) { this.saveLog(...); }

    @Transactional(propagation = REQUIRES_NEW)
    private void saveLog(...) { ... }  // 不會開新交易
}

// ✅ 拆到另一個 Bean
@Service
public class AuditLogService {
    @Transactional(propagation = REQUIRES_NEW)
    public void saveLog(...) { ... }  // 這樣才真的開新交易
}
```

---

### 🟡 Q7：`Propagation.REQUIRES_NEW` 跟 `REQUIRED` 差在哪？什麼時候用？

**A：**

| | `REQUIRED`（預設） | `REQUIRES_NEW` |
|---|---|---|
| 有現存交易 | 加入現有交易 | **暫停**現有交易，開一個新的 |
| 沒有交易 | 開新交易 | 開新交易 |
| 內層 rollback | 外層也 rollback | 內層 rollback 不影響外層 |

**典型用途**：寫稽核 log。如果用 `REQUIRES_NEW`，主交易失敗 rollback 時，稽核 log 仍然留著（「有人試過但失敗了」的記錄是稽核需求）。

**本專案 M5 故意不用 `REQUIRES_NEW`**：保單變更操作 rollback 時，我希望 `policy_change_log` 也 rollback，避免「log 說改了但 DB 沒改」的稽核錯亂。稽核正確性 > 失敗記錄保留。

---

### 🔴 Q8：樂觀鎖跟悲觀鎖怎麼選？本專案的決策是什麼？

**A：**

|  | 樂觀鎖 (`@Version`) | 悲觀鎖 (`SELECT FOR UPDATE`) |
|---|---|---|
| 機制 | 不鎖，寫入時 `WHERE version=N` 驗證 | 讀時就鎖住 row，其他 TX 等待 |
| 衝突低時 | 效能好 | 浪費鎖資源 |
| 衝突高時 | 大量 409，client 重試成本高 | 排隊等待，延遲增加 |
| 跨多表更新 | 各自 `@Version`，無法跨表原子 | 鎖多個 row，確保原子 |

**本專案選擇樂觀鎖**：銀保系統「兩個業務員同時修改同一張保單」機率極低，衝突時 client 拿到 409 重試 UX 影響小，效能比悲觀鎖好。

我也在 `PolicyRepository` 保留了 `findByIdForUpdate`（PESSIMISTIC_WRITE）作對比——若未來變成高頻搶購型場景（例如保單限額秒殺），再切換。

---

### 🔴 Q9：412 Precondition Failed 和 409 Optimistic Lock Conflict 怎麼分？

**A：**
兩者都是「版本衝突」，但發生層次不同：

- **412**：client 帶 `If-Match: "3"`，server 讀到當前版本已是 4，**在應用層**立刻判定「前置條件不成立」，回 412。RFC 7232 §4.2 明確規定 If-Match 失敗用 412。此時 client 的請求根本沒碰到 DB 寫入。

- **409**：兩個 client 都帶 `If-Match: "3"`、都通過前置檢查，同時進入交易。A 先 commit 把版本改成 4；B 在 `saveAndFlush` 時 Hibernate 發出 `WHERE version=3`，0 rows 受影響，拋 `OptimisticLockingFailureException`，翻譯成 409。

**維運意義**：412 多 → client cache 更新太慢，考慮縮短 cache TTL；409 多 → 真實併發過高，考慮悲觀鎖或分區（sharding）策略。

---

## 三、狀態機

### 🟡 Q10：你怎麼防止業務狀態非法跳轉？

**A：**
本專案的核保流程用 **EnumMap 表驅動**狀態機，把合法的下一個狀態定義在 enum 本身：

```java
public enum UnderwritingStatus {
    SUBMITTED {
        @Override public Set<UnderwritingStatus> nextStates() {
            return EnumSet.of(UNDER_REVIEW, WITHDRAWN);
        }
    },
    APPROVED {
        @Override public boolean isTerminal() { return true; }
        @Override public Set<UnderwritingStatus> nextStates() {
            return EnumSet.of();
        }
    };

    public abstract Set<UnderwritingStatus> nextStates();
    public boolean isTerminal() { return false; }
    public boolean canTransitionTo(UnderwritingStatus target) {
        return nextStates().contains(target);
    }
}
```

Service 呼叫 `canTransitionTo` 驗證，非法跳轉拋 `IllegalStateTransitionException` → `@RestControllerAdvice` 翻成 409。

**為什麼選表驅動而不是 Spring StateMachine？** 狀態數少（6 個）、規則穩定時，表驅動比每個 Action 一個 class 更易讀易改。狀態超過 15 種、需要 Guard/Action 鏈時才引入 Spring StateMachine。

---

### 🟢 Q11：如果有 100 行 if-else 的狀態判斷，怎麼重構？

**A：**
兩種常見手法：

1. **表驅動**（本專案用的）：把狀態轉移規則放進 Map 或 Enum，用查表取代 if-else。
2. **策略模式（Strategy Pattern）**：每個狀態一個 handler class，實作同一介面。用 Map<Status, Handler> 查找對應策略執行。

金融業建議優先表驅動，因為業務規則通常放在設定檔或 DB（產品線異動不需要改程式碼）。

---

## 四、查詢與分頁

### 🟡 Q12：`Specification` 動態查詢是什麼？本專案怎麼用？

**A：**
當查詢條件可選（有的 client 帶 `status` 過濾、有的帶 `holderId`、有的都帶），不能用固定 JPQL。

`Specification<T>` 實作 JPA Criteria API，可以動態組合：

```java
public class PolicySpecifications {
    public static Specification<Policy> hasStatus(PolicyStatus status) {
        return (root, query, cb) ->
            status == null ? cb.conjunction()  // null → 不加條件
                           : cb.equal(root.get("status"), status);
    }
}

// Service 組合
Specification<Policy> spec = Specification
    .where(PolicySpecifications.hasStatus(status))
    .and(PolicySpecifications.hasHolder(holderId));
policyRepository.findAll(spec, pageable);
```

---

### 🟡 Q13：深分頁問題（deep pagination）是什麼？怎麼解？

**A：**
`OFFSET 10000 LIMIT 20` — DB 要掃 10020 行，丟掉前 10000 行只回 20 行。OFFSET 越大，掃描越慢。

解法：

1. **Keyset Pagination（遊標分頁）**：記住上一頁最後一筆的主鍵，用 `WHERE id > lastId LIMIT 20`。永遠 O(1) scan。本專案沒實作，但面試提一下。
2. **業務限制**：金融保單查詢通常有日期範圍或保單號過濾，OFFSET 不會太深。
3. **覆蓋索引**：先用索引撈 id list，再 JOIN 主表。

---

## 五、API 設計

### 🟢 Q14：RESTful API 版本化怎麼做？

**A：**
兩種主流：

1. **URL versioning**：`/api/v1/policies`、`/api/v2/policies`。直觀、易測試、proxy 可 cache；但 URL 顯得冗長，版本廢棄後死路一條。
2. **Header versioning**：`Accept: application/vnd.sean.v2+json`。URL 乾淨、符合 REST 原則；但測試麻煩，瀏覽器難測。

本專案用 URL versioning（`/api/...`），金融業內部系統 URL 版本最常見，清楚易維護。

---

### 🟢 Q15：什麼是冪等性？本專案怎麼實作？

**A：**
冪等性：同一個請求送多次，效果跟送一次一樣。

HTTP Method 層面：GET / PUT / DELETE 天然冪等；POST / PATCH 不是。

本專案在 PATCH（保單變更）加 `Idempotency-Key` header：

1. client 每次請求帶一個唯一 UUID key。
2. server 以這個 key 為 PK 查 `idempotency_record`。
3. 沒命中 → 執行業務邏輯 → 存 record + response snapshot。
4. 命中 → 比對 request hash → 相同就 replay 上次 response（不重新執行業務）。

典型使用場景：網路超時、client 不知道上次有沒有成功，重試時帶同一個 key，安全地拿到上次的結果。

---

## 六、例外處理與可觀測性

### 🟢 Q16：`@RestControllerAdvice` 怎麼用？

**A：**
`@RestControllerAdvice` 是全域例外處理器，結合 `@ExceptionHandler` 攔截 Controller 拋出的例外：

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(PolicyNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleNotFound(PolicyNotFoundException ex) {
        return ApiError.of("POLICY_NOT_FOUND", ex.getMessage());
    }
}
```

本專案統一用自訂業務例外（`PolicyNotFoundException`、`IllegalStateTransitionException` 等），在這一個地方翻成 HTTP status，不讓 Controller 自己 try/catch。

---

### 🟡 Q17：線上 API 出錯怎麼定位？traceId 的作用是什麼？

**A：**
本專案用 MDC（Mapped Diagnostic Context）注入 traceId：

1. `TraceIdFilter`（`OncePerRequestFilter`）在每個請求進來時：優先取 `X-Trace-Id` header（外部傳入）；沒有就自動生成 UUID。
2. 把 traceId 存進 MDC，log pattern 自動帶上 `[traceId=xxx]`。
3. 回應 header 帶上 `X-Trace-Id: xxx`，ApiError body 也有 `traceId` 欄位。
4. `finally` 清除 MDC，避免 thread pool 污染（下一個請求拿到舊 traceId）。

排查線上錯誤時：拿到 client 回報的 traceId，直接在 ELK 或 log 系統搜尋，不需要逐行翻 log。

---

## 七、測試

### 🟡 Q18：為什麼要用 Testcontainers 而不是 H2 記憶體資料庫跑整合測試？

**A：**

| | H2 | Testcontainers + PostgreSQL |
|---|---|---|
| 速度 | 快（純記憶體） | 稍慢（container 啟動） |
| 相容性 | 需要特別的相容模式；部分 PG SQL 語法不支援 | 與 production 完全一致 |
| JSONB | 不支援 | 完整支援 |
| CHECK constraint | 語法差異 | 完整支援 |
| 索引類型 | 有限 | 完整支援 |

本專案用了 **JSONB**（`policy_change_log`）和 PostgreSQL 特有語法，H2 跑不了。更重要的是：上一季有團隊因為「H2 測試全綠，PG migration 上 production 才炸掉」付出了慘痛代價。

---

### 🔴 Q19：樂觀鎖的併發測試怎麼寫？

**A：**
關鍵問題：如何讓兩個 thread 都「讀到同一個舊版本」再去寫？

本專案的做法：

1. 在 service 的 `saveAndFlush` 前加一個 **demo sleep**（可設定 ms 數）。
2. 測試用 `CountDownLatch(2)` 讓兩個 thread 同步起跑。
3. Thread A 和 B 同時執行，都讀到 `version=N`，都進 sleep；sleep 結束後 A 先 flush 成功（version 變 N+1）；B 的 `WHERE version=N` 匹配不到任何 row，拋 `OptimisticLockingFailureException`。

斷言：一個成功（HTTP 200）、一個失敗（409）、`policy.version` 只進了 1 次、`policy_change_log` 只有 1 筆。

**重要**：這支測試**不標 `@Transactional`**——如果在測試層開交易，service 的 TX 就變成 nested，commit 在測試結束才發生，兩個 thread 都看到同一個未 commit 的狀態，測不到真實的 DB 衝突。

---

### 🟡 Q20：測試金字塔是什麼？本專案的選擇？

**A：**

```
         E2E (少)
        集成測試 (中)
     單元測試 (多，快)
```

本專案以「面試作品集」為目標，選擇最有說服力的幾個整合測試：

- **旗艦測試**：樂觀鎖併發（`PolicyOptimisticLockConcurrencyTest`）——用自動化測試「捕捉」到 lost update 防護機制，是面試中最有說服力的 demo。
- **冪等性測試**：三種情境（replay / hash mismatch / no key）。
- **反向案例**：8 個不同 HTTP 狀態碼的 edge case。

省略的部分：`@DataJpaTest` Repository slice、`@WebMvcTest` Controller slice。面試時說明：這兩層在有整合測試覆蓋的情況下，投資報酬率較低；大型專案再補。

---

## 八、系統設計延伸（口頭說明）

### 🔴 Q21：如果第三方 API（例如銀行扣款）在交易中呼叫失敗，怎麼處理？

**A：**
這是**分散式交易**問題，沒有銀彈。常見方案：

1. **Saga 模式**（Choreography 或 Orchestration）：每個服務負責自己的補償操作（Compensating Transaction）。扣款失敗就執行「退款補償」。複雜度高，但沒有分散式鎖。

2. **Outbox Pattern**：把「要發給外部系統的事件」先寫進本地 DB 的 outbox 表（同一個本地交易），再由 Message Relay 非同步發送到 MQ。確保「寫本地 DB」跟「發事件」的原子性，不需要 2PC。

3. **TCC（Try-Confirm-Cancel）**：預留資源（Try）→ 確認（Confirm）或取消（Cancel）。需要服務提供三個 API，侵入性高。

本專案 M10（規劃中）會示範 Outbox Pattern + 最終一致性。

---

### 🔴 Q22：JWT vs Session 差在哪？JWT 登出怎麼處理？

**A：**

| | Session | JWT |
|---|---|---|
| 狀態 | 有狀態（server 端存 Session） | 無狀態（token 自帶 claim） |
| 擴展性 | 水平擴展需要 Session 共享（Redis） | 任何 server 只要有 secret 就能驗 |
| 撤銷 | 刪 Session 立刻生效 | Token 在 exp 前永遠有效（除非加黑名單） |

**JWT 登出問題**：JWT 本身無狀態，server 無法「讓某個 token 失效」。常見解法：

1. **Token 黑名單**（Redis 存 jti）：加 Redis 依賴，變回有狀態，但範圍小。
2. **縮短 exp**（5~15 分鐘）+ Refresh Token：access token 短命，影響面縮小。
3. **版本號（token version）**：user 表加 `token_version`；登出時 +1；validate 時比對 version。

本專案 M9（選配）預計用方案 2（short-lived access token + refresh token）。

---

## 九、金融業常見考題

### 🟢 Q23：為什麼金融業禁用 `double` / `float` 計算金額？

**A：**
浮點數使用 IEEE 754 二進位表示，有精度限制：`0.1 + 0.2 = 0.30000000000000004`。

金融業一定用 `BigDecimal`，且：
- 用 `new BigDecimal("0.1")` 而不是 `new BigDecimal(0.1)`（後者已有浮點誤差）。
- 加法用 `a.add(b)`，除法用 `a.divide(b, 2, RoundingMode.HALF_UP)` 明確指定精度與捨入模式。
- DB 欄位用 `NUMERIC(15, 4)` 或 `DECIMAL`，不用 `FLOAT`。

---

### 🟢 Q24：為什麼金融業每張表都要稽核欄位（created_at, created_by, updated_at, updated_by）？

**A：**
金融法規（臺灣：金管會；國際：SOX、GDPR）要求能回答「誰在什麼時間做了什麼操作」。

本專案用 **JPA Auditing** 自動填入：

```java
@EntityListeners(AuditingEntityListener.class)
@MappedSuperclass
public abstract class AuditableEntity {
    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @CreatedBy
    private String createdBy;

    @LastModifiedBy
    private String updatedBy;
}
```

配合 `AuditorAwareImpl` 從 `SecurityContext` 或 `X-Actor` header 取得當前使用者。

---

### 🟡 Q25：UUID 作為主鍵有什麼優缺點？本專案的選擇？

**A：**

| | BIGINT IDENTITY | UUID v4 | UUID v7 |
|---|---|---|---|
| 洩漏序號風險 | 有（競爭對手可猜業務量） | 無 | 無 |
| 索引效能 | 順序插入，B-tree 友善 | 隨機，page split 多 | 時間排序，接近順序 |
| 可讀性 | 好 | 差 | 差（但含時間） |

本專案選 **UUID v7**（時間排序版）：對外不洩漏保單量（商業機密），又保有索引友善性。PostgreSQL 的 `gen_random_uuid()` 是 v4，未來考慮用 Java 端生成 v7。

---

*以上問答根據 HelloJavaInBancassurance M0~M8 實作整理，建議搭配 `docs/M5_SMOKE_TEST.md` 的 curl demo 實際操作一遍，面試時能說出「我有跑過」。*
