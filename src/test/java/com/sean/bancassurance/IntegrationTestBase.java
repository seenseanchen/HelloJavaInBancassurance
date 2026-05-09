package com.sean.bancassurance;

import com.sean.bancassurance.common.web.TraceIdFilter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 整合測試的共同基底類別。
 *
 *  做的事：
 *   1. 起一個 PostgreSQL 16 alpine container (整個 JVM 共享一份)
 *   2. 讓 Spring Boot 自動把 container 的 jdbc:url / username / password 注入
 *      → 子類測試的 DataSource 自動指向這個 container，不用手寫 properties
 *   3. 套用 application-test.yml (demo sleep=0、log 安靜)
 *   4. 啟用 MockMvc 自動配置 (Web 層測試會用)
 *
 *  ── 關鍵 annotation 拆解 ────────────────────────────────────────────
 *
 *  @SpringBootTest
 *      啟動完整 Spring application context — Bean、AOP proxy、@Transactional 全到位。
 *      跟 @DataJpaTest / @WebMvcTest (slice test) 對比：slice 只起部分 context (快但
 *      不真實)；@SpringBootTest 起全部 (慢但接近 production)。
 *      M5 樂觀鎖併發測試「必須」用 @SpringBootTest — 我們要驗的就是「@Transactional
 *      + JPA + 真 PG」三者合作的行為，缺一不可。
 *
 *  @Testcontainers
 *      JUnit Jupiter 擴展，識別 @Container 標註的欄位、管理 container 生命週期。
 *      ★ 我們刻意「不」用 @Container — 因為 @Container 在 static 欄位時 = 每個 class
 *        起一次 container。改成手動 .start() + 不寫 @Container = 整個 JVM 起一次，
 *        所有測試 class 共用，總時間從「class 數 × 5 秒」變成「5 秒」。
 *
 *  @ServiceConnection
 *      Spring Boot 3.1+ 提供的「自動配 DataSource」機制：
 *      偵測欄位是 PostgreSQLContainer<?> → 把 container.getJdbcUrl() 等資訊
 *      塞進 Environment 裡，等同於以下手寫：
 *
 *        @DynamicPropertySource
 *        static void props(DynamicPropertyRegistry r) {
 *            r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
 *            r.add("spring.datasource.username", POSTGRES::getUsername);
 *            r.add("spring.datasource.password", POSTGRES::getPassword);
 *        }
 *
 *      → 一行解決，且支援 Redis、Kafka、Mongo... 多種 service。
 *
 *  @ActiveProfiles("test")
 *      指定載入 application-test.yml — 把 demo sleep 關掉、log 降到 WARN。
 *
 *  MockMvcConfig (inner @TestConfiguration)
 *      Spring Boot 4.0.x 把 @AutoConfigureMockMvc 所在的
 *      org.springframework.boot.test.autoconfigure.web.servlet 套件重構，
 *      直接依賴該 annotation 不再可靠。
 *
 *      改用 @TestConfiguration + MockMvcBuilders.webAppContextSetup(wac) 手動註冊
 *      MockMvc Bean — 完全版本無關，效果與 @AutoConfigureMockMvc 相同：
 *        (1) 完整 DispatcherServlet 流程（Filter、Interceptor、ExceptionHandler 全跑）
 *        (2) 不開真實 TCP socket（比 RANDOM_PORT 快）
 *        (3) 子類照樣用 @Autowired MockMvc mockMvc 注入
 *
 *  (面試題 / 中級)：「@AutoConfigureMockMvc 和手動 MockMvcBuilders.webAppContextSetup 有什麼差？」
 *    大致相同：都走完整 DispatcherServlet 鏈。差別在：
 *      - @AutoConfigureMockMvc 預設套用 Spring Security TestSecurityContextHolder，
 *        手動 build 若沒加 .apply(springSecurity()) 就不會有那層。
 *        本專案 M9 前未啟用 Security，兩者行為一致。
 *
 *  ── 為什麼用 abstract class 而非介面？ ─────────────────────────────
 *  Spring 的 test annotation 都是「class 級」(掃 super class) — 介面上的 annotation
 *  不會被 Spring TestContext 認得。所以基底類別必須是 class。
 *
 *  ── ContextCache：節省第二支以後的測試啟動時間 ─────────────────────
 *  Spring TestContext 會 cache application context，只要兩個 test class 的
 *  「context configuration」一樣 (annotations、profiles、properties、bean overrides)，
 *  就重用同一個 context。我們所有 IT 都繼承同一個 base + 同樣 profile，所以理論上
 *  context 只 build 一次。但若某個子類加了 @TestPropertySource(...) 就會建立新的 context。
 *  → 把「需要不同 properties」的測試獨立 class，反而能保留 cache 命中。
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Import(IntegrationTestBase.MockMvcConfig.class)
public abstract class IntegrationTestBase {

