package com.sean.bancassurance.auth.util;

import com.sean.bancassurance.auth.domain.AppUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

/**
 * SecurityContext 存取的薄包裝。
 *
 *  ── 為什麼需要這個 util？──────────────────────────────────────────────
 *
 *  controller 經常要拿「目前登入者」帶給 service 當 audit actor：
 *
 *      SecurityContextHolder.getContext().getAuthentication().getName()
 *
 *  上面這串長到每次都得寫一次又容易拼錯，集中包一個 SecurityUtils.currentUsername()
 *  既可讀又利於統一處理「沒登入」邊界情況。
 *
 *  ── 為什麼回 String 而不是 Optional<String>？─────────────────────────────
 *
 *  controller 的場景：在進 @PreAuthorize 之後幾乎一定有登入 user，
 *  Optional.get() 寫起來吵；我們在沒登入或匿名時回固定字串 "anonymous"，
 *  caller code 簡潔。真正 nullable 的場景 (例如 audit 在 startup 階段) 用
 *  currentUsernameOpt() 回 Optional。
 *
 *  ── 為什麼 final + private constructor？─────────────────────────────
 *
 *  純 utility class — 全 static method，禁止實體化。Java 沒有「真正的」
 *  utility class 語法（不像 C# 的 static class），慣例就是「final + private ctor」。
 */
public final class SecurityUtils {

    /** Spring Security 對 anonymous 認證的預設 principal 名稱 */
    private static final String ANONYMOUS = "anonymousUser";

    /** 沒有 SecurityContext 時用的 fallback (例如背景 thread / 啟動時) */
    private static final String SYSTEM = "system";

    private SecurityUtils() {
        // utility holder
    }

    /**
     * 目前登入者的 username。沒有登入 (anonymous / null) 回 "anonymous"。
     * controller 直接拿來當 audit actor 字串使用。
     */
    public static String currentUsername() {
        return currentUsernameOpt().orElse(ANONYMOUS);
    }

    /**
     * 同上，但 caller 想自己決定 fallback 時用 Optional。
     *
     * Optional.empty() 的兩種情況：
     *   - SecurityContext 內 authentication 是 null
     *   - authentication.getName() 是 "anonymousUser" (尚未登入但有 anonymous filter 灌空 context)
     */
    public static Optional<String> currentUsernameOpt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Optional.empty();
        }
        String name = auth.getName();
        if (name == null || ANONYMOUS.equals(name)) {
            return Optional.empty();
        }
        return Optional.of(name);
    }

    /**
     * Audit 場景專用：登入 → username；沒登入 → "system"。
     * JpaAuditingConfig 的 AuditorAware 用這個。
     *
     *  為什麼跟 currentUsername() 分開？
     *    - controller 抓 actor 是「業務操作」場景，沒登入應該被擋 (anonymous 是 bug)
     *    - audit 是「資料層」場景，跟 SecurityContext 解耦，沒登入時用 "system" 才合理
     *      (Flyway 跑 migration / 啟動初始化等不該污染 audit log)
     */
    public static String currentUsernameForAudit() {
        return currentUsernameOpt().orElse(SYSTEM);
    }

    /**
     * 拿目前登入者的 AppUserPrincipal。
     * 沒登入或 principal 不是 AppUserPrincipal (例如 anonymous) 回 Optional.empty()。
     *
     * 用例：controller 要顯示 displayName / 取 user UUID。
     */
    public static Optional<AppUserPrincipal> currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Optional.empty();
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof AppUserPrincipal p) {
            return Optional.of(p);
        }
        return Optional.empty();
    }

    /**
     * 拿目前登入者的 user UUID。沒登入回 Optional.empty()。
     */
    public static Optional<UUID> currentUserId() {
        return currentPrincipal().map(AppUserPrincipal::userId);
    }
}
