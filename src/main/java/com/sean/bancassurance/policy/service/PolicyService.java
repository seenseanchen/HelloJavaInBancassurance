package com.sean.bancassurance.policy.service;

import com.sean.bancassurance.common.exception.ResourceNotFoundException;
import com.sean.bancassurance.policy.api.dto.PolicyResponse;
import com.sean.bancassurance.policy.api.dto.PolicySummaryResponse;
import com.sean.bancassurance.policy.domain.Policy;
import com.sean.bancassurance.policy.repository.PolicyRepository;
import com.sean.bancassurance.policy.repository.PolicySpecifications;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 保單服務 — M4 上半「查詢」階段。
 *
 *  ── 為什麼整個 class 標 @Transactional(readOnly = true)？──────────
 *    1. Hibernate 對 readOnly 交易會關閉「dirty checking 一些開銷」、設 FlushMode 為
 *       MANUAL → 純查詢效能更好。
 *    2. 防呆：不小心在 readOnly service 裡 setXxx() 也不會 flush 出去 (除非顯式 save)。
 *    3. M5 寫操作 (PATCH 變更) 我們會在「個別方法」上覆寫 @Transactional (預設 readWrite)。
 *
 *  ── 為什麼 getById 用 findByIdWithBeneficiaries？──────────────────
 *    回傳 PolicyResponse 一定要把受益人也回出去 → 在交易內就 fetch，
 *    避免 controller 層轉成 JSON 時觸發 LazyInitializationException。
 *
 *  ── 為什麼 search() 用 Specification + 簡介 DTO？─────────────────
 *    清單頁可能回 100 筆，每筆都 fetch 受益人會放大 SQL 數量。
 *    用 PolicySummaryResponse (不含 beneficiaries) → JPA 只下「主表 SELECT」。
 *
 *  (面試題 / 中級)：「@Transactional 用 class 還是 method？哪個優先？」
 *    答：method 上的 @Transactional 會覆寫 class 上的設定。
 *        最佳實踐：class 預設 readOnly = true (查詢居多)，個別寫方法另標 @Transactional。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PolicyService {

    private final PolicyRepository repository;

    /**
     * 用內部 UUID 查單筆（含受益人）。
     */
    public PolicyResponse getById(UUID id) {
        Policy policy = repository.findByIdWithBeneficiaries(id)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", id.toString()));
        return PolicyResponse.from(policy);
    }

    /**
     * 用對外保單號查單筆（含受益人）— 客服最常打的 API。
     */
    public PolicyResponse getByPolicyNumber(String policyNumber) {
        Policy policy = repository.findByPolicyNumberWithBeneficiaries(policyNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", policyNumber));
        return PolicyResponse.from(policy);
    }

    /**
     * 多條件動態查詢（清單頁）。
     *
     * 用 Specification 把「每個過濾條件」組起來，null 條件自動跳過。
     * 回 Page<PolicySummaryResponse> — 不含受益人，避免 N+1。
     *
     * (面試題 / 資深)：「分頁查詢遇到深分頁問題怎麼辦？」
     *   - LIMIT/OFFSET 在 OFFSET 100000 時 DB 仍要掃前 100000 筆，效能爛
     *   - 解法：keyset pagination (cursor-based)，用 (created_at, id) 當 cursor
     *   - 但客服 / 業務員後台分頁通常不會超過幾十頁 → 直接用 Pageable 就夠
     */
    public Page<PolicySummaryResponse> search(PolicySearchCriteria criteria, Pageable pageable) {
        Specification<Policy> spec = Specification
                .allOf(
                        PolicySpecifications.holderIdNumberIs(criteria.holderIdNumber()),
                        PolicySpecifications.statusIs(criteria.status()),
                        PolicySpecifications.productCodeIs(criteria.productCode()),
                        PolicySpecifications.channelIs(criteria.channel()),
                        PolicySpecifications.effectiveDateBetween(
                                criteria.effectiveDateFrom(), criteria.effectiveDateTo())
                );

        Page<Policy> page = repository.findAll(spec, pageable);
        log.debug("Policy search: criteria={}, totalElements={}", criteria, page.getTotalElements());
        return page.map(PolicySummaryResponse::from);
    }
}
