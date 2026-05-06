package com.sean.bancassurance.underwriting.api;

import com.sean.bancassurance.underwriting.api.dto.CreateUnderwritingCaseRequest;
import com.sean.bancassurance.underwriting.api.dto.UnderwritingCaseResponse;
import com.sean.bancassurance.underwriting.domain.UnderwritingStatus;
import com.sean.bancassurance.underwriting.service.UnderwritingCaseService;
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
import java.util.UUID;

/**
 * 核保案件 REST Controller。
 *
 *  @RestController
 *      = @Controller + @ResponseBody → 回傳值直接序列化為 JSON。
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
@RestController
@RequestMapping("/api/underwriting/cases")
@RequiredArgsConstructor
public class UnderwritingCaseController {

    private final UnderwritingCaseService service;

    /**
     * POST /api/underwriting/cases — 送件
     */
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
    @GetMapping("/{id}")
    public UnderwritingCaseResponse getById(@PathVariable UUID id) {
        return service.getById(id);
    }

    /**
     * GET /api/underwriting/cases/by-number/{caseNumber} — 用業務編號查
     *
     * 設計選擇：另開子路徑而不是讓 {id} 同時接受 UUID 和編號，避免「歧義路徑」。
     */
    @GetMapping("/by-number/{caseNumber}")
    public UnderwritingCaseResponse getByCaseNumber(@PathVariable String caseNumber) {
        return service.getByCaseNumber(caseNumber);
    }

    /**
     * GET /api/underwriting/cases?status=SUBMITTED&page=0&size=20
     */
    @GetMapping
    public Page<UnderwritingCaseResponse> list(
            @RequestParam(required = false) UnderwritingStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return service.list(status, pageable);
    }
}
