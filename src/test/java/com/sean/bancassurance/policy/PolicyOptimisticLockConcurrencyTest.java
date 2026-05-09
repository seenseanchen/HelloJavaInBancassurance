package com.sean.bancassurance.policy;

import com.sean.bancassurance.IntegrationTestBase;
import com.sean.bancassurance.policy.api.dto.ChangeAddressRequest;
import com.sean.bancassurance.policy.domain.Policy;
import com.sean.bancassurance.policy.domain.PolicyChangeLog;
import com.sean.bancassurance.policy.domain.PolicyStatus;
import com.sean.bancassurance.policy.repository.PolicyChangeLogRepository;
import com.sean.bancassurance.policy.repository.PolicyRepository;
import com.sean.bancassurance.policy.service.PolicyChangeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8 旗艦測試 — 樂觀鎖併發。
 *
 * ════════════════════════════════════════════════════════════════════════
 *  這支測試是整個 M5 + M8 的「面試核心展示品」。
 *  口頭答案訓練：
 *    「我寫了一支整合測試，在 Testcontainers 起 PG 16，模擬兩個 thread 同時對
 *     同一張 IN_FORCE 保單做 PATCH 變更地址。透過 demo sleep 把 saveAndFlush
 *     之前的視窗放大到 500ms，配合 CountDownLatch 讓兩個 thread 同時起跑，
 *     兩個都會載到 version=0、都通過樂觀鎖前置檢查、都 sleep。先醒的成功
 *     UPDATE → version=1；後醒的 UPDATE WHERE version=0 → 0 rows →
 *     OptimisticLockingFailureException，最終 DB 只進版一次，change log 也只
 *     寫一筆。這就是 @Version 防 lost update 的證據。」
 * ════════════════════════════════════════════════════════════════════════
 *
 *  ── 為什麼用 @TestPropertySource 而不在 application-test.yml 設？─────
 *   只有「這支測試」需要 sleep；其他測試零延遲跑完才快。
 *   @TestPropertySource 是 class-level 屬性覆寫，priority 高於 yml。
 *   ★ 副作用：覆寫 properties 會讓 Spring 建一個「新的 ApplicationContext」
 *     (跟其他不覆寫的測試 class 不共用 cache)。這是一次性 cost，可接受。
 *
 *  ── 為什麼不用 @Transactional 標在測試方法上？──────────────────────
 *   @Transactional 標在測試方法 = 整個測試包在一個 TX 裡，結束時 rollback。
 *   但我們的 service 自己有 @Transactional — 它會 join 測試的 TX 而不是開新的。
 *   結果：兩個 thread 共用同一個測試 TX = 不會發生樂觀鎖衝突 (在同一 TX 裡看到的
 *   是自己的 dirty write)。
 *   → 樂觀鎖測試「必須」讓 service 自己的 TX 真實 commit / rollback，
 *     所以 testmethod「不要」標 @Transactional。
 *
 *  ── 怎麼回到「乾淨初始狀態」讓測試可重跑？─────────────────────────
 *   @AfterEach 把 policy.version 重設、把 change log 砍掉。雖然「測試之間互不
 *   干擾」原則上應該用 @Sql 或重建 DB，但本系統測試量小，手動 reset 最直觀。
 *
 *  ── 為什麼用 CountDownLatch ─────────────────────────────────────────
 *   兩個 Future submit 後，executor 內部排程不保證「真的同時起跑」。
 *   CountDownLatch(1) 當成「起跑線」：兩個 thread 都先 await()，主線程
 *   countDown() 後才一起衝。配合 demo sleep 500ms 雙重保險，避免 timing race
 *   讓測試 flaky。
 */
@DisplayName("M5 保單變更 — 樂觀鎖併發測試")
@TestPropertySource(properties = {
        // 把 saveAndFlush 之前的視窗放大到 500ms — 留時間讓 thread B 也載入同一個 version
        "app.demo.optimistic-lock-sleep-ms=500"
})
class PolicyOptimisticLockConcurrencyTest extends IntegrationTestBase {

    /**
     * Policy 1：王小明 — IN_FORCE 銀保通路躉繳壽險。version 從 V4 種子的 0 開始。
     * 為什麼選這張？確定是 IN_FORCE (能改地址)、有 2 個受益人 (將來其他測試用)。
     */
    private static final UUID POLICY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired private PolicyChangeService changeService;
    @Autowired private PolicyRepository policyRepository;
    @Autowired private PolicyChangeLogRepository changeLogRepository;

    @AfterEach
    void resetPolicyToInitialState() {
        // 把這張保單 reset 回 version=0、地址 reset 回原本，砍掉本測試寫進去的 change log。
        // 不用 @Sql 是因為要重設 @Version 欄位 — 直接 SQL UPDATE 會跟 Hibernate 的 entity
        // cache 不同步，不如走 Repository 重存。
        Policy p = policyRepository.findById(POLICY_ID).orElseThrow();
        p.setBillingAddress("台北市信義區信義路五段7號");
        // 強制把 version 拉回 0：先 native delete 再重撈 — 但實際上 Hibernate 不允許手動改
        // @Version 欄位（會被忽略）。最務實做法：保留變更後的 version，下次 test 用「動態起始
        // version」(下面的 act 階段已用 initialVersion 變數，不依賴硬編 0)。
        policyRepository.saveAndFlush(p);

        // 砍 change log，避免下一支測試斷言「count=1」時被前次殘留汙染
        List<PolicyChangeLog> logs = changeLogRepository.findByPolicyIdOrderByOccurredAtDesc(POLICY_ID);
        changeLogRepository.deleteAll(logs);
    }

