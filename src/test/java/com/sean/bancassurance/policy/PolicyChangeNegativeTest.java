package com.sean.bancassurance.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sean.bancassurance.IntegrationTestBase;
import com.sean.bancassurance.policy.api.dto.BeneficiaryUpsert;
import com.sean.bancassurance.policy.api.dto.ChangeAddressRequest;
import com.sean.bancassurance.policy.api.dto.ChangeBeneficiariesRequest;
import com.sean.bancassurance.policy.api.dto.ChangePaymentMethodRequest;
import com.sean.bancassurance.policy.domain.BeneficiaryRelationship;
import com.sean.bancassurance.policy.domain.Policy;
import com.sean.bancassurance.policy.domain.PolicyStatus;
import com.sean.bancassurance.policy.domain.PremiumPaymentMethod;
import com.sean.bancassurance.policy.repository.PolicyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M5 反向案例 — 各種「該失敗」的場景。
 *
 * ════════════════════════════════════════════════════════════════════════
 *  反向案例的價值 (面試)：
 *    happy path 只證明「正常情況下能用」。生產 bug 多半在 sad path：
 *      - client 帶舊版本 → 該擋下，不能讓 lost update 發生
 *      - 受益人比例算錯 → 該擋下，不能讓保單金額分配為 99%
 *      - 對不該變更的狀態強行變更 → 該擋下，不能讓停效保單被改地址、繞過繳費追蹤
 *    寫反向測試的潛台詞：「我考慮過這些 edge case，且驗證每個都會被擋下。」
 *
 *  本檔案覆蓋的 HTTP 錯誤碼：
 *    412 PRECONDITION_FAILED        — If-Match / expectedVersion 不符
 *    409 INVALID_POLICY_STATE       — 對 LAPSED / MATURED 保單做變更
 *    422 BUSINESS_RULE_VIOLATION    — 受益人比例 / SINGLE_PAY 變更等業務規則
 *    400 VALIDATION_FAILED          — Bean Validation (@NotNull / @Pattern) 失敗
 *
 *  ── 為什麼這四個錯誤碼要分清楚？(面試 ★ 高頻) ──────────────────────
 *
 *    400 — 客戶端送錯「格式」(語法/型別/必填)。client 改 JSON 即可。
 *    409 — 格式對，但跟伺服器當前狀態衝突 (狀態機、樂觀鎖)。client 要先重查再重試。
 *    412 — 條件式請求 (If-Match) 比對失敗，跟「樂觀鎖衝突」(409) 的差別在於：
 *           412 是「我還沒下手就先告訴你版本過期」、409 是「下手後 DB 才告訴你晚了一步」。
 *           這個專案兩個都實作 — 412 是 service 第一道防線，409 是 DB 最後一道防線。
 *    422 — 格式對、狀態也對，但語意違反業務規則 (例如比例加總≠100)。
 *
 *    某些公司只用 400 + 409 + 500 三種，把 412/422 都當 400 — 是化簡、但失去精度。
 *    銀行業 API 對接 SIEM / 監控系統時，code 越精準越好排錯。
 * ════════════════════════════════════════════════════════════════════════
 *
 *  ── 為什麼用 Policy 4 (IN_FORCE) + Policy 3 (LAPSED) ─────────────────
 *   Policy 4 是 IN_FORCE 銀保通路躉繳壽險，1 個受益人 — 用來測「條件對但業務規則違反」
 *   Policy 3 是 LAPSED (停效) — 用來測「狀態不對」(409 INVALID_POLICY_STATE)
 *   Policy 1/2 被前面測試用了，這裡不撞。
 */
@DisplayName("M5 保單變更 — 反向案例 (412 / 409 / 422)")
class PolicyChangeNegativeTest extends IntegrationTestBase {

    /** Policy 4：陳大明的第二張，IN_FORCE 線上躉繳，1 個受益人 */
    private static final UUID IN_FORCE_POLICY_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    /** Policy 3：陳大明的第一張，LAPSED — 用來測「狀態不對不准改」 */
    private static final UUID LAPSED_POLICY_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PolicyRepository policyRepository;

