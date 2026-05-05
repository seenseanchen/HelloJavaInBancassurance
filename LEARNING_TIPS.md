# LEARNING_TIPS.md — 從實際案例學 Java 的有效方法

> 給 Sean：這份是「除了動手敲程式以外，怎麼最大化學習效率」的策略筆記。
> 銀行/人壽業面試重「穩定、扎實、能說清楚原理」，比起學新技術，更重視你**能不能在壓力下講清楚一個 Bug 是怎麼被你修掉的**。

---

## 一、學習心法 (Mindset)

### 1. 先「看懂」，再「自己寫」，再「教別人」

費曼學習法的金融業強化版：
- **看懂**：每個 annotation、每個設計模式、每個 SQL，都要能用「白話」對 12 歲小孩解釋。
- **自己寫**：不要 copy-paste。把同一段程式抄三次，第三次不看範本。
- **教別人**：把學會的概念寫成你的 README 或 Notion 筆記，假裝在教學弟。
  → 這在面試時就是你的「STAR 故事素材」。

### 2. 拒絕「框架黑魔法心態」

很多人學 Spring Boot 後變成「會用但講不清楚」。面試官會故意挖：
> 「`@Transactional` 為什麼有時候不生效？」

如果你只是「我都加 `@Transactional` 阿，反正會動」，會被秒淘汰。
**正確姿勢**：每個 annotation 都要問三件事 ——
1. 它**底層**在做什麼? (e.g. AOP Proxy、Bean 後處理)
2. 它的**副作用**是什麼? (e.g. self-invocation 失效)
3. 它的**替代方案**是什麼? (e.g. 程式化交易 `TransactionTemplate`)

### 3. 同一個功能用三種寫法

例如「保單變更的並行控制」：
- 寫法 A：樂觀鎖 `@Version`
- 寫法 B：悲觀鎖 `LockModeType.PESSIMISTIC_WRITE`
- 寫法 C：分散式鎖 (Redisson)

把三種都實作過，並寫一個對比表。**面試時，當你說「我都試過，最後選 A 是因為...」，瞬間從初級變成中高級候選人**。

---

## 二、銀行/人壽業面試的「題目地圖」

把這張地圖印出來貼牆上，每個格子至少要能講 3 分鐘。

### Java 核心 (基本功)

| 題目 | 重點 |
|---|---|
| `==` vs `equals()` | String pool、自動裝箱、為何要 override `hashCode()` |
| ArrayList vs LinkedList | 隨機存取 vs 順序插入；CPU cache friendly |
| HashMap 底層 | 陣列 + 鏈結 + 紅黑樹、負載因子、JDK 8 改動、為何 capacity 是 2 的次方 |
| ConcurrentHashMap | 分段鎖 → CAS + synchronized；和 `Hashtable` 差別 |
| 不可變物件 (Immutable) | 為何 String 是 final、設計優勢、`record` (Java 14+) |
| Generics | 類型擦除 (Type Erasure)、PECS、`<? extends T>` vs `<? super T>` |
| Stream API | 中間/終結操作、惰性求值、什麼時候用 parallelStream 反而更慢 |
| Optional | 為何不該用在欄位、`orElse` vs `orElseGet` 差異 |
| Java 8 ~ 21 新特性 | Lambda、Stream、`var`、Switch Expression、Sealed Class、Record、Virtual Thread |

### 多執行緒與並發 (中高級必考)

| 題目 | 重點 |
|---|---|
| `synchronized` vs `ReentrantLock` | 公平鎖、可中斷、條件變數 |
| `volatile` 三大特性 | 可見性、有序性、不保證原子性 |
| `AtomicInteger` 與 CAS | ABA 問題、`AtomicStampedReference` |
| 執行緒池 | 7 個參數、4 種拒絕策略、為何 Executors 工廠方法不建議用 |
| `CompletableFuture` | 取代 Future，鏈式回呼 |
| 死鎖四要素 | 互斥、持有並等待、不可剝奪、循環等待 |
| ThreadLocal 與記憶體洩漏 | 為何要 remove、與執行緒池一起用的坑 |

### JVM (中高級必考)