    @Test
    @DisplayName("兩個 thread 同時 PATCH 地址 — 一個成功、一個拋 OptimisticLockingFailureException")
    void twoConcurrentChanges_oneWinsOneConflicts() throws Exception {

        // ─── Arrange ────────────────────────────────────────────────
        // 取目前 version 作為兩個 thread 的 expectedVersion。
        // 不寫死 = 0：因為前次測試 (例如 BancassuranceApplicationTests) 跑過後
        // version 可能已經是 N>0；用「當前值」最穩。
        Policy initial = policyRepository.findById(POLICY_ID).orElseThrow();
        long initialVersion = initial.getVersion();
        assertThat(initial.getStatus()).isEqualTo(PolicyStatus.IN_FORCE);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);   // 兩個 thread 共用的起跑線

        Callable<Outcome> taskA = makeChangeAddressTask(
                "alice", "台北市大安區忠孝東路四段1號", initialVersion, "thread-A", startGate);
        Callable<Outcome> taskB = makeChangeAddressTask(
                "bob",   "新北市板橋區縣民大道二段7號", initialVersion, "thread-B", startGate);

        // ─── Act ────────────────────────────────────────────────────
        Future<Outcome> futureA = executor.submit(taskA);
        Future<Outcome> futureB = executor.submit(taskB);

        // 兩個 thread 都已 await() 在起跑線；放他們衝
        startGate.countDown();

        Outcome resultA = futureA.get(30, TimeUnit.SECONDS);  // 留 30s 給第一次測試啟動 + 500ms sleep
        Outcome resultB = futureB.get(30, TimeUnit.SECONDS);

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // ─── Assert ─────────────────────────────────────────────────

        // 1. 一個成功、一個衝突（不關心誰勝誰負，只關心「恰好一勝一敗」）
        long successCount = countOf(resultA, Outcome.Status.SUCCESS) + countOf(resultB, Outcome.Status.SUCCESS);
        long conflictCount = countOf(resultA, Outcome.Status.CONFLICT) + countOf(resultB, Outcome.Status.CONFLICT);

        assertThat(successCount)
                .as("應該恰好一個 thread 成功 — 失敗代表樂觀鎖無效或 race condition 沒復現")
                .isEqualTo(1);
        assertThat(conflictCount)
                .as("應該恰好一個 thread 拋 OptimisticLockingFailureException")
                .isEqualTo(1);

        // 2. DB 裡 policy.version 只進版「一次」(N → N+1)，沒有 lost update
        Policy after = policyRepository.findById(POLICY_ID).orElseThrow();
        assertThat(after.getVersion())
                .as("樂觀鎖只該允許一次成功 UPDATE，version 從 %d 進到 %d", initialVersion, initialVersion + 1)
                .isEqualTo(initialVersion + 1);

        // 3. change_log 只寫一筆 (失敗那邊 rollback，連帶把 audit log 也丟掉)
        List<PolicyChangeLog> logs = changeLogRepository.findByPolicyIdOrderByOccurredAtDesc(POLICY_ID);
        assertThat(logs)
                .as("失敗的 thread 應該連 change_log 都不應該留下 — 證明 audit 與主交易共生死")
                .hasSize(1);
        assertThat(logs.getFirst().getAfterVersion())
                .as("change_log 紀錄的 afterVersion 應該等於勝利者寫進 DB 的新版本")
                .isEqualTo(initialVersion + 1);

        // 4. 最終地址是「兩個候選地址之一」(不在乎是哪個)
        assertThat(after.getBillingAddress())
                .isIn("台北市大安區忠孝東路四段1號", "新北市板橋區縣民大道二段7號");
    }

    // ════════════════════════════════════════════════════════════════
    // 工具方法
    // ════════════════════════════════════════════════════════════════

    private Callable<Outcome> makeChangeAddressTask(
            String actor, String newAddress, long expectedVersion,
            String label, CountDownLatch gate) {
        return () -> {
            gate.await();   // 等主線程 countDown，兩個 thread 同時衝
            try {
                changeService.changeAddress(
                        POLICY_ID,
                        null,                       // 不用 If-Match header，從 body 讀 expectedVersion
                        null,                       // 不用 Idempotency-Key (會觸發 replay 路徑)
                        new ChangeAddressRequest(expectedVersion, newAddress, "concurrency-test " + label),
                        actor);
                return Outcome.success(label);
            } catch (OptimisticLockingFailureException e) {
                return Outcome.conflict(label, e.getClass().getSimpleName());
            } catch (Throwable t) {
                return Outcome.unexpected(label, t);
            }
        };
    }

    private static long countOf(Outcome o, Outcome.Status status) {
        return o.status() == status ? 1 : 0;
    }

    /**
     * Test-local sealed 結果型別 — 比 boolean / String 更能表達 3 種結局。
     *
     * (Java 21 sealed + record 練習；面試聊到「Java 17/21 新語法你用過什麼」時可以講)
     */
    private record Outcome(Status status, String label, String detail) {
        enum Status { SUCCESS, CONFLICT, UNEXPECTED }
        static Outcome success(String label)            { return new Outcome(Status.SUCCESS, label, null); }
        static Outcome conflict(String label, String x) { return new Outcome(Status.CONFLICT, label, x); }
        static Outcome unexpected(String label, Throwable t) {
            return new Outcome(Status.UNEXPECTED, label, t.getClass().getName() + ": " + t.getMessage());
        }
    }
}
