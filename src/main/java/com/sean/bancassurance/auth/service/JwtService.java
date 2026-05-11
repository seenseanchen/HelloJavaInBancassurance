package com.sean.bancassurance.auth.service;

import com.sean.bancassurance.auth.domain.AppUserPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * JWT 簽發 + 驗證 — 用 jjwt 0.12.x。
 *
 *  ── JWT 三段式結構 (面試★必考) ──────────────────────────────────────────
 *
 *      header.payload.signature
 *
 *      header  (Base64Url 編碼的 JSON)：
 *        { "alg": "HS256", "typ": "JWT" }
 *
 *      payload (Base64Url 編碼的 JSON)，標準 claims：
 *        iss (issuer)     簽發者
 *        sub (subject)    主體，通常是 username 或 user id
 *        aud (audience)   受眾
 *        exp (expiration) 過期時間 (epoch seconds)
 *        nbf (not before) 生效時間
 *        iat (issued at)  簽發時間
 *        jti (JWT ID)     唯一識別 (給黑名單用)
 *
 *      signature (HMAC-SHA256(secret, base64url(header) + "." + base64url(payload)))
 *        用 secret 對前兩段算 HMAC，防止竄改。改任一字元 signature 就對不上。
 *
 *  ── HS256 vs RS256 (面試★必考) ──────────────────────────────────────────
 *
 *      HS256 (HMAC-SHA256，對稱密鑰)
 *        - 簽發 + 驗證 用「同一把」secret
 *        - 速度快、key 短
 *        - 任何持有 secret 的人都能簽、驗 → 適合「自簽自驗」(單一服務 / 微服務群共享 secret)
 *        - 不適合對外發 token：第三方驗 = 必須拿到 secret = 也能簽假 token
 *
 *      RS256 (RSA-SHA256，非對稱密鑰)
 *        - 簽發用 private key，驗證用 public key
 *        - private 留在 auth server、public 公開 (JWKS endpoint)
 *        - 速度比 HS256 慢 ~10x；token 也比較長 (RSA signature 256 bytes)
 *        - 適合 OAuth2 / OIDC：auth server 簽，N 個 resource server 各自驗
 *
 *      銀行業現況：
 *        - 自家系統內部認證 → HS256 + secret 從 Vault
 *        - 對外 OpenAPI / 跨 LOB → RS256 + KMS 託管 private key + 公開 JWKS
 *
 *      本專案：HS256 (學習友善、一支 secret 就跑得起來)。
 *      M9_SMOKE_TEST.md 會補一段「如何切到 RS256」當面試延伸題。
 *
 *  ── 為什麼不用 Spring Security 內建的 nimbus-jose-jwt？──────────────────
 *
 *      nimbus 是「OAuth2 Resource Server」的標配 (spring-boot-starter-oauth2-resource-server
 *      就是包它)。功能多 (JWE / JWK rotation / JWKS fetch...)，但抽象層厚。
 *      我們做的是「自簽自驗」，不需要 OAuth2 那一整套，jjwt API 直觀好教。
 */
@Slf4j
@Service
public class JwtService {

    private static final String CLAIM_USER_ID = "uid";
    private static final String CLAIM_ROLES   = "roles";

    private final SecretKey secretKey;
    private final long ttlMs;
    private final String issuer;

    /**
     * 從 application.yml 注入 JWT 設定。
     *
     *  ⚠️ 這裡用 constructor injection (RecommendedArgsConstructor 不適用 @Value，
     *     所以手寫 constructor)：
     *      - 比 field injection 容易單元測試 (可以 new JwtService("secret", 3600000, "issuer"))
     *      - final field 表達「這些值是不可變的」
     *
     *  Keys.hmacShaKeyFor(byte[]) 行為：
     *      - 把 byte[] 包成 HMAC SecretKey
     *      - JJWT 0.12+ 強制要求至少 32 bytes (256 bits) for HS256 — 不夠長會直接拋
     *        WeakKeyException，這是好事，避免 dev 用太短 secret 進 prod
     */
    public JwtService(
            @Value("${app.security.jwt.secret}") String secret,
            @Value("${app.security.jwt.ttl-ms}") long ttlMs,
            @Value("${app.security.jwt.issuer}") String issuer) {

        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMs = ttlMs;
        this.issuer = issuer;

        log.info("JwtService initialized: issuer={}, ttlMs={} ({} min)",
                issuer, ttlMs, ttlMs / 60000);
    }

