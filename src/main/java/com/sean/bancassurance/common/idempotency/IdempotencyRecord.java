package com.sean.bancassurance.common.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 冪等性紀錄 — 「同一個 Idempotency-Key 的請求結果」儲存。
 *
 * 為什麼放 common/ 不放 policy/？
 *   冪等性是跨業務領域的基礎建設 — 未來核保送件 / 商品上架都會用到。
 *   先放 common/ 表達「這是公共能力，不屬於任何單一領域」。
 *
 * ★ 為什麼用 idempotency_key 當 PK？
 *   1. 自然主鍵 — 已經是唯一的，多開一個 surrogate id 反而冗餘
 *   2. INSERT 撞 PK 直接拋 DataIntegrityViolationException → service 接住做 replay 邏輯
 *      (其實 service 用 SELECT 先查，這條只是 DB 端最後一道防線；高併發時兩個 request
 *       同時 SELECT 都沒查到、都 INSERT，PK 衝突會擋住第二個)
 *
 * (面試題 / 資深)：「冪等性鍵怎麼處理併發？兩個 request 同 key 同時打進來怎辦？」
 *   答：
 *     1. 樂觀路線：兩個 request 先 SELECT (沒查到) → 都進業務邏輯 → INSERT 階段
 *        其中一個會 PK 衝突拋例外 → 那條 rollback；client 看到 5xx 重送一次，
 *        就會 hit 到第一條已 commit 的紀錄、走 replay 路徑
 *     2. 悲觀路線：用 SELECT FOR UPDATE 鎖 key — 串行化但簡單
 *     3. 最強：分散式鎖 (Redis SETNX) — 給高 TPS 系統用
 *   M5 我們採 (1) — DB 約束就是天然鎖。
 */
@Entity
@Table(name = "idempotency_record")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class IdempotencyRecord {

    @Id
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "endpoint", nullable = false, updatable = false, length = 255)
    private String endpoint;

    /** SHA-256 hex (64 chars) */
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status", nullable = false, updatable = false)
    private Integer responseStatus;

    @ToString.Exclude   // 可能很長，避免污染 log
    @Column(name = "response_body", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false, length = 64)
    private String createdBy;
}
