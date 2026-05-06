package com.sean.bancassurance.underwriting.service;

import com.sean.bancassurance.common.exception.IllegalStateTransitionException;
import com.sean.bancassurance.common.exception.ResourceNotFoundException;
import com.sean.bancassurance.underwriting.api.dto.CaseEventResponse;
import com.sean.bancassurance.underwriting.api.dto.CreateUnderwritingCaseRequest;
import com.sean.bancassurance.underwriting.api.dto.TransitionRequests.ApproveRequest;
import com.sean.bancassurance.underwriting.api.dto.TransitionRequests.ClaimRequest;
import com.sean.bancassurance.underwriting.api.dto.TransitionRequests.RejectRequest;
import com.sean.bancassurance.underwriting.api.dto.TransitionRequests.RequestInfoRequest;
import com.sean.bancassurance.underwriting.api.dto.TransitionRequests.ResubmitRequest;
import com.sean.bancassurance.underwriting.api.dto.TransitionRequests.WithdrawRequest;
import com.sean.bancassurance.underwriting.api.dto.UnderwritingCaseResponse;
import com.sean.bancassurance.underwriting.domain.UnderwritingCase;
import com.sean.bancassurance.underwriting.domain.UnderwritingCaseEvent;
import com.sean.bancassurance.underwriting.domain.UnderwritingEventType;
import com.sean.bancassurance.underwriting.domain.UnderwritingStatus;
import com.sean.bancassurance.underwriting.repository.UnderwritingCaseEventRepository;
import com.sean.bancassurance.underwriting.repository.UnderwritingCaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 核保案件業務服務 — 含 M2 CRUD 與 M3 狀態機 transitions。
 *
 *  [M3 新增] transition 方法的共通模式：
 *    1. 載入 case (找不到 → 404)
 *    2. 用 enum.canTransitionTo 驗證合法跳轉 (非法 → 409 INVALID_STATE_TRANSITION)
 *    3. 檢查 transition-specific 的業務規則 (例如「補件 comment 必填」由 DTO Bean Validation 把關)
 *    4. 更新 case 欄位 (status / reviewedBy / reviewedAt / reviewComment)
 *    5. 寫入 underwriting_case_event (audit log)
 *    6. save() ← 同一個 transaction，任何一步失敗整個 rollback
 *
 *  為什麼 transition 邏輯不抽到 Entity 上 (rich domain model)?
 *    可以抽，但會把 EventRepository、log、稽核欄位設定都拖進 Entity，
 *    讓「資料」與「業務」混在一起。對 Spring + JPA 專案，service-layer 仍是主流。
 *    (面試題 / 資深)：「Anemic vs Rich domain model 你怎麼選？」
 *      答：團隊熟 DDD + 業務複雜 → Rich；快速迭代 / 業務規則不穩 → Anemic。
 *
 *  為什麼 @Transactional 要寫在「對外的 transition 方法」上，而不是 transition() 共用 helper？
 *    自呼叫陷阱：同 class 內呼叫 helper，proxy 不會被穿過 → 交易切面不會生效。
 *    所以：對外的 6 個方法各自掛 @Transactional，內部 helper 就跑在它們的交易裡。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)   // class 預設 readOnly；寫操作另外覆寫
public class UnderwritingCaseService {

