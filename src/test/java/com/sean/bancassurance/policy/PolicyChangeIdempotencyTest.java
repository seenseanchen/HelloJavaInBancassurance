package com.sean.bancassurance.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sean.bancassurance.IntegrationTestBase;
import com.sean.bancassurance.common.idempotency.IdempotencyRecord;
import com.sean.bancassurance.common.idempotency.IdempotencyRecordRepository;
import com.sean.bancassurance.policy.api.dto.ChangeAddressRequest;
import com.sean.bancassurance.policy.domain.Policy;
import com.sean.bancassurance.policy.domain.PolicyChangeLog;
import com.sean.bancassurance.policy.repository.PolicyChangeLogRepository;
import com.sean.bancassurance.policy.repository.PolicyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M5 冪等性 — Idempotency-Key replay 行為測試。
 *
 * ════════════════════════════════════════════════════════════════════════
 *  業務動機 (面試話術)：
 *    銀保場景常因網路抖動讓客戶重送同一個變更請求。沒有冪等保護的話：
 *      - 客戶按一次「送出」、實際打了 3 次後端 → 受益人被改 3 次、log 多 3 筆
 *      - 客戶體驗：看到 1 次成功訊息 + 後續 2 次「樂觀鎖衝突」
 *    解法：client 為每個邏輯操作生成 UUID 當 Idempotency-Key，server 把
 *      (key, request_hash, response_body) 寫進 idempotency_record 表，TTL 24h。
 *      重送相同 (key, body) → 直接 replay 上次的 response，不重做業務邏輯。
 *
 *  斷言三件事：
 *    1. 同 key + 同 body 重送 — 第二次回 200 + 同樣 response，DB 沒進版、log 沒增量
 *    2. 同 key + 不同 body — 第二次 422 IDEMPOTENCY_KEY_REUSED (防 client 改內容)
 *    3. idempotency_record 表的確被寫入 (要保證 24h 內 replay 路徑可命中)
 * ════════════════════════════════════════════════════════════════════════
 *
 *  ── 為什麼用 MockMvc 而不直接呼叫 Service？──────────────────────────
 *   冪等性是 HTTP 層的契約 — 我們要驗的是「整段 path：header 解析、
 *   service replay、wrapper 包裝、ETag header」全程串得起來。MockMvc 模擬
 *   完整的 DispatcherServlet 流程，但不開真 socket，是「整合測試 + 快」的折衷。
 *
 *  ── 各種 thread-safety / TX 邊界 ───────────────────────────────────
 *   MockMvc.perform() 是同步的，順序執行兩次 PATCH，第一次 commit 後第二次才開始。
 *   所以這支測試「不」需要 demo sleep — 那是樂觀鎖併發測試專用。
 *
 *  ── 為什麼用 Policy 2 (李美華) 而不是 Policy 1？─────────────────────
 *   Policy 1 被 OptimisticLockConcurrencyTest 用了。讓兩個測試各據一張保單，
 *   若有測試遺留狀態也只汙染自己那張，不會影響旁邊測試。
 */
@DisplayName("M5 保單變更 — 冪等性 (Idempotency-Key) 測試")
class PolicyChangeIdempotencyTest extends IntegrationTestBase {

    /** Policy 2：李美華 — IN_FORCE 業務員月繳投資型，1 個受益人 */
    private static final UUID POLICY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PolicyRepository policyRepository;
    @Autowired private PolicyChangeLogRepository changeLogRepository;
    @Autowired private IdempotencyRecordRepository idempotencyRepository;

    @AfterEach
    void cleanupTestArtifacts() {
        // 砍本測試寫的 change log + idempotency record，讓下次重跑乾淨。
        // policy.version 不重設 — 用「動態當前版本」風格，對 version 的當前值不敏感。
        changeLogRepository.deleteAll(
                changeLogRepository.findByPolicyIdOrderByOccurredAtDesc(POLICY_ID));
        // 為了避免影響其他測試，砍掉本測試生成的 idempotency_record 也很重要 —
        // 不過我們的 key 用 UUID.randomUUID() 每次測試都不同，理論上不會撞，
        // 還是保險起見刪掉所有由本測試 endpoint 產生的紀錄。
        // 簡單做法：deleteAll() 全清 (測試之間互不依賴 idempotency state)
        idempotencyRepository.deleteAll();
    }

    // ════════════════════════════════════════════════════════════════
    // 案例 1：同 key + 同 body 兩次 → 第二次 replay
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("同 Idempotency-Key + 同 body 重送 → 200 + 不重複寫 DB")
    void sameKeySameBody_secondCallReplaysWithoutDoubleWrite() throws Exception {

        // ─── Arrange ────────────────────────────────────────────────
        Policy initial = policyRepository.findById(POLICY_ID).orElseThrow();
        long initialVersion = initial.getVersion();

        String idempotencyKey = UUID.randomUUID().toString();
        ChangeAddressRequest body = new ChangeAddressRequest(
                initialVersion, "新北市新店區北新路三段88號", "搬家更新地址");
        String bodyJson = objectMapper.writeValueAsString(body);

        // ─── Act 1：第一次呼叫 — 真的執行業務邏輯 ─────────────────
        mockMvc.perform(patch("/api/policies/{policyId}/address", POLICY_ID)
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"" + (initialVersion + 1) + "\""))
                // ApiResponse 包裝後：body.data.version 才是 PolicyResponse.version
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.version").value(initialVersion + 1))
                .andExpect(jsonPath("$.data.billingAddress").value("新北市新店區北新路三段88號"));

        // 第一次後：DB 已進版 + idempotency_record 已寫入 + change_log 多一筆
        Policy afterFirst = policyRepository.findById(POLICY_ID).orElseThrow();
        assertThat(afterFirst.getVersion()).isEqualTo(initialVersion + 1);