    // ════════════════════════════════════════════════════════════════
    // 412 PRECONDITION_FAILED — If-Match / expectedVersion 不符
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("body.expectedVersion 比實際版本大 → 412 PRECONDITION_FAILED")
    void bodyExpectedVersionTooLarge_returns412() throws Exception {

        Policy initial = policyRepository.findById(IN_FORCE_POLICY_ID).orElseThrow();
        long currentVersion = initial.getVersion();

        // 比當前 version 大 999 — 一定 > 0 過 @Min(0)，但比真實版本大 → 412
        // (沒帶 If-Match，service 會 fallback 到 body.expectedVersion)
        ChangeAddressRequest body = new ChangeAddressRequest(
                currentVersion + 999, "台北市內湖區瑞光路478號", "stale-version test (body)");

        mockMvc.perform(patch("/api/policies/{policyId}/address", IN_FORCE_POLICY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isPreconditionFailed())   // 412
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"))
                .andExpect(jsonPath("$.status").value(412))
                .andExpect(header().exists("X-Trace-Id"));

        Policy after = policyRepository.findById(IN_FORCE_POLICY_ID).orElseThrow();
        assertThat(after.getVersion())
                .as("412 不應該動到 DB")
                .isEqualTo(currentVersion);
    }

    @Test
    @DisplayName("If-Match header 優先於 body — 即使 body 帶對的版本，header 錯也 412")
    void ifMatchHeaderTakesPrecedence_andTriggers412() throws Exception {

        Policy initial = policyRepository.findById(IN_FORCE_POLICY_ID).orElseThrow();
        long currentVersion = initial.getVersion();

        // body 帶「正確的」版本 (能過 @Min(0))，但 If-Match header 帶錯
        // 用來證明：service 的 resolveExpectedVersion 確實「優先讀 header」
        ChangeAddressRequest body = new ChangeAddressRequest(
                currentVersion,                        // 正確 — 不會被 Bean Validation 擋下
                "台北市內湖區行愛路100號",
                "If-Match precedence test");

        mockMvc.perform(patch("/api/policies/{policyId}/address", IN_FORCE_POLICY_ID)
                        // 故意送錯誤的 If-Match — header 取勝就會 412
                        .header("If-Match", "\"99\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));

        Policy after = policyRepository.findById(IN_FORCE_POLICY_ID).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(currentVersion);
    }

    // ════════════════════════════════════════════════════════════════
    // 409 INVALID_POLICY_STATE — 對非 IN_FORCE 保單變更
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("對 LAPSED 保單變更地址 → 409 INVALID_POLICY_STATE")
    void changeAddressOnLapsedPolicy_returns409() throws Exception {

        // 確認種子資料
        Policy lapsed = policyRepository.findById(LAPSED_POLICY_ID).orElseThrow();
        assertThat(lapsed.getStatus())
                .as("這支測試前提是 Policy 3 為 LAPSED；如果失敗檢查 V4 種子")
                .isEqualTo(PolicyStatus.LAPSED);

        ChangeAddressRequest body = new ChangeAddressRequest(
                lapsed.getVersion(), "新竹市東區光復路二段101號", "address change on LAPSED");

        mockMvc.perform(patch("/api/policies/{policyId}/address", LAPSED_POLICY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())              // 409
                .andExpect(jsonPath("$.code").value("INVALID_POLICY_STATE"))
                .andExpect(jsonPath("$.status").value(409));
    }

    // ════════════════════════════════════════════════════════════════
    // 422 BUSINESS_RULE_VIOLATION — 受益人加總 ≠ 100
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("受益人比例加總 99 → 422 BUSINESS_RULE_VIOLATION")
    void beneficiariesAllocationSumNot100_returns422() throws Exception {

        Policy initial = policyRepository.findById(IN_FORCE_POLICY_ID).orElseThrow();

        // 兩個受益人加起來 99 (60 + 39)，故意少 1
        ChangeBeneficiariesRequest body = new ChangeBeneficiariesRequest(
                initial.getVersion(),
                List.of(
                        new BeneficiaryUpsert("配偶", "B987654321",
                                BeneficiaryRelationship.SPOUSE, new BigDecimal("60.00"), 1),
                        new BeneficiaryUpsert("子女", "C123456789",
                                BeneficiaryRelationship.CHILD,  new BigDecimal("39.00"), 1)
                ),
                "故意比例加總 99 測 422");

        mockMvc.perform(patch("/api/policies/{policyId}/beneficiaries", IN_FORCE_POLICY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())   // 422
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("100")));
    }

    @Test
    @DisplayName("受益人沒人 priority=1 → 422 BUSINESS_RULE_VIOLATION")
    void beneficiariesNoPriorityOne_returns422() throws Exception {

        Policy initial = policyRepository.findById(IN_FORCE_POLICY_ID).orElseThrow();

        // 加總 = 100，但兩個都是 priority=2 — 沒有第一順位
        ChangeBeneficiariesRequest body = new ChangeBeneficiariesRequest(
                initial.getVersion(),
                List.of(
                        new BeneficiaryUpsert("配偶", "B987654321",
                                BeneficiaryRelationship.SPOUSE, new BigDecimal("50.00"), 2),
                        new BeneficiaryUpsert("子女", "C123456789",
                                BeneficiaryRelationship.CHILD,  new BigDecimal("50.00"), 2)
                ),
                "故意沒人 priority=1 測 422");

        mockMvc.perform(patch("/api/policies/{policyId}/beneficiaries", IN_FORCE_POLICY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("priority")));
    }

    // ════════════════════════════════════════════════════════════════
    // 422 BUSINESS_RULE_VIOLATION — 不允許改成 SINGLE_PAY
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("變更繳費方式為 SINGLE_PAY → 422 BUSINESS_RULE_VIOLATION")
    void changePaymentMethodToSinglePay_returns422() throws Exception {

        Policy initial = policyRepository.findById(IN_FORCE_POLICY_ID).orElseThrow();

        ChangePaymentMethodRequest body = new ChangePaymentMethodRequest(
                initial.getVersion(),
                PremiumPaymentMethod.SINGLE_PAY,   // ★ 業務上禁止
                "test SINGLE_PAY rejection");

        mockMvc.perform(patch("/api/policies/{policyId}/payment-method", IN_FORCE_POLICY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("SINGLE_PAY")));
    }

    // ════════════════════════════════════════════════════════════════
    // 404 RESOURCE_NOT_FOUND — 找不到的保單 id (順手測，反正一行)
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("變更不存在的保單 → 404 RESOURCE_NOT_FOUND")
    void changeAddressOnNonexistentPolicy_returns404() throws Exception {

        UUID ghostPolicy = UUID.fromString("99999999-9999-9999-9999-999999999999");
        ChangeAddressRequest body = new ChangeAddressRequest(
                0L, "台北市仁愛路四段100號", "ghost policy");

        mockMvc.perform(patch("/api/policies/{policyId}/address", ghostPolicy)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound())              // 404
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // ════════════════════════════════════════════════════════════════
    // 400 VALIDATION_FAILED — Bean Validation 違反
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("expectedVersion 為負數 → 400 VALIDATION_FAILED (Bean Validation)")
    void negativeExpectedVersion_returns400() throws Exception {

        // expectedVersion=-1 違反 @Min(0)
        // 這個被 @Valid + @Min 在 controller 層擋下，根本不會進 service
        // → 證明 Bean Validation 跟業務規則 (422) 是分開的兩道防線
        ChangeAddressRequest body = new ChangeAddressRequest(
                -1L, "台北市忠孝東路四段1號", "negative version");

        mockMvc.perform(patch("/api/policies/{policyId}/address", IN_FORCE_POLICY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())            // 400
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details[0].field").value("expectedVersion"));
    }
}
