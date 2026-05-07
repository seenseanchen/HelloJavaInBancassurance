package com.sean.bancassurance.policy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sean.bancassurance.common.exception.BusinessRuleViolationException;
import com.sean.bancassurance.common.exception.IllegalPolicyStateException;
import com.sean.bancassurance.common.exception.PreconditionFailedException;
import com.sean.bancassurance.common.exception.ResourceNotFoundException;
import com.sean.bancassurance.common.idempotency.IdempotencyRecord;
import com.sean.bancassurance.common.idempotency.IdempotencyRecordRepository;
import com.sean.bancassurance.policy.api.dto.BeneficiaryUpsert;
import com.sean.bancassurance.policy.api.dto.ChangeAddressRequest;
import com.sean.bancassurance.policy.api.dto.ChangeBeneficiariesRequest;
import com.sean.bancassurance.policy.api.dto.ChangePaymentMethodRequest;
import com.sean.bancassurance.policy.api.dto.PolicyChangeLogResponse;
import com.sean.bancassurance.policy.api.dto.PolicyResponse;
import com.sean.bancassurance.policy.domain.Beneficiary;
import com.sean.bancassurance.policy.domain.Policy;
import com.sean.bancassurance.policy.domain.PolicyChangeLog;
import com.sean.bancassurance.policy.domain.PolicyChangeType;
import com.sean.bancassurance.policy.domain.PolicyStatus;
import com.sean.bancassurance.policy.domain.PremiumPaymentMethod;
import com.sean.bancassurance.policy.repository.PolicyChangeLogRepository;
import com.sean.bancassurance.policy.repository.PolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 保單變更服務 — M5 核心。
 *
 * 跟 PolicyService (查詢專用) 切開的理由：
 *   1. PolicyService 整 class 標 @Transactional(readOnly=true) — 適合查詢
 *   2. PolicyChangeService 整 class 標 @Transactional — 全部都是寫操作
 *   3. 兩個 service 依賴不同 (本 class 多依賴 changeLogRepo / idempotencyRepo / objectMapper)
 *   4. 單一職責：「查」與「改」職能不同，分開符合 SRP
 *
 *  ── @Transactional 的關鍵知識 (面試重點) ─────────────────────────────
 *
 *  Propagation (傳播行為)：
 *    REQUIRED (預設)        ：有交易就用、沒有就開
 *    REQUIRES_NEW           ：永遠開新交易；外層交易暫停
 *                             用例：寫 audit log 不論主交易成敗都要寫 (本系統不這樣，因為
 *                                  我們希望主交易 rollback 時 log 也 rollback)
 *    NESTED                 ：savepoint，外層 rollback 時內層也 rollback；內層 rollback
 *                             不影響外層 (PG / Hibernate 支援)
 *    SUPPORTS / NOT_SUPPORTED / NEVER / MANDATORY：較少用
 *
 *  Isolation (隔離級別)：
 *    DEFAULT (PG = READ_COMMITTED) — 本系統用這個
 *    REPEATABLE_READ — 同交易內讀同筆永遠相同；極端衝突場景才用
 *    SERIALIZABLE   — 最嚴；效能最差；極端強一致才用
 *
 *  rollbackFor / noRollbackFor：
 *    Spring 預設「unchecked exception (RuntimeException) 才 rollback」，checked 不 rollback。
 *    自訂 checked 例外要 rollback → 加 @Transactional(rollbackFor = MyCheckedException.class)
 *    本系統的業務例外都繼承 RuntimeException，不必特別設定。
 *
 *  ── 自呼叫 (Self-Invocation) 陷阱 ────────────────────────────────────
 *  Spring 的 @Transactional 是靠 AOP proxy 實現。同一個 class 內呼叫的方法
 *  「不會經過 proxy」→ 切面不生效。
 *
 *  錯誤示範：
 *    public void changeAddress(...) {        ← 標 @Transactional
 *        ...
 *        innerHelper(...);                    ← 這裡是 this.innerHelper(...)，不過 proxy
 *    }
 *    @Transactional(propagation = REQUIRES_NEW)
 *    private void innerHelper(...) { ... }   ← 不會開新交易！
 *
 *  解法：
 *    1. 把 inner 抽到另一個 bean (推薦)
 *    2. 注入 self proxy (@Autowired private PolicyChangeService self;) 然後 self.inner()
 *    3. 用 ApplicationContext 拿自身 proxy
 *
 *  本 class 的 helper 都是「在當前交易裡執行」(沒額外 Propagation 需求)，
 *  所以 helper 不標 @Transactional，private 也 OK — 自呼叫不會出問題。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional   // class 預設讀寫；查詢方法另外覆寫 readOnly
