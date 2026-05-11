package com.sean.bancassurance.auth.service;

import com.sean.bancassurance.auth.domain.AppUserPrincipal;
import com.sean.bancassurance.auth.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Security 的 SPI — UserDetailsService DB-backed 實作。
 *
 *  ── 在認證流的位置 ─────────────────────────────────────────────────────
 *
 *  這個介面是 Spring Security 「載入使用者資料」的標準擴展點 (SPI)：
 *
 *      AuthenticationManager.authenticate(loginToken)
 *           │
 *           ▼
 *      DaoAuthenticationProvider
 *           │  1. UserDetailsService.loadUserByUsername(username) ← 我們實作這個
 *           │  2. PasswordEncoder.matches(rawPassword, userDetails.getPassword())
 *           │  3. 回傳 Authenticated authentication
 *           ▼
 *      SecurityContextHolder.getContext().setAuthentication(...)
 *
 *  所以本 class 的職責「只」是「給我 username，我給你 UserDetails」 — 不負責比 password。
 *  比 password 是 DaoAuthenticationProvider + PasswordEncoder 的事 (M9.4 設定)。
 *
 *  ── 為什麼要 @Transactional(readOnly = true)？──────────────────────────
 *
 *  AppUser.roles 是 @ElementCollection，雖然 fetch=EAGER，但 Hibernate 仍可能
 *  在某些 dialect / 版本下「分兩條 SELECT」載入。沒交易的話：
 *    1. SELECT app_user → close session
 *    2. session.getRoles() → LazyInitializationException
 *
 *  加上 @Transactional 確保整個 method 跑在同一個 Persistence Context，
 *  不論 EAGER 是否分兩條都能成功撈到 roles。
 *
 *  readOnly=true 暗示 Hibernate「不必 dirty-check / flush」，效能略好。
 *
 *  (面試題 / 中級)：「Service @Transactional(readOnly=true) 對 SQL 有什麼影響？」
 *    - 對 application code：Hibernate 跳過 dirty-checking，省 CPU
 *    - 對 DB：JDBC connection 的 readOnly hint，部分 RDBMS (如 MySQL InnoDB) 會走
 *      只讀 replica；PG 主要影響的是 connection pool 提示。
 *    - 對主 / 從架構：搭配 RoutingDataSource 可自動把 readOnly 路由到 read replica。
 *
 *  ── 安全細節：UsernameNotFoundException 訊息要不要洩漏 username？─────────
 *
 *  寫法 A：throw new UsernameNotFoundException("User not found: " + username);
 *  寫法 B：throw new UsernameNotFoundException("Bad credentials");
 *
 *  業界爭議：
 *    A 對 debug 友善，但允許「列舉攻擊 (user enumeration)」— 攻擊者能從
 *      回傳訊息差異判斷「這個 username 存在 / 不存在」。
 *    B 統一訊息，攻擊者拿到 401 但不知道是 user 不在還是 password 錯。
 *
 *  Spring Security 5+ 預設行為「會把 UsernameNotFoundException 轉成 BadCredentialsException」
 *  → 即使我們這裡丟 A，DaoAuthenticationProvider 也會把它換成「Bad credentials」回給 client。
 *  所以後台 log 寫 username (有 trace)、回給 client 看到的永遠是「Bad credentials」 — 兩全其美。
 *
 *  我們這邊：
 *    - log 印 username（debug 用）
 *    - 例外訊息也帶 username（後台統一 logging）
 *    - DaoAuthenticationProvider 會吃掉它換成 BadCredentialsException → client 看到 401 「Bad credentials」
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DbUserDetailsService implements UserDetailsService {

    private final AppUserRepository userRepository;

    /**
     * 依 username 載入 UserDetails。
     *
     * Spring Security 對「找不到」的約定：丟 UsernameNotFoundException。
     * DaoAuthenticationProvider 會把這個翻成 BadCredentialsException 回給 caller，
     * 避免「使用者列舉」漏洞。
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        log.debug("Loading user details: username={}", username);
        return userRepository.findByUsername(username)
                .map(AppUserPrincipal::from)
                .orElseThrow(() -> {
                    log.info("Login attempt for unknown username: {}", username);
                    return new UsernameNotFoundException("User not found: " + username);
                });
    }
}