    /**
     * 簽發 access token。
     *
     *  payload 結構：
     *    {
     *      "iss": "bancassurance-backend",
     *      "sub": "underwriter01",          ← username
     *      "iat": 1731234567,
     *      "exp": 1731238167,
     *      "uid": "22222222-...",           ← 我們的 user UUID (custom claim)
     *      "roles": ["ROLE_UNDERWRITER"]    ← Spring Security authority 字串 list (custom claim)
     *    }
     *
     *  為什麼把 roles 放進 token？
     *    每個 request 都要做授權判斷。如果 token 不帶 roles，filter 就要每次回 DB
     *    撈使用者角色 → 增加延遲、增加 DB 壓力。
     *    把 roles 放 token 內 = stateless，filter 不必查 DB。
     *
     *  缺點：
     *    role 變了 (例如 underwriter01 被升為 admin) → 舊 token 還認得舊 role，
     *    要等 token 過期才會重新簽。解法：縮短 ttl + 配 refresh token，或維護黑名單。
     *
     *  (面試題 / 中級)：「JWT 為什麼難做登出？」
     *    - JWT 是 stateless：server 沒存任何 session，無從「作廢」一張已簽出的 token
     *    - 解法：黑名單 (Redis 存 jti，每次驗 token 比對黑名單) — 但這就有狀態了，
     *      失去「stateless」的優勢
     *    - 業界折衷：access token TTL 短 (15min–1h) + refresh token TTL 長 (7-30 days，
     *      存 DB 可作廢)。登出時把 refresh token 作廢，access token 過期就自然失效。
     */
    public String issueToken(AppUserPrincipal principal) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(ttlMs);

        List<String> roleStrings = principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)   // "ROLE_UNDERWRITER"
                .toList();

        return Jwts.builder()
                .issuer(issuer)
                .subject(principal.username())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim(CLAIM_USER_ID, principal.userId().toString())
                .claim(CLAIM_ROLES, roleStrings)
                // signWith(SecretKey) 會自動依 key 長度推導出 HS256 / HS384 / HS512
                // 我們 secret 32 bytes → HS256
                .signWith(secretKey)
                .compact();
    }

    /**
     * 驗證 token + 取出 claims。
     *
     *  jjwt 0.12.x 流程：
     *    1. parser builder：注入 secret + issuer 預期值
     *    2. parseSignedClaims(token)
     *       - signature 不對 → SignatureException
     *       - exp 過期        → ExpiredJwtException
     *       - iss 不符        → IncorrectClaimException
     *       - 格式壞掉        → MalformedJwtException
     *       全部都是 JwtException 的子類
     *
     *  本 method 把所有 JwtException 統一翻成 InvalidJwtException — caller 不必認 5 種子類。
     *
     *  (面試題 / 中級)：「verifyWith vs setSigningKey 差在哪？」
     *    - JJWT 0.12 之前：parser().setSigningKey(key) — deprecated
     *    - JJWT 0.12 之後：parser().verifyWith(key)    — 新 API，明確語意
     *      還有對應的 .decryptWith(key) 給 JWE 用。新 API 把「驗證 vs 解密」分得清楚。
     */
    public Claims parseAndValidate(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException e) {
            // 不要 log token 本身 (即使 invalid，仍可能含敏感資訊)
            log.debug("JWT validation failed: {} ({})", e.getClass().getSimpleName(), e.getMessage());
            throw new InvalidJwtException("Invalid or expired JWT token", e);
        }
    }

    /**
     * 從 claims 取 user id。Token 簽發時保證了這個 claim 存在。
     */
    public UUID extractUserId(Claims claims) {
        return UUID.fromString(claims.get(CLAIM_USER_ID, String.class));
    }

    /**
     * 從 claims 取 roles。
     *
     * 注意：JWT JSON 反序列化回來時，list 元素型別是 String — 不會自動轉成
     * GrantedAuthority。M9.4 的 JwtAuthenticationFilter 會把 String 包成
     * SimpleGrantedAuthority 再放進 SecurityContext。
     */
    @SuppressWarnings("unchecked")
    public List<String> extractRoles(Claims claims) {
        Object raw = claims.get(CLAIM_ROLES);
        if (raw instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    /**
     * Login 回應要帶「token 還有多久過期」(秒)，供 client 排程 refresh / re-login。
     */
    public long getTtlSeconds() {
        return ttlMs / 1000;
    }

    /**
     * Token 驗證失敗的統一例外。
     * 放在 inner class 是因為「Jwt 相關例外」跟 JwtService 緊密綁定，沒人會單獨用。
     */
    public static class InvalidJwtException extends RuntimeException {
        public InvalidJwtException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