public class PolicyChangeService {

    private final PolicyRepository policyRepository;
    private final PolicyChangeLogRepository changeLogRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    /**
     * 教學用 demo sleep（ms）。設為 > 0 可放大樂觀鎖衝突視窗，讓兩支並行 curl 都讀到
     * 同一個 @Version 值後再競爭 UPDATE，重現真正的 409 OPTIMISTIC_LOCK_CONFLICT。
     *
     * ⚠️ 這個欄位「刻意不宣告為 final」，因為它不是 Spring bean 而是 @Value 純量注入。
     *    Lombok 的 @RequiredArgsConstructor 只對 final 欄位產生建構子參數；
     *    @Value 欄位由 Spring 在建構後用 field injection 寫入，不能放進建構子。
     *    Production code 應避免 @Value field injection（難以單元測試），
     *    但 demo / 測試輔助參數這樣寫可以接受。
     */
    @Value("${app.demo.optimistic-lock-sleep-ms:0}")
    private long demoSleepMs;

    /** Idempotency-Key 紀錄存活時間 — 24h (金融業常見) */
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    // ════════════════════════════════════════════════════════════════
    // 對外 API：三個 PATCH + 一個 GET (changes 歷史)
    // ════════════════════════════════════════════════════════════════

    /**
     * 變更通訊地址 — M5 最簡單的 PATCH (單欄位)。
     *
     *  @param policyId    保單 UUID
     *  @param ifMatch     HTTP header If-Match 的值；可為 null (則用 body 的 expectedVersion)
     *  @param idempotencyKey  HTTP header Idempotency-Key 的值；可為 null
     *  @param req         請求 body
     *  @param actor       誰送出變更 (X-Actor header；M9 接 Spring Security 後改 SecurityContext)
     */
    public PolicyResponse changeAddress(
            UUID policyId, String ifMatch, String idempotencyKey,
            ChangeAddressRequest req, String actor) {

        // 1. 冪等性層 — 先攔下重送
        Optional<PolicyResponse> replayed = tryReplay(idempotencyKey, "PATCH /api/policies/{id}/address", req, PolicyResponse.class);
        if (replayed.isPresent()) {
            log.info("Idempotency replay: key={}, policyId={}", idempotencyKey, policyId);
            return replayed.get();
        }

        // 2. 載入 + 業務狀態檢查
        Policy policy = loadInForcePolicyOrThrow(policyId, "change address");

        // 3. 樂觀鎖前置檢查 (412)
        long expectedVersion = resolveExpectedVersion(ifMatch, req.expectedVersion());
        assertVersionMatches(expectedVersion, policy.getVersion());

        // 4. 快照 + 套用
        Map<String, Object> before = Map.of("billingAddress", policy.getBillingAddress());
        policy.setBillingAddress(req.newAddress());
        Map<String, Object> after  = Map.of("billingAddress", policy.getBillingAddress());

        // 5. flush — 樂觀鎖在這裡才實際下 UPDATE；衝突 → OptimisticLockingFailureException → 409
        Policy saved = saveAndFlush(policy);

        // 6. 寫變更日誌 (同一交易；主交易 rollback log 也 rollback)
        writeChangeLog(saved, PolicyChangeType.ADDRESS, before, after, req.reason(), actor);

        PolicyResponse response = PolicyResponse.from(saved);

        // 7. 寫冪等紀錄
        saveIdempotencyRecord(idempotencyKey, "PATCH /api/policies/{id}/address", req, response, actor);

        log.info("Policy address changed: policyId={}, version {} -> {}, actor={}",
                policyId, expectedVersion, saved.getVersion(), actor);
        return response;
    }

