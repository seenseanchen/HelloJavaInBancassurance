package com.sean.bancassurance.underwriting.api.dto;

import com.sean.bancassurance.underwriting.domain.Channel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 送件 (Submit) 的 Request DTO。
 *
 *  ── M9.5 變更 (breaking) ────────────────────────────────────────────────
 *  原本有 `String submittedBy` 欄位 (從 client 帶業務員 ID)。
 *  M9.5 接 Spring Security 後，送件者直接從 SecurityContext 取 (登入的 CSR 自己)。
 *  詳細理由見 TransitionRequests 註解的「為什麼不能讓 client 在 body 帶 user_id」。
 *
 * 為什麼用 Java 21 record？
 *  - 自動產生 constructor / accessor / equals / hashCode / toString
 *  - 不可變 (immutable)：DTO 跨層傳遞時不會被半路改值
 *  - 比 Lombok 更原生，是 Java 17+ 的標配
 *
 * Bean Validation：
 *  @NotBlank  : 字串非 null 且 trim 後非空
 *  @NotNull   : 物件不為 null (用於非字串)
 *  @Size      : 長度限制
 *  @DecimalMin: BigDecimal / 數字下限
 *  ★ 配合 Controller 的 @Valid 才會觸發；驗證失敗由 @RestControllerAdvice 統一翻譯
 */
@Schema(description = "投保送件請求")
public record CreateUnderwritingCaseRequest(

        @Schema(description = "要保人姓名（客戶全名）", example = "陳小明")
        @NotBlank
        @Size(max = 64)
        String applicantName,

        @Schema(description = "要保人身分證號（台灣格式：1 英文字母 + 9 數字）", example = "A123456789")
        @NotBlank
        @Size(max = 32)
        String applicantIdNumber,

        @Schema(description = "商品代碼（由商品上架系統維護）", example = "LIFE-001")
        @NotBlank
        @Size(max = 32)
        String productCode,

        @Schema(description = "投保金額（新台幣，最小 0.01）", example = "1000000.00")
        @NotNull
        @DecimalMin(value = "0.01", message = "保額必須大於 0")
        BigDecimal coverageAmount,

        @Schema(description = "年繳保費（新台幣）", example = "25000.00")
        @NotNull
        @DecimalMin(value = "0.01", message = "保費必須大於 0")
        BigDecimal premium,

        @Schema(description = "銷售通路",
                example = "BANCASSURANCE",
                allowableValues = {"BANCASSURANCE", "DIRECT", "BROKER", "ONLINE"})
        @NotNull
        Channel channel

) {}
