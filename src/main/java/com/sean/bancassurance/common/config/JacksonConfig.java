package com.sean.bancassurance.common.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson ObjectMapper 設定 — 明示宣告 Bean，不依賴 JacksonAutoConfiguration 的隱式條件。
 *
 * ── 為什麼要明示宣告？ ────────────────────────────────────────────────────────
 *
 * Spring Boot 的 JacksonAutoConfiguration 有一個 @ConditionalOnClass(ObjectMapper.class)
 * + @ConditionalOnMissingBean(ObjectMapper.class) 條件：
 *   - 只要 classpath 有 jackson-databind，就自動建立一個 ObjectMapper bean。
 *   - 但 starter-webmvc (Spring Boot 4.0 切開後) 不保證一定帶入 jackson-databind，
 *     所以 condition 可能不成立。
 *
 * 明示宣告的好處：
 *   1. 不受 starter 分拆影響，永遠有 bean。
 *   2. 可以精確控制序列化行為 (金融業重要！)。
 *   3. 程式碼可讀性高，同事 code review 看得懂配置意圖。
 *
 * ── 設定說明 ──────────────────────────────────────────────────────────────────
 *
 * JavaTimeModule
 *   讓 Jackson 認識 Java 8+ 的時間類型 (Instant, LocalDateTime, ZonedDateTime...)。
 *   沒有這個模組，序列化 Instant 會變成一個大數字 (epoch millis)，很難看。
 *
 * WRITE_DATES_AS_TIMESTAMPS = false
 *   關掉「把時間寫成數字」，改成 ISO-8601 字串 (e.g. "2026-05-07T06:20:00Z")。
 *   金融業 API 回應一律用 ISO-8601，方便前端與跨系統解析。
 *
 * FAIL_ON_UNKNOWN_PROPERTIES = false
 *   收到未知欄位時不拋例外，而是靜默忽略。
 *   API 版本向後兼容的關鍵：舊版 client 呼叫新版 API，多出的欄位不會爆炸。
 *
 * NON_NULL inclusion
 *   回應 JSON 不輸出 null 欄位，減少 payload 大小，前端也更好判斷「欄位有沒有值」。
 *
 * ── 面試題 (初級) ─────────────────────────────────────────────────────────────
 * Q: ObjectMapper 是 thread-safe 嗎？
 * A: 是。ObjectMapper 本身 (設定好之後) 是 thread-safe 的，可以安心宣告為 Singleton bean。
 *    但注意：ObjectReader / ObjectWriter 才是「build 後不可改」，
 *    直接對 ObjectMapper 呼叫 configure() 在多執行緒下仍要小心。
 *
 * ── 面試題 (中級) ─────────────────────────────────────────────────────────────
 * Q: Spring Boot 的 @ConditionalOnMissingBean 是什麼？
 * A: Spring Boot Auto-configuration 用的條件注解。
 *    「只有當 Spring Context 裡還沒有同型別的 Bean，才建立這個 auto-config bean」。
 *    所以我們這裡明示宣告了 ObjectMapper bean，JacksonAutoConfiguration 的預設 bean
 *    就會被跳過 (因為 @ConditionalOnMissingBean 條件不成立)。
 *    這就是 Spring Boot「約定優於配置 (Convention over Configuration)」的彈性出口。
 */
@Configuration
public class JacksonConfig {

    /**
     * 全域 ObjectMapper — 所有需要 JSON 序列化/反序列化的地方都注入這個。
     *
     * Spring Boot 的 HttpMessageConverter 也會優先用這個 bean (而非另建一個)，
     * 因此 API 回應的 JSON 格式也由此控制。
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // 1. Java 8+ 時間類型支援 (Instant, LocalDate, LocalDateTime...)
        mapper.registerModule(new JavaTimeModule());

        // 2. 時間輸出為 ISO-8601 字串而非 epoch timestamp
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // 3. 收到未知欄位靜默忽略 (向後兼容)
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        // 4. 回應不輸出 null 欄位，讓 JSON 精簡
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        return mapper;
    }
}
