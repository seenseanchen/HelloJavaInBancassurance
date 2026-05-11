package com.sean.bancassurance.auth.domain;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/**
 * 把 AppUser 包裝成 Spring Security 認得的 UserDetails。
 *
 *  ── 為什麼需要這層包裝？──────────────────────────────────────────────
 *
 *  Spring Security 整套 API 都圍繞 UserDetails 打轉：
 *    - UserDetailsService.loadUserByUsername(...) 回傳 UserDetails
 *    - AuthenticationManager 拿 UserDetails 比對 password
 *    - SecurityContextHolder.getContext().getAuthentication().getPrincipal() 通常是 UserDetails
 *    - @PreAuthorize("authentication.principal.id == #policy.holderId") 也讀 principal
 *
 *  我們的 AppUser 是 JPA Entity，不認得 UserDetails 介面。兩個選擇：
 *    A. 讓 AppUser 直接 implements UserDetails — entity 跟 framework 耦合，污染 domain
 *    B. 寫一個薄包裝 (AppUserPrincipal) — domain 與 framework 解耦
 *
 *  我們選 B。理由：
 *    - AppUser 是業務概念 (DDD aggregate)，不該知道 Spring Security 存在
 *    - 換 framework / 跨服務複用 entity 時，AppUser 不必動
 *    - Adapter Pattern 的標準應用場景
 *
 *  (面試題 / 中級)：「為什麼不讓 Entity implements UserDetails？」
 *    答：分層原則。Entity 是 domain layer，UserDetails 是 security framework 的 SPI。
 *        混在一起會讓 domain 改動牽動 framework，反之亦然。Adapter / Wrapper 把
 *        framework-specific 的東西隔離在 auth/ package 內，domain 維持乾淨。
 *
 *  ── 為什麼用 record？─────────────────────────────────────────────────
 *  Java 16+ 的 record 自動生 equals/hashCode/toString，且強制不可變 (final fields)。
 *  UserDetails 在被 Spring 放進 SecurityContext 後可能被多 thread 讀，不可變最安全。
 *  缺點：record 不能 extends class — 但這裡只 implements interface，所以沒問題。
 */
public record AppUserPrincipal(
        UUID userId,
        String username,
        String passwordHash,
        String displayName,
        boolean enabled,
        Collection<? extends GrantedAuthority> authorities
) implements UserDetails {

    /**
     * Factory method — 從 AppUser 構造 principal。
     * 把 enum role 轉成 Spring Security 的 ROLE_ prefixed authority。
     */
    public static AppUserPrincipal from(AppUser user) {
        Objects.requireNonNull(user, "AppUser must not be null");
        var authorities = user.getRoles().stream()
                .map(AppRole::toAuthority)
                .toList();
        return new AppUserPrincipal(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getDisplayName(),
                Boolean.TRUE.equals(user.getEnabled()),
                authorities
        );
    }

    // ──────────────────────────────────────────────────────────────────
    // UserDetails 介面實作
    // ──────────────────────────────────────────────────────────────────

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    /**
     * Spring Security 拿這個跟 client 提交的密碼比對 (BCryptPasswordEncoder.matches)。
     * 簽完 JWT 後 token 裡不會帶 password — 所以這個 method 只在「真正登入那一次」被呼叫。
     */
    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    /**
     * 我們的 AppUser 只有 enabled 一個 boolean，其他三個 (expired/locked/credentials)
     * 沒有獨立欄位，全部回 true。
     *
     *  四個 boolean 的差別 (面試題 / 中級)：
     *    enabled                 : 帳號是否啟用 (停用 / 軟刪除)
     *    accountNonExpired       : 帳號是否過期 (例如「合約到期，不再僱用」)
     *    accountNonLocked        : 帳號是否鎖定 (例如「密碼錯太多次，暫時鎖 30 分」)
     *    credentialsNonExpired   : 密碼是否過期 (例如「90 天必須改密碼」)
     *
     *  銀行業實務通常會把這四個拆出來，配合 audit 表記錄解鎖時間。
     *  本專案保持 MVP，只用 enabled。
     */
    @Override public boolean isAccountNonExpired()     { return true; }
    @Override public boolean isAccountNonLocked()      { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled()               { return enabled; }
}