        IdempotencyRecord record = idempotencyRepository.findById(idempotencyKey).orElseThrow();
        assertThat(record.getEndpoint()).isEqualTo("PATCH /api/policies/{id}/address");
        assertThat(record.getResponseStatus()).isEqualTo(200);
        assertThat(record.getRequestHash()).isNotBlank();
        assertThat(record.getExpiresAt()).isAfter(record.getCreatedAt());

        List<PolicyChangeLog> logsAfterFirst =
                changeLogRepository.findByPolicyIdOrderByOccurredAtDesc(POLICY_ID);
        assertThat(logsAfterFirst).hasSize(1);

        // ─── Act 2：第二次同 key 同 body — 應該走 replay 路徑 ─────────
        mockMvc.perform(patch("/api/policies/{policyId}/address", POLICY_ID)
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(status().isOk())
                // ★ 關鍵斷言：response.version 還是 initialVersion+1 (replay 上次的)；
                //   而不是 initialVersion+2 (那才是「真的又跑了一次」)
                .andExpect(jsonPath("$.data.version").value(initialVersion + 1))
                .andExpect(jsonPath("$.data.billingAddress").value("新北市新店區北新路三段88號"));

        // ─── Assert：DB 沒被第二次呼叫動到 ─────────────────────────
        Policy afterSecond = policyRepository.findById(POLICY_ID).orElseThrow();
        assertThat(afterSecond.getVersion())
                .as("第二次 replay 不應該再進版 — version 應該停在 %d", initialVersion + 1)
                .isEqualTo(initialVersion + 1);

        List<PolicyChangeLog> logsAfterSecond =
                changeLogRepository.findByPolicyIdOrderByOccurredAtDesc(POLICY_ID);
        assertThat(logsAfterSecond)
                .as("replay 不應該再寫一筆 change_log")
                .hasSize(1);
    }

    // ════════════════════════════════════════════════════════════════
    // 案例 2：同 key + 不同 body → 422
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("同 Idempotency-Key + 不同 body → 422 IDEMPOTENCY_KEY_REUSED")
    void sameKeyDifferentBody_returns422() throws Exception {

        Policy initial = policyRepository.findById(POLICY_ID).orElseThrow();
        long initialVersion = initial.getVersion();

        String idempotencyKey = UUID.randomUUID().toString();

        // 第一次：地址 A
        ChangeAddressRequest bodyA = new ChangeAddressRequest(
                initialVersion, "台北市中山區南京東路二段100號", "first call");
        mockMvc.perform(patch("/api/policies/{policyId}/address", POLICY_ID)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bodyA)))
                .andExpect(status().isOk());

        // 第二次：同 key 但 newAddress 不一樣 → 422
        // ★ 這個防護是為了擋「client 用同 key 送不同 payload」這種違反冪等定義的
        //   行為。RFC 7231 沒明文，但業界共識：同 key 必須對應同樣的副作用。
        ChangeAddressRequest bodyB = new ChangeAddressRequest(
                initialVersion + 1,                       // 用更新後的版本
                "高雄市左營區博愛二路777號",                  // ★ 不同地址
                "second call with different body");
        mockMvc.perform(patch("/api/policies/{policyId}/address", POLICY_ID)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bodyB)))
                .andExpect(status().isUnprocessableEntity())   // 422
                // ApiError 不被 ApiResponseWrapper 包裝 — 是 top-level
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.status").value(422))
                // GlobalExceptionHandler 設了 X-Trace-Id header
                .andExpect(header().exists("X-Trace-Id"));

        // 第二次失敗，DB 應該沒被「第二次」改 — version 停在 +1 (來自第一次)
        Policy afterBoth = policyRepository.findById(POLICY_ID).orElseThrow();
        assertThat(afterBoth.getVersion()).isEqualTo(initialVersion + 1);
        assertThat(afterBoth.getBillingAddress())
                .as("第二次失敗，地址應該還是第一次寫進去的值")
                .isEqualTo("台北市中山區南京東路二段100號");
    }

    // ════════════════════════════════════════════════════════════════
    // 案例 3：沒帶 Idempotency-Key — 不啟用冪等，每次都真的執行
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("沒帶 Idempotency-Key → 每次呼叫都真的執行（用以對照案例 1）")
    void noIdempotencyKey_eachCallExecutes() throws Exception {

        Policy initial = policyRepository.findById(POLICY_ID).orElseThrow();
        long initialVersion = initial.getVersion();

        // 第一次：不帶 key，地址 A
        mockMvc.perform(patch("/api/policies/{policyId}/address", POLICY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangeAddressRequest(initialVersion,
                                        "台中市西區忠明南路123號", "no key first call"))))
                .andExpect(status().isOk());

        // 第二次：仍不帶 key，但帶當前 version
        mockMvc.perform(patch("/api/policies/{policyId}/address", POLICY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangeAddressRequest(initialVersion + 1,
                                        "桃園市中壢區中央西路二段50號", "no key second call"))))
                .andExpect(status().isOk());

        // ─── Assert：兩次都真的執行 ────────────────────────────────
        Policy afterBoth = policyRepository.findById(POLICY_ID).orElseThrow();
        assertThat(afterBoth.getVersion())
                .as("沒 key 兩次都該被執行 — version 從 %d 進到 %d", initialVersion, initialVersion + 2)
                .isEqualTo(initialVersion + 2);
        assertThat(afterBoth.getBillingAddress())
                .isEqualTo("桃園市中壢區中央西路二段50號");

        List<PolicyChangeLog> logs =
                changeLogRepository.findByPolicyIdOrderByOccurredAtDesc(POLICY_ID);
        assertThat(logs)
                .as("兩次都各寫一筆 change_log")
                .hasSize(2);
    }
}