| 題目 | 重點 |
|---|---|
| 記憶體區域 | 堆 / 棧 / 方法區 / 程式計數器 / 本地方法棧 |
| GC 演算法 | 標記清除 / 標記整理 / 複製 / 分代 |
| GC 收集器 | G1 / CMS / ZGC / Shenandoah / Serial |
| OOM 排查 | `-Xmx` / 堆轉儲 (heap dump) / MAT / VisualVM |
| 類載入機制 | 雙親委派、SPI、為何 Tomcat 打破雙親委派 |

### Spring / Spring Boot

| 題目 | 重點 |
|---|---|
| IoC / DI | 三種注入方式、循環依賴怎麼解決 |
| Bean 生命週期 | 實例化 → 屬性填充 → BeanPostProcessor → init → destroy |
| `@Transactional` 失效情境 | self-invocation、private 方法、catch 吃掉例外、傳播屬性錯誤 |
| AOP | JDK 動態代理 vs CGLIB、織入時機 |
| Spring MVC 流程 | DispatcherServlet → HandlerMapping → HandlerAdapter → ViewResolver |
| Spring Boot 自動配置 | `@EnableAutoConfiguration` / `spring.factories` / `AutoConfiguration.imports` |

### 資料庫與交易 (銀行業必考)

| 題目 | 重點 |
|---|---|
| ACID | 重點是 Isolation 與 Durability |
| 4 種隔離等級 | RU / RC / RR / Serializable；對應的問題 (髒讀/不可重複讀/幻讀) |
| MVCC | PostgreSQL / MySQL InnoDB 的實作 |
| 索引 | B+ Tree、覆蓋索引、最左前綴、為何不建議過多索引 |
| 樂觀鎖 vs 悲觀鎖 | 適用場景、實作方式 |
| 分散式交易 | 2PC / 3PC / TCC / Saga / Outbox / 本地訊息表 |
| 慢查詢處理 | EXPLAIN、索引、SQL 改寫、讀寫分離、分庫分表 |

### 系統設計 (給有 1-3 年經驗的人)

- 設計一個「保單系統」要包含哪些子服務？
- 高峰時保費繳款怎麼削峰填谷? (MQ)
- 銀保通路與保險公司核心系統怎麼對接? (Webhook、檔案交換、雙方對帳)

---

## 三、刻意練習 (Deliberate Practice) 的具體做法

### 1. 每完成一個功能，都寫「自我面試問答」

例如完成 M5 (保單變更) 後，立刻列 10 題問自己：

```
Q1: 你的 @Transactional 加在哪一層? Service 還是 Repository? 為什麼?
A1: ...

Q2: 如果 Service 方法 A 內部呼叫同 class 的方法 B，B 的 @Transactional 會生效嗎?
A2: ...

Q3: 你怎麼測試樂觀鎖真的生效了?
A3: ...
```

把答案存成 `/docs/qna/M5.md`，**面試前一晚就背這個**，比刷八股文有效十倍。

### 2. 跑壓測還原「面試官的可怕問題」

