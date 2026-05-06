package com.sean.bancassurance.underwriting.api.dto;

import com.sean.bancassurance.underwriting.domain.Channel;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 送件 (Submit) 的 Request DTO。
 *
 * 為什麼用 Java 21 record？
 *  - 自動產生 constructor / accessor / equals / hashCode / toString
 *  - 不可變 (immutable)：DTO 跨層傳遞時不會被半路改值
 *  - 比 Lombok 更原生，是 Java 17+ 的標配
 *
 * 為什麼把 Entity 與 DTO 嚴格分離？(面試題 / 中級)
 *  - Entity 帶有 JPA 狀態 (managed/detached)，序列化容易踩到 LazyInitializationException
 *  - Entity 改欄位 != API 改欄位 — 用 DTO 隔開，欄位演進獨立
 *  - 避免不小心把 password / id_number / version / 內部欄位 leak 到 API
 *  - 可在 DTO 上加 Bean Validation 規則，Entity 保持乾淨
 *
 * Bean Validation (Jakarta Validation)：
 *  @NotBlank  : 字串非 null 且 trim 後非空
 *  @NotNull   : 物件不為 null (用於非字串)
 *  @Size      : 長度限制
 *  @DecimalMin: BigDecimal / 數字下限
 *  ★ 配合 Controller 的 @Valid 才會觸發；驗證失敗會由 @RestControllerAdvice 統一翻譯
 */
public record CreateUnderwritingCaseRequest(

        @NotBlank
        @Size(max = 64)
        String applicantName,

        @NotBlank
        @Size(max = 32)
        String applicantIdNumber,

        @NotBlank
        @Size(max = 32)
        String productCode,

        @NotNull
        @DecimalMin(value = "0.01", message = "保額必須大於 0")
        BigDecimal coverageAmount,

        @NotNull
        @DecimalMin(value = "0.01", message = "保費必須大於 0")
        BigDecimal premium,

        @NotNull
        Channel channel,

        @NotBlank
        @Size(max = 64)
        String submittedBy

) {}