    private final UnderwritingCaseRepository repository;
    private final UnderwritingCaseEventRepository eventRepository;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("Asia/Taipei"));

    // ════════════════════════════════════════════════════════════════
    // M2: CRUD
    // ════════════════════════════════════════════════════════════════

    /**
     * 建立 (送件) 一張核保案件。
     *
     * 此方法整個跑在一個交易裡：
     *  - 產生 caseNumber (有極小機率撞號 → 改用樂觀重試或 DB sequence)
     *  - INSERT underwriting_case + INSERT underwriting_case_event(CASE_SUBMITTED)
     *  - 失敗 (例如 unique constraint 撞號) → 整個 rollback
     */
    @Transactional
    public UnderwritingCaseResponse submit(CreateUnderwritingCaseRequest req) {
        String caseNumber = generateCaseNumber();
        Instant now = Instant.now();

        UnderwritingCase entity = UnderwritingCase.builder()
                .id(UUID.randomUUID())   // 暫用 v4；未來可換 UUID v7 generator
                .caseNumber(caseNumber)
                .applicantName(req.applicantName())
                .applicantIdNumber(req.applicantIdNumber())
                .productCode(req.productCode())
                .coverageAmount(req.coverageAmount())
                .premium(req.premium())
                .channel(req.channel())
                .status(UnderwritingStatus.SUBMITTED)   // 新案一律從 SUBMITTED 起跳
                .submittedBy(req.submittedBy())
                .submittedAt(now)
                .build();

        UnderwritingCase saved = repository.save(entity);

        // 第一筆事件：CASE_SUBMITTED (from=null → to=SUBMITTED)
        recordEvent(saved, UnderwritingEventType.CASE_SUBMITTED,
                null, UnderwritingStatus.SUBMITTED,
                req.submittedBy(), null, now);

        log.info("Underwriting case submitted: caseNumber={}, id={}", caseNumber, saved.getId());
        return UnderwritingCaseResponse.from(saved);
    }

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

    public Page<UnderwritingCaseResponse> list(UnderwritingStatus status, Pageable pageable) {
        Page<UnderwritingCase> page = (status == null)
                ? repository.findAll(pageable)
                : repository.findByStatus(status, pageable);
        return page.map(UnderwritingCaseResponse::from);
    }

    /**
     * 列出某案件的完整事件歷史 (按 occurred_at 排序)。
     * (M3 新增)
     */
    public List<CaseEventResponse> listEvents(UUID caseId) {
        // 先確認案件存在 (找不到回 404 而非空陣列；空陣列會誤導 client 以為案件存在但沒事件)
        if (!repository.existsById(caseId)) {
            throw new ResourceNotFoundException("UnderwritingCase", caseId.toString());
        }
        return eventRepository.findByCaseIdOrderByOccurredAtAsc(caseId).stream()
                .map(CaseEventResponse::from)
                .toList();
    }

    // ════════════════════════════════════════════════════════════════
    // M3: 狀態機 transitions
    // ════════════════════════════════════════════════════════════════

    /** 領件：SUBMITTED → UNDER_REVIEW */
    @Transactional
    public UnderwritingCaseResponse claim(UUID caseId, ClaimRequest req) {
        return doTransition(caseId, UnderwritingStatus.UNDER_REVIEW,
                UnderwritingEventType.CASE_CLAIMED, req.actor(), null,
                (c, now) -> {
                    c.setReviewedBy(req.actor());
                    // reviewedAt 在最後一次 review 動作覆寫；領件時先記初次領件時間
                    c.setReviewedAt(now);
                });
    }

    /** 要求補件：UNDER_REVIEW → PENDING_INFO */
    @Transactional
    public UnderwritingCaseResponse requestInfo(UUID caseId, RequestInfoRequest req) {
        return doTransition(caseId, UnderwritingStatus.PENDING_INFO,
                UnderwritingEventType.INFO_REQUESTED, req.actor(), req.comment(),
                (c, now) -> {
                    c.setReviewedBy(req.actor());
                    c.setReviewedAt(now);
                    c.setReviewComment(req.comment());
                });
    }

    /** 補件後重送：PENDING_INFO → UNDER_REVIEW */
    @Transactional
    public UnderwritingCaseResponse resubmit(UUID caseId, ResubmitRequest req) {
        return doTransition(caseId, UnderwritingStatus.UNDER_REVIEW,
                UnderwritingEventType.CASE_RESUBMITTED, req.actor(), req.comment(),
                (c, now) -> {
                    // resubmit 是業務員動作；不更動 reviewedBy (核保員)
                    // 但記得把上一輪的 reviewComment 清掉，避免誤導下個核保員
                    c.setReviewComment(null);
                });
    }

    /** 核准：UNDER_REVIEW → APPROVED */
    @Transactional
    public UnderwritingCaseResponse approve(UUID caseId, ApproveRequest req) {
        return doTransition(caseId, UnderwritingStatus.APPROVED,
                UnderwritingEventType.CASE_APPROVED, req.actor(), req.comment(),
                (c, now) -> {
                    c.setReviewedBy(req.actor());
                    c.setReviewedAt(now);
                    c.setReviewComment(req.comment());
                });
    }

    /** 退件：UNDER_REVIEW → REJECTED */
    @Transactional
    public UnderwritingCaseResponse reject(UUID caseId, RejectRequest req) {
        return doTransition(caseId, UnderwritingStatus.REJECTED,
                UnderwritingEventType.CASE_REJECTED, req.actor(), req.comment(),
                (c, now) -> {
                    c.setReviewedBy(req.actor());
                    c.setReviewedAt(now);
                    c.setReviewComment(req.comment());
                });
    }

    /** 撤件：(SUBMITTED | UNDER_REVIEW | PENDING_INFO) → WITHDRAWN */
    @Transactional
    public UnderwritingCaseResponse withdraw(UUID caseId, WithdrawRequest req) {
        return doTransition(caseId, UnderwritingStatus.WITHDRAWN,
                UnderwritingEventType.CASE_WITHDRAWN, req.actor(), req.comment(),
                (c, now) -> {
                    // 撤件可能是業務員或客戶；不動 reviewedBy
                    c.setReviewComment(req.comment());
                });
    }

    // ════════════════════════════════════════════════════════════════
    // 私有 helper
    // ════════════════════════════════════════════════════════════════

    /**
     * 共通 transition 流程：載入 → 驗證 → 套用客製欄位 → 更新狀態 → 寫事件 → save。
     *
     * 用 BiConsumer<Entity, Instant> 把「每個 transition 特有的欄位設定」
     * 注入進來；類似策略模式但更輕量 (不為了 6 個動作各開一個 class)。
     *
     * 注意：呼叫此方法的對外方法都標了 @Transactional，
     *      所以本 helper 跑在同一個交易裡 — save() 之外，rollback 也會涵蓋事件寫入。
     */
    private UnderwritingCaseResponse doTransition(
            UUID caseId,
            UnderwritingStatus targetStatus,
            UnderwritingEventType action,
            String actor,
            String comment,
            java.util.function.BiConsumer<UnderwritingCase, Instant> caseUpdater) {

        UnderwritingCase entity = repository.findById(caseId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "UnderwritingCase", caseId.toString()));

        UnderwritingStatus current = entity.getStatus();
        if (!current.canTransitionTo(targetStatus)) {
            throw new IllegalStateTransitionException(current, targetStatus);
        }

        Instant now = Instant.now();
        caseUpdater.accept(entity, now);
        entity.setStatus(targetStatus);

        // 因為 entity 已被 EntityManager 管理，setStatus 後 dirty checking 會在 commit
        // 時自動發 UPDATE。這裡呼叫 save() 不是必要，但顯式呼叫讓意圖清楚 (團隊風格)。
        UnderwritingCase saved = repository.save(entity);

        recordEvent(saved, action, current, targetStatus, actor, comment, now);

        log.info("Underwriting case transition: id={}, {} -> {}, action={}, actor={}",
                saved.getId(), current, targetStatus, action, actor);

        return UnderwritingCaseResponse.from(saved);
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
    }

    /**
     * 產生對外案件編號：UW-yyyyMMdd-XXXX (本地時區用台北)
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
        throw new IllegalStateException("Failed to generate unique caseNumber after 5 attempts");
    }
}
