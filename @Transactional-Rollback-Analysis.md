# @Transactional 與 Rollback 機制詳解
## HelloJavaInBancassurance 專案範例

> **文件目的**：深入剖析本專案如何利用 Spring 的 @Transactional 註解實現事務控制與回滾機制，並對應金融業常見的業務場景。

---

## 目錄

1. [核心概念](#核心概念)
2. [M2 核保案件：簡單的 CRUD 事務](#m2-核保案件簡單的-crud-事務)
3. [M3 狀態機：多步驟事務與一致性](#m3-狀態機多步驟事務與一致性)
4. [M5 保單變更：樂觀鎖與複雜事務](#m5-保單變更樂觀鎖與複雜事務)
5. [面試題精選](#面試題精選)
6. [常見坑與解法](#常見坑與解法)

---

## 核心概念

### @Transactional 四大支柱

#### 1️⃣ **Propagation (傳播行為)**

Spring 提供 7 種傳播行為，最常用的三種：

| Propagation | 行為 | 金融場景 |
|---|---|---|
| **REQUIRED** (預設) | 有交易就用、沒有就開 | 大多數 Service 方法 |
| **REQUIRES_NEW** | 永遠開新交易；外層交易暫停 | 寫「不論主交易成敗都要記錄」的 audit log |
| **SUPPORTS** | 有交易就用、沒有也 OK | 只讀查詢，不關心有沒有事務 |

本專案的選擇：
- **PolicyChangeService** / **UnderwritingCaseService**：採 `REQUIRED`（預設）
- **原因**：主交易 rollback 時，變更日誌也要 rollback，確保「更新與審計同生死」

```java
// ✅ 本專案做法：主交易 + audit log 同生死
@Transactional   // ← REQUIRED (default)
public PolicyResponse changeBeneficiaries(...) {
    Policy saved = saveAndFlush(policy);
    // 這行跑在「同一個交易」裡 — 如果 save 失敗，log 也不會寫進去
    writeChangeLog(saved, PolicyChangeType.BENEFICIARIES, before, after, reason, actor);
}

// ❌ 反面教材：audit log 獨立事務（銀行業常見錯誤）
@Transactional(propagation = REQUIRES_NEW)  // ← 不推薦
private void writeChangeLog(...) {
    // 主交易 rollback 時，log 還是被寫進去了 — 金融稽核不符
}
```

#### 2️⃣ **Isolation (隔離級別)**

```java
@Transactional(isolation = Isolation.READ_COMMITTED)  // ← PostgreSQL 預設
```

**本專案採 READ_COMMITTED 的理由**：
- 銀保場景並行度不是極高，不需要 REPEATABLE_READ
- 遇到真正的衝突場景 → 樂觀鎖 (`@Version`) 或悲觀鎖 (`SELECT FOR UPDATE`) 再處理
- 不用 SERIALIZABLE（效能殺手）

**隔離級別與 dirty read / phantom read 的關係**：
```
READ_UNCOMMITTED  ← dirty read 可能  (不用)
READ_COMMITTED    ← dirty read 不可能，phantom read 可能 (本專案用這個)
REPEATABLE_READ   ← phantom read 可能 (高衝突場景)
SERIALIZABLE      ← 完全隔離、最安全、最慢 (只在極端場景用)
```

#### 3️⃣ **rollbackFor / noRollbackFor**

Spring 預設行為：
- ✅ **Unchecked exception (RuntimeException)** → **自動 rollback**
- ❌ **Checked exception** → **不會 rollback**（提醒開發者自己處理）

本專案所有業務例外都繼承 `RuntimeException`，所以無需額外設定 `rollbackFor`：

```java
// 自訂業務例外
public class BusinessRuleViolationException extends RuntimeException { ... }
public class IllegalPolicyStateException extends RuntimeException { ... }
public class PreconditionFailedException extends RuntimeException { ... }

// Service 拋出時：Spring 自動 rollback
@Transactional
public void changePaymentMethod(...) {
    if (req.newPaymentMethod() == SINGLE_PAY) {
        // 拋 RuntimeException → 自動 rollback
        throw new BusinessRuleViolationException(...);
    }
}
```

**如果用 checked exception**（不推薦）：
```java
@Transactional(rollbackFor = MyCheckedException.class)
public void someMethod() throws MyCheckedException {
    throw new MyCheckedException(...);  // 現在才會 rollback
}
```

#### 4️⃣ **readOnly = true (查詢優化)**

```java
@Transactional(readOnly = true)
public PolicyResponse getById(UUID policyId) {
    // Spring 把交易設成「唯讀」，DB driver 可進行優化
    // (例如：PostgreSQL 不用寫 WAL、MySQL 不用鎖行)
}
```

本專案的實踐：
```java
// 查詢專用 service：整 class 標 readOnly
@Transactional(readOnly = true)
public class PolicyService { ... }

// 寫操作專用 service：整 class 標讀寫
@Transactional
public class PolicyChangeService { ... }

// 讀寫混合 service (少見)：方法級別覆寫
@Service
@Transactional
public class UnderwritingCaseService {
    // 預設讀寫
    public UnderwritingCaseResponse claim(...) { ... }
    
    // 覆寫為唯讀
    @Transactional(readOnly = true)
    public List<CaseEventResponse> listEvents(UUID caseId) { ... }
}
```

---

## M2 核保案件：簡單的 CRUD 事務

### 場景：業務員提交核保案件

```java
@Service
@Transactional(readOnly = true)   // ← class 預設唯讀
public class UnderwritingCaseService {

    /**
     * 建立新案件 (SUBMITTED 狀態)
     * 
     * 事務邊界：
     *   ↓ @Transactional 開始
     *   ├─ 產生案件編號 (有微小重複可能)
     *   ├─ INSERT underwriting_case
     *   ├─ INSERT underwriting_case_event (CASE_SUBMITTED)
     *   ↓ commit / rollback
     */
    @Transactional   // ← 覆寫 class 設定，變成讀寫
    public UnderwritingCaseResponse submit(CreateUnderwritingCaseRequest req) {
        
        // 1. 產生案件編號 UW-yyyyMMdd-xxxx
        String caseNumber = generateCaseNumber();  // 循環最多 5 次避免撞號
        Instant now = Instant.now();

        // 2. 建立 entity
        UnderwritingCase entity = UnderwritingCase.builder()
                .id(UUID.randomUUID())
                .caseNumber(caseNumber)
                .applicantName(req.applicantName())
                .applicantIdNumber(req.applicantIdNumber())
                .productCode(req.productCode())
                .coverageAmount(req.coverageAmount())
                .premium(req.premium())
                .channel(req.channel())
                .status(UnderwritingStatus.SUBMITTED)  // ← 新案一律從 SUBMITTED 起跳
                .submittedBy(req.submittedBy())
                .submittedAt(now)
                .build();

        // 3. 保存 — 此時 Hibernate 不會立即下 INSERT，而是標記 entity 為 dirty
        UnderwritingCase saved = repository.save(entity);

        // 4. 寫第一筆事件日誌
        recordEvent(saved, UnderwritingEventType.CASE_SUBMITTED,
                null, UnderwritingStatus.SUBMITTED,
                req.submittedBy(), null, now);
        // ↑ 若此行拋例外 → 整個 transaction rollback
        //   (underwriting_case 的 INSERT + underwriting_case_event 的 INSERT 全被撤銷)

        log.info("Underwriting case submitted: caseNumber={}, id={}", caseNumber, saved.getId());
        return UnderwritingCaseResponse.from(saved);
        // ↑ method 結束 → Spring 自動 commit
    }
}
```

### Rollback 演示：案件編號撞號

```java
// ❌ 萬分之一的倒楣情況
private String generateCaseNumber() {
    String date = "20260508";
    for (int attempt = 0; attempt < 5; attempt++) {
        int suffix = ThreadLocalRandom.current().nextInt(1, 10000);
        String candidate = "UW-%s-%04d".formatted(date, suffix);  // e.g. "UW-20260508-0001"
        
        // Database 層 unique constraint: UNIQUE(case_number)
        if (!repository.existsByCaseNumber(candidate)) {
            return candidate;  // ✅ OK，沒撞號
        }
        // 迴圈再試…
    }
    
    // ❌ 5 次都撞到 → 拋 exception
    throw new IllegalStateException("Failed to generate unique caseNumber after 5 attempts");
}

// 當上面的 submit() 拋例外時，Spring 會：
// 1. 捕捉 IllegalStateException (unchecked → 自動 rollback)
// 2. 回滾「已經執行過」的 save() / recordEvent() / 任何 DB 操作
// 3. 拋 exception 給 controller → GlobalExceptionHandler 把它對映到 500
// 4. Client 收到 500，可以重試（業務員重新提交）
```

### 概念驗證

**關鍵點**：
- ✅ `submit()` 標 `@Transactional` → 整個方法在同一交易
- ✅ `save()` 與 `recordEvent()` 跑在同一交易 → 一起 commit 或 rollback
- ✅ 任何 exception 拋出 → 自動 rollback，DB 狀態不變
- ✅ 沒有 exception → method 結束時自動 commit

---

## M3 狀態機：多步驟事務與一致性

### 場景：核保員審查案件（狀態跳轉）

```java
@Service
@Transactional(readOnly = true)
public class UnderwritingCaseService {

    /**
     * 核保員領件：SUBMITTED → UNDER_REVIEW
     * 
     * 事務需求：
     *   - 讀取舊狀態，驗證「能不能轉」
     *   - 更新狀態 + 核保員簽核人資料
     *   - 寫事件日誌 (审计軌跡)
     *   - ⚠️ 三個操作必須原子性：不能「狀態改了但日誌沒寫」
     */
    @Transactional  // ← 開啟讀寫交易
    public UnderwritingCaseResponse claim(UUID caseId, ClaimRequest req) {
        return doTransition(
                caseId, 
                UnderwritingStatus.UNDER_REVIEW,
                UnderwritingEventType.CASE_CLAIMED, 
                req.actor(), 
                null,
                (c, now) -> {
                    c.setReviewedBy(req.actor());
                    c.setReviewedAt(now);
                }
        );
    }

    /**
     * 核保員要求補件：UNDER_REVIEW → PENDING_INFO
     */
    @Transactional
    public UnderwritingCaseResponse requestInfo(UUID caseId, RequestInfoRequest req) {
        return doTransition(
                caseId, 
                UnderwritingStatus.PENDING_INFO,
                UnderwritingEventType.INFO_REQUESTED, 
                req.actor(), 
                req.comment(),  // ← 補件理由
                (c, now) -> {
                    c.setReviewedBy(req.actor());
                    c.setReviewedAt(now);
                    c.setReviewComment(req.comment());
                }
        );
    }

    /**
     * 核心 transition 邏輯 — helper (不標 @Transactional)
     * 
     * 為什麼 helper 不標 @Transactional？
     *   「自呼叫陷阱」：同 class 內 method 呼叫不經過 Spring proxy。
     *   所以 helper 標不標都沒差 — 它永遠跑在呼叫者的交易裡。
     *   乾脆不標，代表「這是 internal helper，必須由外面的 @Transactional 把關」。
     */
    private UnderwritingCaseResponse doTransition(
            UUID caseId,
            UnderwritingStatus targetStatus,
            UnderwritingEventType action,
            String actor,
            String comment,
            java.util.function.BiConsumer<UnderwritingCase, Instant> caseUpdater) {

        // ⓐ 讀取案件
        UnderwritingCase entity = repository.findById(caseId)
                .orElseThrow(() -> new ResourceNotFoundException("UnderwritingCase", caseId.toString()));
        //                                     ↑
        //                     404 拋出 → transaction 標記 rollback
        //                              (但還沒真的寫任何東西，所以影響不大)

        // ⓑ 驗證狀態跳轉合法性 (表驅動狀態機)
        UnderwritingStatus current = entity.getStatus();
        if (!current.canTransitionTo(targetStatus)) {
            throw new IllegalStateTransitionException(current, targetStatus);
            // ↑ 409 Conflict — client 的 API 呼叫「格式對，但跟 server 狀態衝突」
            //   transaction 自動 rollback (雖然還沒改任何東西)
        }

        // ⓒ 套用狀態機特有的欄位更新邏輯 (claim/request-info/approve 各不同)
        Instant now = Instant.now();
        caseUpdater.accept(entity, now);  // ← lambda 在交易內執行
        entity.setStatus(targetStatus);

        // ⓓ 保存 entity (Hibernate 自動偵測 dirty，準備 UPDATE)
        UnderwritingCase saved = repository.save(entity);

        // ⓔ 寫事件日誌 (審計軌跡)
        recordEvent(saved, action, current, targetStatus, actor, comment, now);
        //          ↑
        //   若 save() 失敗 → rollback 前面的 UPDATE
        //   若 recordEvent() 失敗 → rollback 前面的 UPDATE + recordEvent() 還沒寫完的部分

        log.info("Underwriting case transition: {} -> {}", current, targetStatus);
        return UnderwritingCaseResponse.from(saved);
        // method 結束 → Spring commit (或因為中間有 exception 而 rollback)
    }

    private void recordEvent(
            UnderwritingCase aCase,
            UnderwritingEventType action,
            UnderwritingStatus fromStatus,
            UnderwritingStatus toStatus,
            String actor,
            String comment,
            Instant occurredAt) {

        UnderwritingCaseEvent event = UnderwritingCaseEvent.builder()
                .id(UUID.randomUUID())
                .caseId(aCase.getId())
                .action(action)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .actor(actor)
                .comment(comment)
                .occurredAt(occurredAt)
                .build();
        
        eventRepository.save(event);
        // ↑ 若此處拋 ConstraintViolationException (資料庫 CHECK constraint 失敗) 
        //   → transaction 回滾到最後一個 savepoint (或交易開始)
        //   → underwriting_case 的 UPDATE 也被撤銷
    }
}
```

### 狀態機的 canTransitionTo 驗證

```java
public enum UnderwritingStatus {
    SUBMITTED,      // 新案
    UNDER_REVIEW,   // 審查中
    PENDING_INFO,   // 待補件
    APPROVED,       // 核准
    REJECTED,       // 退件
    WITHDRAWN;      // 撤件

    // ★ 表驅動狀態機 ★
    public boolean canTransitionTo(UnderwritingStatus target) {
        return switch (this) {
            case SUBMITTED -> target == UNDER_REVIEW || target == WITHDRAWN;
            case UNDER_REVIEW -> target == PENDING_INFO || target == APPROVED || target == REJECTED || target == WITHDRAWN;
            case PENDING_INFO -> target == UNDER_REVIEW || target == WITHDRAWN;
            case APPROVED, REJECTED, WITHDRAWN -> false;  // 終態，不能再轉
        };
    }
}

// 違反規則時 → doTransition() 拋 IllegalStateTransitionException
// → GlobalExceptionHandler 翻成 409
// → transaction 自動 rollback
```

### Rollback 演示：非法狀態跳轉

```
HTTP PATCH /api/underwriting/cases/{id}/approve

curl -X PATCH http://localhost:8080/api/underwriting/cases/abc123/approve \
  -H "Content-Type: application/json" \
  -d '{"actor":"user123", "comment":"looks good"}'

[Case 當前狀態是 WITHDRAWN (終態)]

service 執行：
  1. 讀取 case → 狀態為 WITHDRAWN
  2. 驗證 WITHDRAWN.canTransitionTo(APPROVED) → false
  3. 拋 IllegalStateTransitionException("cannot transition from WITHDRAWN to APPROVED")
  4. GlobalExceptionHandler 捕捉 → 409 Conflict
  5. Spring 自動 rollback (雖然還沒改任何東西)

Response:
  {
    "status": 409,
    "statusText": "Conflict",
    "code": "INVALID_STATE_TRANSITION",
    "message": "cannot transition from WITHDRAWN to APPROVED",
    "path": "/api/underwriting/cases/abc123/approve",
    "traceId": "550e8400-e29b-41d4-a716-446655440000"
  }
```

---

## M5 保單變更：樂觀鎖與複雜事務

### 場景：客戶變更受益人（集合替換 + 樂觀鎖 + 冪等性）

```java
@Service
@Transactional
public class PolicyChangeService {

    /**
     * 變更受益人 — M5 的最複雜操作。
     *
     * 事務需求 (金融場景特有)：
     *   1. 樂觀鎖 (@Version)：防止兩個 client 同時改，后來者被擋下
     *   2. 冪等性 (Idempotency-Key)：重複送同個 request 應得同個回應，不會重複扣款/入帳
     *   3. 變更日誌 (JSONB before/after snapshot)：配合交易，一起成敗
     *   4. 業務規則驗證：受益人比例加總必須 = 100
     *   5. 級聯刪除：舊受益人要被 orphanRemoval，新受益人要被 INSERT
     *
     * 事務流程圖：
     *   [Client A]                [Client B]
     *        ↓ GET (讀 v=5)              ↓ GET (讀 v=5)
     *        ├─ assertVersion(5)    ├─ assertVersion(5)  (都通過)
     *        ├─ 改 beneficiaries     ├─ 改 beneficiaries
     *        ├─ flush/UPDATE         │   (A 先搶到)
     *        │  WHERE v=5 v6 ✅      │
     *        │                      ├─ flush/UPDATE
     *        │                      │  WHERE v=5 → 0 rows ❌
     *        │                      └─ OptimisticLockingFailureException → 409
     */
    public PolicyResponse changeBeneficiaries(
            UUID policyId, String ifMatch, String idempotencyKey,
            ChangeBeneficiariesRequest req, String actor) {

        // ⓐ 冪等性第一層：check cache
        Optional<PolicyResponse> replayed = tryReplay(
                idempotencyKey, "PATCH /api/policies/{id}/beneficiaries", 
                req, PolicyResponse.class);
        if (replayed.isPresent()) {
            log.info("🔄 Idempotency replay: 重複 request，直接吐上次的回應");
            return replayed.get();
        }

        // ⓑ 讀取保單 + 受益人 (JOIN FETCH 避免 N+1)
        Policy policy = policyRepository.findByIdWithBeneficiaries(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", policyId.toString()));
        // ↑ 如果找不到 → 404，transaction 標記 rollback (但還沒改東西)

        // ⓒ 檢查保單狀態
        if (policy.getStatus() != PolicyStatus.IN_FORCE) {
            throw IllegalPolicyStateException.notInForce(policy.getStatus(), "change beneficiaries");
            // ↑ 409 — 保單沒生效，不能改受益人
        }

        // ⓓ 樂觀鎖第一道防線：檢查 If-Match header / body expectedVersion
        long expectedVersion = resolveExpectedVersion(ifMatch, req.expectedVersion());
        assertVersionMatches(expectedVersion, policy.getVersion());
        //                                    ↑
        // 若不符 → PreconditionFailedException (412)
        // 此時還沒改 DB，transaction 自動 rollback (影響不大)

        // ⓔ 業務規則驗證：受益人比例加總 = 100 & 至少一位優先順序=1
        validateBeneficiaryRules(req.beneficiaries());
        // ↑ 若違反 → BusinessRuleViolationException (422 Unprocessable Entity)
        //   還沒改 DB，rollback

        // ⓕ 建快照 (before)
        Map<String, Object> before = Map.of("beneficiaries", 
                snapshotBeneficiaries(policy.getBeneficiaries()));

        // ⓖ 套用變更 — 核心步驟 (必須用 .clear() + .addAll 或迴圈 add，不能用 setter)
        //   原因：Hibernate 需要看到「同一個 collection object 被改變」才能觸發 orphanRemoval
        policy.getBeneficiaries().clear();  // ← 觸發 DELETE 舊受益人的準備
        for (BeneficiaryUpsert upsert : req.beneficiaries()) {
            Beneficiary b = Beneficiary.builder()
                    .id(UUID.randomUUID())
                    .name(upsert.name())
                    .idNumber(upsert.idNumber())
                    .relationship(upsert.relationship())
                    .allocationPercentage(upsert.allocationPercentage())
                    .priority(upsert.priority())
                    .build();
            policy.addBeneficiary(b);  // ← 觸發 INSERT 新受益人的準備
        }

        // ⓗ 樂觀鎖第二道防線 (最後的 DB 驗證)
        // ★ 必須 flush()，不能等到 commit 時才下 UPDATE
        //   原因：我們需要「在 exception 拋出時，entity.version 已經更新成新值」，
        //        這樣回傳的 response 才會帶正確的 version/ETag
        Policy saved = saveAndFlush(policy);
        // ↑ 若此時有另一個交易已改過該 policy，執行：
        //   UPDATE policy SET ..., version=N+1 WHERE id=:id AND version=N
        //   結果 0 rows affected → OptimisticLockingFailureException
        // → GlobalExceptionHandler 翻成 409 Conflict
        // → transaction 自動 rollback
        //   (DELETE / INSERT 都被撤銷，DB 回到交易開始的狀態)

        // ⓘ 建快照 (after)
        Map<String, Object> after = Map.of("beneficiaries",
                snapshotBeneficiaries(saved.getBeneficiaries()));

        // ⓙ 寫變更日誌 (JSONB)
        // ★ 同一交易 — 若此行失敗，前面的 UPDATE + DELETE + INSERT 全被 rollback
        writeChangeLog(saved, PolicyChangeType.BENEFICIARIES, before, after, req.reason(), actor);

        PolicyResponse response = PolicyResponse.from(saved);

        // ⓚ 寫冪等紀錄 (Idempotency-Key)
        // ★ 用 save() 而非 saveAndFlush() — 讓它跟主交易一起 commit
        // (若 PK 衝突，讓 main thread 成功，concurrent thread 得到 5xx，
        //  重試時就會 hit 到 idempotency cache)
        saveIdempotencyRecord(idempotencyKey, "PATCH /api/policies/{id}/beneficiaries", 
                req, response, actor);

        log.info("✅ Policy beneficiaries changed: id={}, v{} -> v{}, actor={}",
                policyId, expectedVersion, saved.getVersion(), actor);
        return response;
        // method 結束 → Spring commit (若無 exception)
    }

    /**
     * 受益人業務規則驗證 — 拋 exception 時交易會被標記 rollback
     */
    private void validateBeneficiaryRules(List<BeneficiaryUpsert> beneficiaries) {
        
        // 規則 1: 比例加總 = 100.00
        BigDecimal sum = beneficiaries.stream()
                .map(BeneficiaryUpsert::allocationPercentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(new BigDecimal("100.00")) != 0) {
            throw new BusinessRuleViolationException(
                    "BUSINESS_RULE_VIOLATION",
                    "beneficiaries allocationPercentage must sum to 100.00, got " + sum);
            // ↑ 422 — transaction 自動 rollback
        }

        // 規則 2: 至少一位第一順位受益人
        boolean hasPriorityOne = beneficiaries.stream()
                .anyMatch(b -> b.priority() != null && b.priority() == 1);
        if (!hasPriorityOne) {
            throw new BusinessRuleViolationException(
                    "BUSINESS_RULE_VIOLATION",
                    "at least one beneficiary must have priority = 1");
            // ↑ 422 — transaction 自動 rollback
        }
    }

    /**
     * 樂觀鎖演示：saveAndFlush 中的關鍵動作
     */
    private Policy saveAndFlush(Policy policy) {
        // (Demo 用：放大樂觀鎖衝突視窗)
        sleepForDemo();

        // saveAndFlush() 會：
        // 1. Hibernate flush all dirty entities
        // 2. 執行 SQL: UPDATE policy SET ..., version = :newVersion 
        //            WHERE id = :id AND version = :oldVersion
        // 3. 檢查受影響行數：0 rows → OptimisticLockingFailureException
        return policyRepository.saveAndFlush(policy);
    }
}
```

### Rollback 演示：樂觀鎖衝突

```
同時有兩個 HTTP PATCH 打進來，改同一張保單的受益人：

時間軸：
t=0ms   [Client A]                      [Client B]
        GET /policies/{id}              GET /policies/{id}
        ↓ 讀回 version=5               ↓ 讀回 version=5

t=100ms ├─ assert version=5 ✅          ├─ assert version=5 ✅
        ├─ 改 beneficiaries            ├─ 改 beneficiaries

t=200ms ├─ saveAndFlush()
        │  UPDATE policy 
        │  SET beneficiaries=..., version=6
        │  WHERE id=XXX AND version=5  (✅ 找到 1 row → 更新成功)
        │  version 現在 = 6
        │
        ├─ writeChangeLog()  ✅          │ [Client B 仍在改 beneficiaries]
        │                                │
        ├─ saveIdempotencyRecord() ✅   │
        │                                │
        └─ commit ✅                    ├─ saveAndFlush()
                                        │  UPDATE policy
                                        │  SET beneficiaries=..., version=6
                                        │  WHERE id=XXX AND version=5  (❌ 找到 0 rows！)
                                        │  → OptimisticLockingFailureException
                                        │
                                        ├─ Spring 自動 rollback:
                                        │  • DELETE 新受益人的 INSERT
                                        │  • 舊受益人的 DELETE (orphanRemoval)
                                        │  • changeLog 的 INSERT
                                        │  • idempotency_record 的 INSERT (也被 rollback)
                                        │
                                        └─ GlobalExceptionHandler 翻成 409 Conflict

[Client A Response] ✅
HTTP 200 OK
{
  "code": "0000",
  "message": "Success",
  "data": {
    "id": "...",
    "version": 6,  ← 新版本
    "beneficiaries": [...]
  }
}

[Client B Response] ❌
HTTP 409 Conflict
{
  "status": 409,
  "statusText": "Conflict",
  "code": "OPTIMISTIC_LOCK_CONFLICT",
  "message": "The resource was modified by another transaction. Please reload and retry.",
  "traceId": "..."
}

Client B 需要：
  1. 重新 GET /policies/{id} 讀最新的 version=6
  2. 重新計算、送出新的 PATCH (with If-Match: "6" or body.expectedVersion=6)
  3. 第二次嘗試才會成功
```

### 冪等性與事務

```java
/**
 * 三種情況下的冪等性處理
 */

// 情況 1: 首次送請，成功 ✅
POST /api/policies/{id}/beneficiaries
Idempotency-Key: idempotency-abc123
Body: { beneficiaries: [...] }

↓ Service 執行：
- tryReplay(key) → 沒在 idempotency_record 表裡，回 empty
- 執行完整邏輯，save + writeChangeLog + saveIdempotencyRecord
- ✅ commit (idempotency_record 被寫進去)

Response: 200 OK { ... }

---

// 情況 2: 重複送同一個請 (same key, same body) ✅
POST /api/policies/{id}/beneficiaries
Idempotency-Key: idempotency-abc123  ← 同 key
Body: { beneficiaries: [...] }  ← 同 body

↓ Service 執行：
- tryReplay(key) → 在 idempotency_record 找到，body hash 相同
- 直接反序列化上次的 response，回傳 (不重新執行邏輯)
- 0 次 DB 寫入 ✅

Response: 200 OK { ... }  (完全相同的 JSON)

---

// 情況 3: 重複送，但改了 body (same key, different body) ❌ 422
POST /api/policies/{id}/beneficiaries
Idempotency-Key: idempotency-abc123  ← 同 key
Body: { beneficiaries: [改成不同] }  ← 不同 body

↓ Service 執行：
- tryReplay(key) → 在 idempotency_record 找到，但 body hash 不同
- 拋 BusinessRuleViolationException("IDEMPOTENCY_KEY_REUSED", ...)
- ✅ transaction 自動 rollback (此時還沒改任何東西)

Response: 422 Unprocessable Entity
{
  "code": "IDEMPOTENCY_KEY_REUSED",
  "message": "Idempotency-Key was used previously with a different request body"
}
```

---

## 面試題精選

### 初級

**Q: @Transactional 是怎麼實現的？**

A: 透過 Spring AOP proxy。當你標 `@Transactional` 時，Spring 在運行期間為該 bean 建立 proxy，
在方法呼叫前開啟交易、方法結束後 commit（或因 exception 而 rollback）。

```java
// 你寫的：
@Transactional
public void saveUser(User user) { ... }

// Spring 產生的 proxy (偽代碼)：
public void saveUser(User user) {
    Transaction tx = transactionManager.begin();
    try {
        // ↓ 呼叫原本的 method
        originalObject.saveUser(user);
        tx.commit();
    } catch (RuntimeException e) {
        tx.rollback();
        throw e;
    }
}
```

**Q: RuntimeException vs Checked exception，@Transactional 怎麼處理？**

A: 
- `RuntimeException`（unchecked）→ **自動 rollback**
- `Checked Exception` → **不會 rollback**，要加 `rollbackFor = MyException.class`

本專案全用 `RuntimeException`，所以不需要 `rollbackFor`。

---

### 中級

**Q: 為什麼改保單時要 `saveAndFlush()`，不能等 commit？**

A: 因為樂觀鎖的 `OptimisticLockingFailureException` 在 flush 時才拋出。
如果只用 `save()`，exception 會在 commit 時才拋，此時 entity.version 還沒更新 → 回傳的 response 版本號錯誤。

```java
// ❌ 錯誤：flush 被延後到 commit
@Transactional
public void change() {
    policy.setBillingAddress("...");
    policyRepository.save(policy);  // ← 只標記 dirty，不實際 UPDATE
    // 若另一個交易改了 policy → 此時不會拋 exception
    
    // 用 policy.getVersion() 會是舊值 → response 帶舊版本 ❌
    return PolicyResponse.from(policy);  // version 仍然是舊值
}

// ✅ 正確：flush 立即執行
@Transactional
public void change() {
    policy.setBillingAddress("...");
    Policy saved = policyRepository.saveAndFlush(policy);  // ← 立即 UPDATE
    // 若另一個交易改了 policy → OptimisticLockingFailureException 立即拋出
    
    // policy.getVersion() 已經是新值
    return PolicyResponse.from(saved);  // version 是正確的新值 ✅
}
```

**Q: 為什麼 changeLog 要跟主交易同生死？REQUIRES_NEW 不好嗎？**

A: 在金融業，audit log 應該「最多跟主交易一樣準確」。如果用 `REQUIRES_NEW`：

```java
// ❌ 反面教材
@Transactional
public void changeBeneficiaries() {
    savePolicy();  // 成功
    writeChangeLog();  // 用 REQUIRES_NEW 開新交易，即使 savePolicy 失敗也會寫進去
}

// 結果：稽查員查 policy 改了，查 log 卻沒有 → 資訊不一致 ❌

// ✅ 本專案做法
@Transactional
public void changeBeneficiaries() {
    savePolicy();  // 成功
    writeChangeLog();  // 跟主交易同生死
}

// 若 savePolicy 成功但 writeChangeLog 失敗 → 整個 rollback
// 結果：policy 沒改，log 也沒寫 → 資訊一致 ✅
```

---

### 資深

**Q: 自呼叫 (Self-Invocation) 陷阱是什麼？**

A: Spring 的 `@Transactional` 靠 AOP proxy，但同 class 內的方法呼叫「繞過 proxy」。

```java
@Service
@Transactional
public class UserService {

    public void importUsers(List<User> users) {
        for (User user : users) {
            // ❌ this.saveUser() 不會經過 proxy — @Transactional 不生效
            saveUser(user);  // ← 相當於 this.saveUser(...)
        }
    }

    @Transactional(propagation = REQUIRES_NEW)
    public void saveUser(User user) {
        // 想要「每個 user 單獨交易」，但因為 this.saveUser 沒經過 proxy，
        // REQUIRES_NEW 完全失效 → 都在 importUsers 的交易裡
    }
}

// 解法 1：注入自身 proxy
@Service
public class UserService {
    @Autowired private UserService self;  // ← 注入自身 proxy

    public void importUsers(List<User> users) {
        for (User user : users) {
            self.saveUser(user);  // ← 經過 proxy ✅
        }
    }
}

// 解法 2：抽到另一個 bean (推薦)
@Service
public class UserService {
    @Autowired private UserPersister persister;

    public void importUsers(List<User> users) {
        for (User user : users) {
            persister.saveUser(user);  // ← 是另一個 bean，一定經過 proxy ✅
        }
    }
}

@Service
public class UserPersister {
    @Transactional(propagation = REQUIRES_NEW)
    public void saveUser(User user) { ... }
}
```

本專案的 helper 都不標 `@Transactional`，因為它們只需要「跑在呼叫者的交易」裡，避免自呼叫陷阱。

**Q: Propagation.NESTED vs REQUIRES_NEW 差別？**

A:

```
REQUIRES_NEW: 暫停外層交易，開新交易。
             新交易成功 commit，外層後來 rollback → 新交易不會被撤
             → 用於「無論外層成敗都要寫」的 audit log (不推薦)

NESTED:      建立 savepoint (PostgreSQL/MySQL 5.7+)。
             內層 rollback → 回到 savepoint，外層繼續
             外層 rollback → 內層也被撤
             → 用於「內層失敗允許外層重試」的場景 (較少用)

  例子 (NESTED)：
  @Transactional
  public void processOrder(Order order) {
      saveOrder(order);  // ① 主交易
      try {
          sendEmail(order);  // ② 嵌套交易 (savepoint)
      } catch (Exception e) {
          // 寄信失敗，rollback ② 但保留 ①
          // 主交易繼續 (order 還是被 save 了，只是沒寄信)
      }
  }
```

本專案沒用 NESTED，因為「萬一寫 log 失敗，整個 rollback」是期望的金融行為。

---

## 常見坑與解法

### 坑 1: 忘記標 @Transactional 在寫操作上

```java
// ❌ 危險：沒標 @Transactional
public void saveUser(User user) {
    userRepository.save(user);
    // 沒有事務邊界 → 若後續操作拋 exception，前面的 save 已經 commit
    sendNotificationEmail(user);  // ← 若拋 exception
    // user 被 save 了，但 notification 沒發出去 → 資料不一致
}

// ✅ 正確
@Transactional
public void saveUser(User user) {
    userRepository.save(user);
    sendNotificationEmail(user);  // ← 若拋 exception，save 也被 rollback
}
```

**本專案如何避免**：Service 層「整 class」標 `@Transactional`。

### 坑 2: readOnly=true 時還在寫入

```java
// ❌ 邏輯矛盾
@Transactional(readOnly = true)
public void updateUser(User user) {
    userRepository.save(user);  // ← readOnly=true，但在寫
    // 行為不定義：可能被 Hibernate 攔下、可能寫進去但最後 rollback
    // 不同 DB 與 Hibernate 版本表現可能不同
}

// ✅ 正確：分開 service
@Transactional(readOnly = true)
public User findById(UUID id) { ... }

@Transactional
public void update(User user) { ... }
```

**本專案如何避免**：
- `PolicyService` 整 class `readOnly = true` (只查詢)
- `PolicyChangeService` 整 class 讀寫 (只變更)

### 坑 3: 異常被 catch 吃掉，transaction 不知道

```java
// ❌ 危險
@Transactional
public void transfer(Account from, Account to, BigDecimal amount) {
    from.debit(amount);
    repository.save(from);
    
    try {
        to.credit(amount);
        repository.save(to);
    } catch (Exception e) {
        // ❌ 問題：exception 被 catch，Spring 不知道失敗了
        // transaction 仍會 commit → from 被扣了，to 沒被加 ❌
        log.error("Failed to credit to account", e);
    }
}

// ✅ 正確：讓 exception 傳播，或手動 rollback
@Transactional
public void transfer(Account from, Account to, BigDecimal amount) {
    from.debit(amount);
    repository.save(from);
    
    to.credit(amount);
    repository.save(to);
    // ← exception 自然傳播，Spring 自動 rollback
}

// 或者
@Transactional
public void transfer(Account from, Account to, BigDecimal amount) {
    try {
        doTransfer(from, to, amount);
    } catch (Exception e) {
        // 需要手動標記 rollback
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        throw e;
    }
}
```

**本專案如何避免**：
- Service 層不 catch 業務例外，讓它傳播
- GlobalExceptionHandler 統一處理 → 確保 exception 拋出時 transaction 已標記 rollback

### 坑 4: flush() 拋的 exception 沒被預期到

```java
// ❌ 誤解：以為 flush() 不拋 exception
@Transactional
public void changeBeneficiaries() {
    policy.setBeneficiaries(newList);
    policyRepository.save(policy);  // 沒 flush，以為不會拋 exception
    
    // 程式繼續…
    writeChangeLog(...);
    
    // 直到 method 結束，flush 才在 commit 時執行
    // OptimisticLockingFailureException 拋出
    // → 但 changeLog 已經被 insert 了 ❌
}

// ✅ 正確：用 saveAndFlush()，讓 exception 立即拋出
@Transactional
public void changeBeneficiaries() {
    policy.setBeneficiaries(newList);
    Policy saved = policyRepository.saveAndFlush(policy);  // ← 立即拋出
    
    // 若此時拋 OptimisticLockingFailureException，往下不執行
    writeChangeLog(...);
}
```

**本專案如何避免**：在樂觀鎖相關的地方都用 `saveAndFlush()`，確保衝突立即拋出。

---

## 總結

| 概念 | 本專案做法 | 為什麼 |
|---|---|---|
| **Class 層級 @Transactional** | 讀寫 service 用 `@Transactional`，唯讀用 `@Transactional(readOnly=true)` | 減少重複標註，簡化意圖 |
| **Propagation** | 全用 `REQUIRED` (預設) | 變更日誌與主交易同生死 |
| **Isolation** | `READ_COMMITTED` (PG 預設) | 銀保場景衝突低，樂觀鎖把關 |
| **rollbackFor** | 無特設，全用 `RuntimeException` | 簡潔；業務例外本身就是 unchecked |
| **saveAndFlush()** | 樂觀鎖場景必用 | 讓衝突立即拋出，entity.version 同步更新 |
| **Audit log 位置** | 同一交易，不用 `REQUIRES_NEW` | 金融稽查要求「改與 log 同進同出」 |
| **Helper 方法** | 不標 `@Transactional` | 避免自呼叫陷阱，它們自動跑在呼叫者交易裡 |
| **異常捕捉** | Service 不 catch，讓傳播 | GlobalExceptionHandler 統一處理，transaction 自動 rollback |

---

## 進階閱讀

- Spring 官方文件：[Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction.html)
- PostgreSQL 隔離級別：[PostgreSQL Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html)
- Hibernate 樂觀鎖：[Optimistic Locking](https://docs.jboss.org/hibernate/orm/6.2/userguide/html_single/Hibernate_User_Guide.html#locking-optimistic)
