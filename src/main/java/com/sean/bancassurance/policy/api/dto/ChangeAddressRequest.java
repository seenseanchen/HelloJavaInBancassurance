package com.sean.bancassurance.policy.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 變更地址請求。
 *
 * expectedVersion：樂觀鎖前置檢查。
 *   先 GET /api/policies/{id} 取得 ETag: "N"，
 *   PATCH 時帶 If-Match: "N"（header）或 expectedVersion: N（body）。
 *   Service 優先讀 header，沒有才讀 body。
 *   版本不符 → 412 Precondition Failed（表示已被他人修改，請重新 GET）。
 */
@Schema(description = "變更通訊地址請求")
public record ChangeAddressRequest(

        @Schema(description = "樂觀鎖版本號（從 GET 單筆 ETag 或 response.version 取得）",
                example = "0",
                minimum = "0")
        @NotNull(message = "expectedVersion is required for optimistic locking")
        @Min(value = 0, message = "expectedVersion must be >= 0")
        Long expectedVersion,

        @Schema(description = "新通訊地址（完整地址含縣市、鄉鎮、路段、門號）",
                example = "台北市信義區松仁路100號5樓")
        @NotBlank(message = "newAddress is required")
        @Size(max = 255, message = "newAddress must be <= 255 characters")
        String newAddress,

        @Schema(description = "變更原因（選填，建議填寫以利稽核）",
                example = "客戶搬家，更新通訊地址",
                nullable = true)
        @Size(max = 500, message = "reason must be <= 500 characters")
        String reason

) {}
