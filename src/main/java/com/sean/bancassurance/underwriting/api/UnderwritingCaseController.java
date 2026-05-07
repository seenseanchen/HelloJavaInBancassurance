package com.sean.bancassurance.underwriting.api;

import com.sean.bancassurance.common.exception.ApiError;
import com.sean.bancassurance.underwriting.api.dto.CaseEventResponse;
import com.sean.bancassurance.underwriting.api.dto.CreateUnderwritingCaseRequest;
import com.sean.bancassurance.underwriting.api.dto.TransitionRequests.ApproveRequest;
import com.sean.bancassurance.underwriting.api.dto.TransitionRequests.ClaimRequest;
import com.sean.bancassurance.underwriting.api.dto.TransitionRequests.RejectRequest;
import com.sean.bancassurance.underwriting.api.dto.TransitionRequests.RequestInfoRequest;
import com.sean.bancassurance.underwriting.api.dto.TransitionRequests.ResubmitRequest;
import com.sean.bancassurance.underwriting.api.dto.TransitionRequests.WithdrawRequest;
import com.sean.bancassurance.underwriting.api.dto.UnderwritingCaseResponse;
import com.sean.bancassurance.underwriting.domain.UnderwritingStatus;
import com.sean.bancassurance.underwriting.service.UnderwritingCaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * 核保案件 REST Controller。
 *
 *  @RestController
 *      = @Controller + @ResponseBody → 回傳值直接序列化為 JSON。
 *
 *  @Tag (OpenAPI)
 *      將此 Controller 的所有 endpoint 歸入 Swagger UI 的同一個分組。
 *
 *  @RequestMapping("/api/underwriting/cases")
 *      整個 Controller 共用前綴。RESTful 設計：以「資源」為核心，動詞用 HTTP method。
 *
 *  @Valid (在 RequestBody 旁)
 *      觸發 Bean Validation。驗證失敗會丟 MethodArgumentNotValidException，
 *      被 @RestControllerAdvice 接住翻成 HTTP 400。
 *
 *  @PageableDefault
 *      指定預設分頁參數。client 沒帶 ?page=&size= 也能正常運作。
 *
 *  ResponseEntity vs 直接回 DTO
 *      - 需要自訂 status code / header (例如 201 Created + Location header) → ResponseEntity
 *      - 一律 200 OK → 直接回 DTO 即可，更簡潔
 *
 *  Location header (RESTful POST 規範)
 *      建立資源後，在 Location header 帶上「新資源的 URI」，client 可直接 GET。
 *      這是 Richardson Maturity Model Level 3 的良好實踐。
 *
 * (面試題 / 初級)：@RequestParam vs @PathVariable 差別？
 *   @PathVariable: /cases/{id} 路徑參數
 *   @RequestParam: /cases?status=APPROVED 查詢字串
 */
@Tag(
    name = "核保案件 (Underwriting Cases)",
    description = "人壽保險核保流程：客戶投保送件 → 核保員領件審查 → 核准 / 退件 / 要求補件。" +
                  "狀態流程：SUBMITTED → UNDER_REVIEW → APPROVED | REJECTED | PENDING_INFO"
)
@RestController
@RequestMapping("/api/underwriting/cases")
@RequiredArgsConstructor
public class UnderwritingCaseController {

    private final UnderwritingCaseService service;