用 [k6](https://k6.io) 或 JMeter，模擬 100 個人同時改同一張保單。
觀察：
- 有沒有 lost update?
- DB 連線池有沒有爆?
- p99 延遲是多少?

**面試官最愛聽的不是「我會用 Spring」，而是「我做過壓測，QPS 到 500 時 DB 連線數變成瓶頸，後來加了 HikariCP 調參與索引解掉」。**

### 3. 故意製造 Bug 再除錯

主動把程式改壞 (例如把 `@Transactional` 拿掉、把 isolation 改成 READ_UNCOMMITTED)，觀察行為：
- 髒讀真的會發生嗎?
- 兩個交易同時改會變怎樣?

**這種「實驗式學習」是金融業面試最值錢的素材**。

### 4. 把每個 Bug 寫成「事件報告」(Postmortem)

格式：
```
標題: 保單變更出現偶發 lost update
影響: 1/100 次請求資料被覆寫
根因: Service 方法 self-invocation 導致 @Transactional 失效
解法: 把 helper 方法移到另一個 @Service
教訓: 任何 @Transactional 方法不要在同 class 內被普通方法呼叫
```

**這就是面試「你遇過最棘手的 bug 是什麼？」的滿分答案模板**。

---

## 四、銀行業特有的「軟實力」加分

### 1. 對「合規 (Compliance)」有概念
- 個資法 / GDPR / 金管會函令對「資料保留期限」、「敏感欄位脫敏」有要求
- 面試提到「我在 Log 裡用 mask 把身分證字號中間 4 碼遮掉」會加分

### 2. 對「稽核軌跡 (Audit Trail)」有 sense
- 任何寫入都要紀錄誰在何時改了什麼
- 不只記新值，還要記舊值
- 我們的 `PolicyChangeLog` 就是練這個

### 3. 對「優雅降級 (Graceful Degradation)」有見解
- 第三方核保系統掛掉，能不能切到「只接件不審核」模式?
- 講得出 Circuit Breaker (Resilience4j / Hystrix) 的人很吃香

### 4. 對「數據一致性」有偏執
- 金融業最忌諱「對帳對不平」
- 任何「金額」、「狀態」相關欄位的計算，都要有對帳機制
- 提到 Outbox Pattern、TCC、Saga 會被認為是有經驗

---

## 五、推薦資源 (中文 / 英文都列)

### 書 (建議至少看一本經典)
- 《Effective Java》Joshua Bloch — Java 工程師聖經，章節短小好啃
- 《深入理解 Java 虛擬機》周志明 — JVM 必看
- 《Spring 揭秘》王福強 — 中文 Spring 原理書
- 《Designing Data-Intensive Applications》Martin Kleppmann — 後端進階必讀

### 線上資源
- [Baeldung](https://www.baeldung.com/) — Spring/Java 教學最完整的網站
- [Spring 官方 Guides](https://spring.io/guides) — 官方範例
- [JEP (Java Enhancement Proposals)](https://openjdk.org/jeps/) — 看新版本特性的權威來源
- [Vlad Mihalcea](https://vladmihalcea.com/) — JPA/Hibernate 大神的 blog，**樂觀鎖、隔離等級必讀**

### YouTube 頻道
- Marco Codes (Spring Boot 實戰)
- Defog Tech (並發、JVM)
- Dan Vega (現代 Spring)

---

## 六、給 Sean 的具體建議 (個人化)

基於你「有 Java 基礎、沒寫過 Spring」的程度：

1. **不要先去刷 LeetCode**。對銀行業 Java 後端面試，**會用 Spring + 講得清楚 SQL 與交易**比演算法重要。
2. **把這個專案當作面試的作品集**。完成後丟到 GitHub，README 寫上設計思路、踩過的坑、效能數據。
3. **準備 3 個 STAR 故事**：M3 狀態機、M5 保單變更樂觀鎖、M8 整合測試。任何「請分享你做過的專案」都能套。
4. **錄一段 5 分鐘的自介影片**講你的專案，自己回放會發現很多講不清楚的地方。
5. **第一輪面試後寫覆盤筆記**，把答不出來的題目補成 `/docs/qna/interview-rounds.md`。
6. **面試前 24 小時不要寫新 code**，只看自己寫過的程式 + 自我問答。

---

## 七、常見學習地雷 (請避開)

| 地雷 | 正確做法 |
|---|---|
| 一上來就學微服務、Kafka、K8s | 先把單體跑穩、面試可能會挖你不熟的 |
| 看 30 個教學文，每個都看一半 | 跟一個系列看完，比 30 個半成品有用 |
| 抄完範例不修改 | 每個範例至少改 3 個地方再跑 |
| 不寫測試 | 銀行業看到沒測試直接扣分 |
| 用 println debug | 學 IntelliJ Debugger、條件斷點、Evaluate Expression |
| 不看 git log | 學會用 git 是基本職業素養，commit message 也是面試考點 |

---

## 八、什麼時候該停下來複習

每完成 2 個 milestone，停下來做：
1. 寫一篇「我學到什麼」的部落格 / 筆記 (不一定要發)
2. 對著鏡子或朋友把該 milestone 講過一遍
3. 修改 `CLAUDE.md` 第 7 節打勾，並在 `LEARNING_TIPS.md` 補新心得

**學習進度的真正單位不是「寫了幾行 code」，而是「能講清楚幾個概念」。**

---

準備好之後，回對話視窗告訴我「**M0 開始**」，我會帶你跑第一個里程碑。
