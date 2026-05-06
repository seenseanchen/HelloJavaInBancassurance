package com.sean.bancassurance.common.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 系統健康檢查 endpoint。
 *
 * 角色：由 Load Balancer / K8s liveness probe / 維運告警系統定期呼叫，
 *      確認服務存活。回傳 200 = 服務正常；非 200 = 該下線重啟。
 *
 * 為什麼自己寫一支，而不是直接用 /actuator/health？
 *  1. 業務團隊習慣把 health 放在 /api 前綴下，跟業務 API 一致
 *  2. 可自訂回傳欄位（版號、build time、trace id），事後排查方便
 *  3. /actuator 多半只開內網，外部 LB 打不到
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    /**
     * GET /api/health
     *
     * @RestController = @Controller + @ResponseBody
     *   → 方法回傳的物件會直接被 Jackson 序列化為 JSON 寫進 HTTP body
     *   → 不會去找 view template (Thymeleaf 之類)
     *
     * @GetMapping("/health") 是 @RequestMapping(method = GET, path = "/health") 的縮寫
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        // Map.of(...) 是 Java 9+ 提供的不可變 Map 工廠方法，
        // 比 new HashMap<>() + put 簡潔，且保證執行緒安全。
        return Map.of(
                "status", "UP",
                "service", "bancassurance",
                "timestamp", Instant.now()  // ISO-8601 UTC，金融業時間欄位一律用 Instant
        );
    }
}