    /**
     * POST /api/underwriting/cases — 送件
     */
    @Operation(
        summary = "送件：建立新核保案件",
        description = "客戶透過業務員送出投保申請。建立後狀態為 `SUBMITTED`，" +
                      "同時在 Location header 回傳新案件 URI。"
    )
    @ApiResponse(responseCode = "201", description = "送件成功，回傳案件資訊")
    @ApiResponse(responseCode = "400", description = "欄位驗證失敗",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<UnderwritingCaseResponse> submit(
            @Valid @RequestBody CreateUnderwritingCaseRequest request) {

        UnderwritingCaseResponse created = service.submit(request);

        // 帶 Location header 給 client：/api/underwriting/cases/{id}
        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    /**
     * GET /api/underwriting/cases/{id} — 用 UUID 查
     */
    @Operation(summary = "查單筆案件（by UUID）")
    @ApiResponse(responseCode = "200", description = "查詢成功")
    @ApiResponse(responseCode = "404", description = "案件不存在",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{id}")
    public UnderwritingCaseResponse getById(
            @Parameter(description = "案件內部 UUID") @PathVariable UUID id) {
        return service.getById(id);
    }

    /**
     * GET /api/underwriting/cases/by-number/{caseNumber} — 用業務編號查
     */
    @Operation(summary = "查單筆案件（by 業務案件號）",
               description = "案件號格式：`UW-YYYYMMDD-NNNN`，例如 `UW-20260507-0001`")
    @ApiResponse(responseCode = "200", description = "查詢成功")
    @ApiResponse(responseCode = "404", description = "案件不存在",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/by-number/{caseNumber}")
    public UnderwritingCaseResponse getByCaseNumber(
            @Parameter(description = "業務案件號，例如 UW-20260507-0001")
            @PathVariable String caseNumber) {
        return service.getByCaseNumber(caseNumber);
    }

    /**
     * GET /api/underwriting/cases?status=SUBMITTED&page=0&size=20
     */
    @Operation(summary = "查清單（分頁，可依狀態過濾）")
    @GetMapping
    public Page<UnderwritingCaseResponse> list(
            @Parameter(description = "案件狀態過濾（選填）")
            @RequestParam(required = false) UnderwritingStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return service.list(status, pageable);
    }

    // ════════════════════════════════════════════════════════════════
    // M3: 狀態機 transition endpoints
    // RESTful 設計：「動詞型」子資源 (action sub-resource)
    // ════════════════════════════════════════════════════════════════

    @Operation(
        summary = "領件：核保員認領待審案件",
        description = "狀態：`SUBMITTED` → `UNDER_REVIEW`。同一案件只能被一位核保員領件。"
    )
    @ApiResponse(responseCode = "200", description = "領件成功")
    @ApiResponse(responseCode = "409", description = "狀態不允許此動作 (非 SUBMITTED)",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{id}/claim")
    public UnderwritingCaseResponse claim(
            @PathVariable UUID id,
            @Valid @RequestBody ClaimRequest request) {
        return service.claim(id, request);
    }

    @Operation(
        summary = "要求補件：核保員通知業務員補充資料",
        description = "狀態：`UNDER_REVIEW` → `PENDING_INFO`。`comment` 欄位必填（說明補件原因）。"
    )
    @ApiResponse(responseCode = "200", description = "要求補件成功")
    @ApiResponse(responseCode = "409", description = "狀態不允許此動作",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{id}/request-info")
    public UnderwritingCaseResponse requestInfo(
            @PathVariable UUID id,
            @Valid @RequestBody RequestInfoRequest request) {
        return service.requestInfo(id, request);
    }

    @Operation(
        summary = "補件重送：業務員提交補充文件後重送審查",
        description = "狀態：`PENDING_INFO` → `UNDER_REVIEW`。"
    )
    @PostMapping("/{id}/resubmit")
    public UnderwritingCaseResponse resubmit(
            @PathVariable UUID id,
            @Valid @RequestBody ResubmitRequest request) {
        return service.resubmit(id, request);
    }

    @Operation(
        summary = "核准：核保員審查通過",
        description = "狀態：`UNDER_REVIEW` → `APPROVED`（終態）。"
    )
    @ApiResponse(responseCode = "200", description = "核准成功")
    @ApiResponse(responseCode = "409", description = "狀態不允許此動作",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{id}/approve")
    public UnderwritingCaseResponse approve(
            @PathVariable UUID id,
            @Valid @RequestBody ApproveRequest request) {
        return service.approve(id, request);
    }

    @Operation(
        summary = "退件：核保員審查不通過",
        description = "狀態：`UNDER_REVIEW` → `REJECTED`（終態）。`comment` 欄位必填（說明退件原因）。"
    )
    @PostMapping("/{id}/reject")
    public UnderwritingCaseResponse reject(
            @PathVariable UUID id,
            @Valid @RequestBody RejectRequest request) {
        return service.reject(id, request);
    }

    @Operation(
        summary = "撤件：業務員主動撤回",
        description = "狀態：`SUBMITTED` 或 `PENDING_INFO` → `WITHDRAWN`（終態）。"
    )
    @PostMapping("/{id}/withdraw")
    public UnderwritingCaseResponse withdraw(
            @PathVariable UUID id,
            @Valid @RequestBody WithdrawRequest request) {
        return service.withdraw(id, request);
    }

    @Operation(
        summary = "查案件歷史軌跡",
        description = "列出此案件的所有狀態變更事件，依時間升冪排序。稽核員與客服常用。"
    )
    @GetMapping("/{id}/events")
    public List<CaseEventResponse> listEvents(
            @Parameter(description = "案件 UUID") @PathVariable UUID id) {
        return service.listEvents(id);
    }
}