    /**
     * MockMvc Bean 手動註冊。
     *
     * @TestConfiguration — 只在測試 context 中生效，不污染 main context。
     * webAppContextSetup(wac) — 把整個 WebApplicationContext (含 Controller、Filter、
     * ExceptionHandler) 塞進 MockMvc，行為最接近真實 runtime。
     *
     * ── 為什麼要明示注入 TraceIdFilter？(Spring Boot 4.0 相容問題) ────────
     *
     * 根本問題：
     *   webAppContextSetup(wac) 透過 wac.getBeansOfType(Filter.class) 自動偵測 Filter 並加入
     *   MockMvc 的 filter chain。但在 Spring Boot 4.0 (Spring Framework 7)，
     *   @TestConfiguration bean 的初始化時序與 main context bean 有時存在競爭，
     *   導致 getBeansOfType() 在 build() 呼叫當下還沒拿到 TraceIdFilter，
     *   filter 就不會被加入。
     *
     *   直接證據：surefire XML 中的 system-out 顯示 [traceId=no-trace]，
     *   代表 MDC 裡完全沒有 traceId — TraceIdFilter 根本沒跑。
     *
     * 解法：
     *   1. 把 TraceIdFilter 宣告為 mockMvc() 方法的參數 → Spring 保證它在 mockMvc Bean
     *      初始化「之前」完成注入（dependency 先初始化是 IoC 的核心規則）。
     *   2. 呼叫 .addFilters(traceIdFilter) 明示加入 → 不依賴自動偵測是否成功。
     *
     * 雙重加入問題：
     *   若 webAppContextSetup 的自動偵測「也」找到了 TraceIdFilter，
     *   filter chain 就會有兩份。但 OncePerRequestFilter 用 request attribute 標記
     *   「已執行」，第二次呼叫 doFilter() 時直接跳過，不會雙重執行。
     *   所以明示加入 + 自動偵測並存是安全的。
     *
     * (面試題 / 資深)：「Spring Boot 4.0 的 MockMvc filter 自動偵測為什麼有時失效？」
     *   答：@SpringBootTest 啟動的是完整 application context，bean 初始化有依賴順序。
     *       @TestConfiguration 的 bean (如 MockMvc) 若沒有明示依賴 Filter bean，
     *       Spring 不保證 Filter 先於 MockMvc 初始化。
     *       wac.getBeansOfType(Filter.class) 在 build() 時呼叫，若 TraceIdFilter
     *       尚未初始化就拿不到，filter 就不在 chain 裡。
     *       明示 dependency injection 是最可靠的修法。
     */
    @TestConfiguration
    static class MockMvcConfig {
        @Bean
        MockMvc mockMvc(WebApplicationContext wac, TraceIdFilter traceIdFilter) {
            return MockMvcBuilders.webAppContextSetup(wac)
                    .addFilters(traceIdFilter)   // 明示加入，不依賴自動偵測時序
                    .build();
        }
    }

    /**
     * 整個 JVM 共享一個 PostgreSQL container。
     *
     * static + 手動 start() (沒寫 @Container)：
     *   - JUnit 不會在 class 結束時呼叫 stop()
     *   - Testcontainers 內建 Ryuk reaper 守護進程會在 JVM 退出時清理孤兒 container
     *   - 所以「JVM 開一次、跑完所有測試、JVM 退出時自動清掉」
     *
     * 為什麼要 final？防誤改；static 欄位被 reassign 是經典 bug 來源。
     *
     * image tag 為什麼選 "postgres:16-alpine"？
     *   - 對齊 docker-compose.yml 本機跑的 PG 16 (主版號一致最重要)
     *   - alpine 比 debian-based 小 ~3 倍 (約 80MB)，CI 第一次拉得快
     *   - PG 16 內建 gen_random_uuid()、pg_trgm 等本系統種子資料用得到的功能
     */
    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}
