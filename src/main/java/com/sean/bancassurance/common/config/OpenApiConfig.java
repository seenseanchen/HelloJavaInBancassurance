package com.sean.bancassurance.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI (Swagger) 全域設定。
 *
 * ── 為什麼要寫這個 Config？────────────────────────────────────────────
 *   springdoc 的自動掃描可以「直接啟動就跑」，但沒有 API 標題、版本、認證說明。
 *   OpenApiConfig 填入這些「API 合約的 meta-data」，讓 Swagger UI 成為
 *   「可以分享給 QA / 前端 / 合作行的活文件」。
 *
 * ── SecurityScheme 說明 ───────────────────────────────────────────────
 *   目前 (M1–M7) 沒有實作 Spring Security，此處先「宣告 Basic Auth scheme」，
 *   告訴閱讀文件的人未來會需要帶 Authorization header，
 *   同時示範 OpenAPI security 的設定方式。
 *   M9 接上 JWT 後，這裡改成 BearerAuth scheme 即可。
 *
 * ── servers 的用途 ────────────────────────────────────────────────────
 *   可以宣告多個環境 (dev / staging / prod)。
 *   Swagger UI 右上角有 server selector，可以切換要打哪個環境。
 *   這在銀行業很有用：QA 打 staging、前端打 dev，同一份文件共用。
 *
 * (面試題 / 中級)：「API 版本化有哪些方式？各自優缺點？」
 *   ① URL 路徑版本：/api/v1/policies、/api/v2/policies
 *      優：最直覺，cache-friendly，load balancer 容易路由
 *      缺：URL 「汙染」，換版要改所有 link
 *
 *   ② Header 版本：Accept: application/vnd.bancassurance.v1+json
 *      優：URL 乾淨，符合 REST 純粹主義 (「版本是回應格式，不是資源路徑」)
 *      缺：瀏覽器 / Postman 要手動帶 header，難以直接貼連結
 *
 *   ③ Query String：/api/policies?version=1
 *      少用，cache 不友善，不算最佳實踐
 *
 *   業界現實：銀行業大多用 URL 版本，因為 LB 規則、log 分析最好做。
 *   本專案用 /api/ 前綴 (沒有版號)，需要版本化時加 /v1/、/v2/。
 *
 * (面試題 / 資深)：「你怎麼在不 breaking 既有 client 的情況下升 API 版本？」
 *   - Additive changes (加欄位、加 endpoint) 可以不升版，向後相容
 *   - Breaking changes (刪欄位、改型別、改 URL) 才需要升版
 *   - 新版 endpoint 與舊版並存跑一段 deprecation period (通常 6–12 個月)，讓 client 遷移
 *   - 用 @Deprecated annotation + OpenAPI `deprecated: true` 標記舊版
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "basicAuth";

    @Bean
    public OpenAPI bancassuranceOpenAPI() {
        return new OpenAPI()
                .info(apiInfo())
                .servers(apiServers())
                // 宣告 Basic Auth security scheme (M9 後改 BearerAuth)
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, basicAuthScheme()))
                // 全域 security requirement：所有 endpoint 預設都要帶認證
                // (目前 Spring Security 尚未啟用，先宣告結構讓文件完整)
                .addSecurityItem(new SecurityRequirement()
                        .addList(SECURITY_SCHEME_NAME));
    }

    private Info apiInfo() {
        return new Info()
                .title("銀保通路後端 API (Bancassurance Backend)")
                .description("""
                        ## 系統概述
                        此 API 服務負責人壽保險銀行通路 (Bancassurance) 的核心後端業務，包含：

                        - **核保案件管理** (`/api/underwriting/cases`)：
                          送件→審查→核准/退件，完整狀態機流程。

                        - **保單查詢** (`/api/policies`)：
                          多條件動態查詢、分頁、受益人明細。

                        - **保單變更** (`/api/policies/{id}/*`)：
                          地址、受益人、繳費方式變更，含樂觀鎖與冪等性保護。

                        ## 統一回應格式
                        所有成功回應均包裝於 `ApiResponse<T>` envelope：
                        ```json
                        {
                          "code": "SUCCESS",
                          "message": "OK",
                          "data": { ... },
                          "traceId": "550e8400-..."
                        }
                        ```
                        錯誤回應為 `ApiError` 扁平格式（含 `traceId` 供 log 追蹤）。

                        ## 請求追蹤
                        每個 request 的 response header 含 `X-Trace-Id`，
                        出現問題時請帶此 ID 聯絡後端工程師。
                        """)
                .version("0.7.0")
                .contact(new Contact()
                        .name("Sean (livebreeze@gmail.com)")
                        .email("livebreeze@gmail.com"))
                .license(new License()
                        .name("Internal — Not for public distribution"));
    }

    private List<Server> apiServers() {
        Server local = new Server()
                .url("http://localhost:8080")
                .description("本機開發環境 (Local Dev)");
        // 未來可加：
        // Server staging = new Server().url("https://api-stg.bancassurance.internal").description("Staging");
        // Server prod    = new Server().url("https://api.bancassurance.internal").description("Production");
        return List.of(local);
    }

    /**
     * HTTP Basic Auth scheme (M7 先宣告結構；M9 接上 JWT 後改成 BearerAuth)。
     *
     *  BearerAuth 寫法供參考：
     *    new SecurityScheme()
     *        .name("bearerAuth")
     *        .type(SecurityScheme.Type.HTTP)
     *        .scheme("bearer")
     *        .bearerFormat("JWT")
     */
    private SecurityScheme basicAuthScheme() {
        return new SecurityScheme()
                .name(SECURITY_SCHEME_NAME)
                .type(SecurityScheme.Type.HTTP)
                .scheme("basic")
                .description("HTTP Basic Auth (M9 後替換為 JWT Bearer)");
    }
}
