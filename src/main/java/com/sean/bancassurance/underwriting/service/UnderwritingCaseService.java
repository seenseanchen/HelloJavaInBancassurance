package com.sean.bancassurance.underwriting.service;

import com.sean.bancassurance.common.exception.ResourceNotFoundException;
import com.sean.bancassurance.underwriting.api.dto.CreateUnderwritingCaseRequest;
import com.sean.bancassurance.underwriting.api.dto.UnderwritingCaseResponse;
import com.sean.bancassurance.underwriting.domain.UnderwritingCase;
import com.sean.bancassurance.underwriting.domain.UnderwritingStatus;
import com.sean.bancassurance.underwriting.repository.UnderwritingCaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 核保案件業務服務。
 *
 *  @Service
 *      語意：這個 class 是「業務邏輯層」。技術上跟 @Component 等價，
 *      但用 @Service 讓人一眼看出層級。
 *
 *  @Transactional   (來自 org.springframework.transaction.annotation)
 *      Spring 的交易切面：在進入方法前 begin transaction、正常結束 commit、
 *      丟出 RuntimeException 時 rollback。
 *      ★ 預設只對「未檢查例外 (RuntimeException) + Error」rollback，
 *        Checked Exception 不會！要全部 rollback：@Transactional(rollbackFor = Exception.class)。
 *      ★ 自呼叫陷阱 (self-invocation)：同一個 class 內 a() 呼叫 b()，b() 上的 @Transactional 不會生效，
 *        因為 Spring 的 AOP 是 dynamic proxy，必須由「外部」呼叫才會穿過 proxy。
 *      ★ readOnly = true 是給查詢方法用的提示，
 *        Hibernate 會關掉 dirty checking，效能更好；JDBC driver 也可能據此切到 read replica。
 *
 *  @RequiredArgsConstructor (Lombok)
 *      自動生成「對所有 final 欄位」的建構子。配合 Spring 4.3+ 的單一建構子規則
 *      → 不必標 @Autowired，依賴注入仍然成立。這是 constructor injection 的最佳實踐。
 *
 * (面試題 / 中級)：為什麼要用 constructor injection 而不是 field injection？
 *  1. 依賴必填且不可變 (final)：忘了注入啟動就失敗 (fail fast)
 *  2. 單元測試友善：不必動 reflection，直接 new 出來注入 mock
 *  3. 避免 circular dependency：構造期間就會發現
 *
 * (面試題 / 資深)：@Transactional 預設傳播是 REQUIRED，列出常見的 7 種傳播行為。
 *  REQUIRED / REQUIRES_NEW / NESTED / SUPPORTS / NOT_SUPPORTED / MANDATORY / NEVER
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)   // class 預設 readOnly；寫操作另外覆寫
public class UnderwritingCaseService {

    private final UnderwritingCaseRepository repository;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("Asia/Taipei"));

    // ────────────────────────────────────────────────────────────────
    // 寫操作：必須覆寫 readOnly = false
    // ────────────────────────────────────────────────────────────────

    /**
     * 建立 (送件) 一張核保案件。
     *
     * 此方法整個跑在一個交易裡：
     *  - 產生 caseNumber (有極小機率撞號 → 改用樂觀重試或 DB sequence)
     *  - INSERT underwriting_case
     *  - 失敗 (例如 unique constraint 撞號) → 整個 rollback
     */
    @Transactional
    public UnderwritingCaseResponse submit(CreateUnderwritingCaseRequest req) {
        String caseNumber = generateCaseNumber();

        UnderwritingCase entity = UnderwritingCase.builder()
                .id(UUID.randomUUID())   // M2 暫用 v4；之後可換 UUID v7 generator
                .caseNumber(caseNumber)
                .applicantName(req.applicantName())
                .applicantIdNumber(req.applicantIdNumber())
                .productCode(req.productCode())
                .coverageAmount(req.coverageAmount())
                .premium(req.premium())
                .channel(req.channel())
                .status(UnderwritingStatus.SUBMITTED)   // 新案一律從 SUBMITTED 起跳
                .submittedBy(req.submittedBy())
                .submittedAt(Instant.now())
                .build();

        UnderwritingCase saved = repository.save(entity);
        log.info("Underwriting case submitted: caseNumber={}, id={}", caseNumber, saved.getId());

        return UnderwritingCaseResponse.from(saved);
    }

    // ────────────────────────────────────────────────────────────────
    // 讀操作：用 class 預設的 readOnly = true
    // ────────────────────────────────────────────────────────────────

    public UnderwritingCaseResponse getById(UUID id) {
        return repository.findById(id)
                .map(UnderwritingCaseResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "UnderwritingCase", id.toString()));
    }

    public UnderwritingCaseResponse getByCaseNumber(String caseNumber) {
        return repository.findByCaseNumber(caseNumber)
                .map(UnderwritingCaseResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "UnderwritingCase", caseNumber));
    }

    /**
     * 分頁列表，可依狀態過濾。
     * Page<Entity> ─map─▶ Page<DTO> 是常見寫法，重點：別把 Entity 直接回給 Controller。
     */
    public Page<UnderwritingCaseResponse> list(UnderwritingStatus status, Pageable pageable) {
        Page<UnderwritingCase> page = (status == null)
                ? repository.findAll(pageable)
                : repository.findByStatus(status, pageable);
        return page.map(UnderwritingCaseResponse::from);
    }

    // ────────────────────────────────────────────────────────────────
    // 私有 helper
    // ────────────────────────────────────────────────────────────────

    /**
     * 產生對外案件編號：UW-yyyyMMdd-XXXX (本地時區用台北)
     *
     * 簡化版：用隨機 4 位數 + 撞號重試。
     * 真實系統會用 DB sequence，避免併發撞號。
     */
    private String generateCaseNumber() {
        String date = DATE_FMT.format(Instant.now());
        for (int attempt = 0; attempt < 5; attempt++) {
            int suffix = ThreadLocalRandom.current().nextInt(1, 10000);
            String candidate = "UW-%s-%04d".formatted(date, suffix);
            if (!repository.existsByCaseNumber(candidate)) {
                return candidate;
            }
        }
        // 5 次都撞號的機率極低；真撞了，讓上層 (例如全域 handler) 處理。
        throw new IllegalStateException("Failed to generate unique caseNumber after 5 attempts");
    }
}