    /**
     * 變更受益人 — M5 主菜。集合替換 + 業務規則驗證。
     */
    public PolicyResponse changeBeneficiaries(
            UUID policyId, String ifMatch, String idempotencyKey,
            ChangeBeneficiariesRequest req, String actor) {

        Optional<PolicyResponse> replayed = tryReplay(idempotencyKey, "PATCH /api/policies/{id}/beneficiaries", req, PolicyResponse.class);
        if (replayed.isPresent()) {
            log.info("Idempotency replay: key={}, policyId={}", idempotencyKey, policyId);
            return replayed.get();
        }

        // ★ 必須用 findByIdWithBeneficiaries：JOIN FETCH 把舊受益人撈出來
        //   等等的 .clear() 才有東西可清。不然 LAZY 沒觸發 → orphanRemoval 不會 DELETE 舊的。
        Policy policy = policyRepository.findByIdWithBeneficiaries(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", policyId.toString()));

        if (policy.getStatus() != PolicyStatus.IN_FORCE) {
            throw IllegalPolicyStateException.notInForce(policy.getStatus(), "change beneficiaries");
        }

        long expectedVersion = resolveExpectedVersion(ifMatch, req.expectedVersion());
        assertVersionMatches(expectedVersion, policy.getVersion());

        // 業務規則：比例加總 = 100；至少有一位第一順位受益人
        validateBeneficiaryRules(req.beneficiaries());

        // 快照 before (snapshot 不遮罩，稽核軌跡需要完整資訊)
        Map<String, Object> before = Map.of("beneficiaries", snapshotBeneficiaries(policy.getBeneficiaries()));

        // 套用：清掉舊的 (orphanRemoval 觸發 DELETE)，加新的 (cascade=ALL 觸發 INSERT)
        // ★ 不能用 policy.setBeneficiaries(newList) — 會把整個 collection ref 換掉，
        //   Hibernate 失去對「原 collection」的追蹤，orphanRemoval 不會跑
        policy.getBeneficiaries().clear();
        for (BeneficiaryUpsert upsert : req.beneficiaries()) {
            Beneficiary b = Beneficiary.builder()
                    .id(UUID.randomUUID())
                    .name(upsert.name())
                    .idNumber(upsert.idNumber())
                    .relationship(upsert.relationship())
                    .allocationPercentage(upsert.allocationPercentage())
                    .priority(upsert.priority())
                    .build();
            policy.addBeneficiary(b);   // helper 自動設 b.policy=this
        }

        Policy saved = saveAndFlush(policy);

        Map<String, Object> after = Map.of("beneficiaries", snapshotBeneficiaries(saved.getBeneficiaries()));
        writeChangeLog(saved, PolicyChangeType.BENEFICIARIES, before, after, req.reason(), actor);

        PolicyResponse response = PolicyResponse.from(saved);
        saveIdempotencyRecord(idempotencyKey, "PATCH /api/policies/{id}/beneficiaries", req, response, actor);

        log.info("Policy beneficiaries changed: policyId={}, version {} -> {}, count {} -> {}, actor={}",
                policyId, expectedVersion, saved.getVersion(),
                ((List<?>) before.get("beneficiaries")).size(),
                ((List<?>) after.get("beneficiaries")).size(),
                actor);
        return response;
    }

    /**
     * 變更繳費方式 — enum 變更示範。
     */
    public PolicyResponse changePaymentMethod(
            UUID policyId, String ifMatch, String idempotencyKey,
            ChangePaymentMethodRequest req, String actor) {

        Optional<PolicyResponse> replayed = tryReplay(idempotencyKey, "PATCH /api/policies/{id}/payment-method", req, PolicyResponse.class);
        if (replayed.isPresent()) {
            log.info("Idempotency replay: key={}, policyId={}", idempotencyKey, policyId);
            return replayed.get();
        }

        Policy policy = loadInForcePolicyOrThrow(policyId, "change payment method");

        long expectedVersion = resolveExpectedVersion(ifMatch, req.expectedVersion());
        assertVersionMatches(expectedVersion, policy.getVersion());

        // 業務規則：不能換成 SINGLE_PAY (躉繳是新單時的選擇，已生效保單沒辦法回頭一次繳完)
        if (req.newPaymentMethod() == PremiumPaymentMethod.SINGLE_PAY) {
            throw new BusinessRuleViolationException(
                    "BUSINESS_RULE_VIOLATION",
                    "Cannot change payment method to SINGLE_PAY for an in-force policy");
        }

        Map<String, Object> before = Map.of("premiumPaymentMethod", policy.getPremiumPaymentMethod().name());
        policy.setPremiumPaymentMethod(req.newPaymentMethod());
        Map<String, Object> after  = Map.of("premiumPaymentMethod", policy.getPremiumPaymentMethod().name());

        Policy saved = saveAndFlush(policy);

        writeChangeLog(saved, PolicyChangeType.PAYMENT_METHOD, before, after, req.reason(), actor);

        PolicyResponse response = PolicyResponse.from(saved);
        saveIdempotencyRecord(idempotencyKey, "PATCH /api/policies/{id}/payment-method", req, response, actor);

        log.info("Policy payment method changed: policyId={}, {} -> {}, version {} -> {}, actor={}",
                policyId, before.get("premiumPaymentMethod"), after.get("premiumPaymentMethod"),
                expectedVersion, saved.getVersion(), actor);
        return response;
    }

    /**
     * 列出某保單的完整變更歷史，按 occurred_at desc。
     */
    @Transactional(readOnly = true)
    public List<PolicyChangeLogResponse> listChanges(UUID policyId) {
        if (!policyRepository.existsById(policyId)) {
            throw new ResourceNotFoundException("Policy", policyId.toString());
        }
        return changeLogRepository.findByPolicyIdOrderByOccurredAtDesc(policyId).stream()
                .map(PolicyChangeLogResponse::from)
                .toList();
    }

    // ════════════════════════════════════════════════════════════════
    // 私有 helper — 全部跑在「上層方法的交易」裡
    // ════════════════════════════════════════════════════════════════

    /**
     * 載入 policy 並斷言狀態為 IN_FORCE。不 fetch beneficiaries (除非 caller 需要)。
     */
    private Policy loadInForcePolicyOrThrow(UUID policyId, String operation) {
        Policy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", policyId.toString()));
        if (policy.getStatus() != PolicyStatus.IN_FORCE) {
            throw IllegalPolicyStateException.notInForce(policy.getStatus(), operation);
        }
        return policy;
    }

    /**
     * 解析「期望的版本號」— header If-Match 優先，沒帶才看 body 的 expectedVersion。
     *
     * If-Match 格式：RFC 7232 規定是 quoted-string 例如 If-Match: "0" 或 W/"0"
     * 我們寬鬆處理：去掉引號 / 「W/」前綴後當數字。
     */
    private long resolveExpectedVersion(String ifMatch, Long bodyExpectedVersion) {
        if (ifMatch != null && !ifMatch.isBlank()) {
            String etag = ifMatch.trim();
            if (etag.startsWith("W/")) etag = etag.substring(2);
            etag = etag.replace("\"", "");
            try {
                return Long.parseLong(etag);
            } catch (NumberFormatException e) {
                throw new PreconditionFailedException(
                        "If-Match header is not a valid version: " + ifMatch);
            }
        }
        // body 的 expectedVersion 有 @NotNull 把關，理論上 controller 不會傳 null 進來
        return Objects.requireNonNull(bodyExpectedVersion,
                "expectedVersion must be provided either in If-Match header or in body");
    }

    private void assertVersionMatches(long expected, long actual) {
        if (expected != actual) {
            throw PreconditionFailedException.versionMismatch(expected, actual);
        }
    }

    /**
     * 受益人業務規則：
     *   1. 比例加總必須 = 100.00 (allow scale=2 比較)
     *   2. 至少一位第一順位 (priority=1)
     */
    private void validateBeneficiaryRules(List<BeneficiaryUpsert> beneficiaries) {
        BigDecimal sum = beneficiaries.stream()
                .map(BeneficiaryUpsert::allocationPercentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ★ 用 compareTo，不要用 equals
        //   new BigDecimal("100").equals(new BigDecimal("100.00")) → false (scale 不同)
        //   compareTo 才比數值大小
        if (sum.compareTo(HUNDRED) != 0) {
            throw new BusinessRuleViolationException(
                    "BUSINESS_RULE_VIOLATION",
                    "beneficiaries allocationPercentage must sum to 100.00, got " + sum);
        }

        boolean hasPriorityOne = beneficiaries.stream()
                .anyMatch(b -> b.priority() != null && b.priority() == 1);
        if (!hasPriorityOne) {
            throw new BusinessRuleViolationException(
                    "BUSINESS_RULE_VIOLATION",
                    "at least one beneficiary must have priority = 1 (primary)");
        }
    }

    /**
     * 把受益人 list 序列化成 JSON 友善的 Map list (給 change_log 快照用)。
     */
    private List<Map<String, Object>> snapshotBeneficiaries(List<Beneficiary> beneficiaries) {
        return beneficiaries.stream()
                .map(b -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", b.getName());
                    m.put("idNumber", b.getIdNumber());           // 稽核全留，不遮罩
                    m.put("relationship", b.getRelationship().name());
                    m.put("allocationPercentage", b.getAllocationPercentage());
                    m.put("priority", b.getPriority());
                    return m;
                })
                .toList();
    }

    /**
     * 把 policy save + flush。flush 會：
     *  1. Hibernate 把所有 dirty 變更下到 SQL (UPDATE / INSERT / DELETE)
     *  2. 對 @Version 欄位：UPDATE policy SET ..., version=:newVersion
     *                    WHERE id=:id AND version=:oldVersion
     *  3. 0 rows updated → OptimisticLockException (Spring 翻成 OptimisticLockingFailureException)
     *  4. 成功 → 把 entity.version 增加為新值，後續 PolicyResponse.from(saved) 才讀得到
     *
     * 不顯式 flush 會發生什麼？
     *   - flush 預設在 transaction commit 時自動觸發
     *   - 衝突會在 method 結束、commit 階段才拋
     *   - 衝突拋出時 Spring 已經把 transaction marked rollback；異常照樣傳到 ExceptionHandler
     *   - 但 entity.version 還沒更新 → 我們建 PolicyResponse 時讀到舊值 (錯誤！)
     *   所以「想正確回傳新版本」就要顯式 flush。
     *
     * ── demo sleep 放在這裡的理由 ────────────────────────────────────
     *  sleep 在 saveAndFlush 之前 = 兩個 thread 都已載入 entity (同一 version)、
     *  都通過 assertVersionMatches，但還沒實際下 UPDATE。
     *  此時 DB 裡的 version 還是舊值，READ_COMMITTED 快照仍然可見，
     *  所以 Thread 2 才有機會「也拿到 version=N 的舊快照」。
     *  Thread 1 sleep 結束後先 UPDATE，version=N+1；
     *  Thread 2 sleep 結束後 UPDATE WHERE version=N → 0 rows → 409。
     */
    private Policy saveAndFlush(Policy policy) {
        sleepForDemo();   // ← demo only；production 設定 optimistic-lock-sleep-ms=0 不執行
        return policyRepository.saveAndFlush(policy);
    }

    /**
     * 教學用 sleep，放大樂觀鎖衝突視窗。
     * demoSleepMs = 0（預設）時直接 return，不影響正常流程效能。
     *
     * ── 為什麼不用 @Profile("demo") ─────────────────────────────────
     *  @Profile 需要切換 Spring 環境；用屬性 + 條件 if 更輕量，
     *  而且隨時可以在不重啟的情況下透過 application-local.yml 覆蓋（若搭配 actuator reload）。
     *  金融業常見的「功能旗標 (feature flag)」就是這種思路。
     */
    private void sleepForDemo() {
        if (demoSleepMs <= 0) return;
        log.warn("⚠️  DEMO SLEEP {} ms — 樂觀鎖衝突示範用，PRODUCTION 請確認 optimistic-lock-sleep-ms=0", demoSleepMs);
        try {
            Thread.sleep(demoSleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Demo sleep interrupted");
        }
    }

    /**
     * 寫入變更日誌 — 跟主操作在同一交易，rollback 時 log 也會被 rollback。
     */
    private void writeChangeLog(
            Policy saved,
            PolicyChangeType changeType,
            Map<String, Object> before,
            Map<String, Object> after,
            String reason,
            String actor) {

        PolicyChangeLog logEntry = PolicyChangeLog.builder()
                .id(UUID.randomUUID())
                .policyId(saved.getId())
                .changeType(changeType)
                .beforeSnapshot(before)
                .afterSnapshot(after)
                .reason(reason)
                .actor(actor)
                .afterVersion(saved.getVersion())
                .occurredAt(Instant.now())
                .build();
        changeLogRepository.save(logEntry);
    }

    // ─── 冪等性 helpers ──────────────────────────────────────────────

    /**
     * 嘗試用 idempotency-key replay 之前的回應。
     * 回 Optional.empty() 代表「沒命中、請繼續正常流程」。
     *
     *  ── 三種狀況 ────────────────────────────────────────────────────
     *   1. key 為 null → 不啟用冪等，直接 empty
     *   2. key 在表中 + body hash 相同 → 命中，吐回上次的 response
     *   3. key 在表中 + body hash 不同 → 422 IDEMPOTENCY_KEY_REUSED
     */
    private <T> Optional<T> tryReplay(
            String idempotencyKey,
            String endpoint,
            Object requestBody,
            Class<T> responseType) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }

        Optional<IdempotencyRecord> existing = idempotencyRepository.findById(idempotencyKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        IdempotencyRecord record = existing.get();
        String currentHash = sha256Hex(toJson(requestBody));

        // 同 key 不同 endpoint：拒絕 (例如 client bug 用同 key 打不同 API)
        if (!record.getEndpoint().equals(endpoint)) {
            throw new BusinessRuleViolationException(
                    "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was used on a different endpoint previously: "
                            + record.getEndpoint());
        }
        // 同 key 不同 body：拒絕 (避免 client 「同 key 改內容」)
        if (!record.getRequestHash().equals(currentHash)) {
            throw new BusinessRuleViolationException(
                    "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was used previously with a different request body");
        }

        // 命中：deserialize 上次的 response 直接回
        try {
            return Optional.of(objectMapper.readValue(record.getResponseBody(), responseType));
        } catch (JsonProcessingException e) {
            // 紀錄壞了 → 不應該發生 (我們存進去時就是合法 JSON)；當作沒命中讓 client 重新跑
            log.error("Corrupted idempotency record for key={}: {}", idempotencyKey, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 把成功的回應寫入 idempotency_record。失敗的回應不寫 (4xx/5xx 讓 client 重試)。
     */
    private void saveIdempotencyRecord(
            String idempotencyKey,
            String endpoint,
            Object requestBody,
            Object response,
            String actor) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) return;

        IdempotencyRecord record = IdempotencyRecord.builder()
                .idempotencyKey(idempotencyKey)
                .endpoint(endpoint)
                .requestHash(sha256Hex(toJson(requestBody)))
                .responseStatus(200)
                .responseBody(toJson(response))
                .expiresAt(Instant.now().plus(IDEMPOTENCY_TTL))
                .build();

        // 用 save() 而非 saveAndFlush()：等主交易 commit 時一併寫
        // (高併發下兩個同 key 同時打進來：兩個都會 SELECT 沒命中 → 兩個都 INSERT；
        //  其中一個會 PK 衝突拋例外 → 一邊 commit 一邊 rollback。沒寫進去那邊 client 會看到 5xx，
        //  重試時就會 hit 到第一個的紀錄、走 replay 路徑，最終語意正確)
        idempotencyRepository.save(record);

        log.debug("Idempotency record saved: key={}, endpoint={}, actor={}",
                idempotencyKey, endpoint, actor);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            // 不該發生 — request DTO / response 都是 Jackson 友善型別
            throw new IllegalStateException("Failed to serialize object for idempotency hashing", e);
        }
    }

    private String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 一定有，每個 JDK 都內建
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
