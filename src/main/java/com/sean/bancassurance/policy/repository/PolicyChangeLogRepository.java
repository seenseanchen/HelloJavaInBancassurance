package com.sean.bancassurance.policy.repository;

import com.sean.bancassurance.policy.domain.PolicyChangeLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 保單變更稽核 Repository — append-only。
 *
 *  ★ 故意「不」開放任何 update / delete 介面：
 *     - JpaRepository 預設有 deleteById, save (update), saveAll 等
 *     - 我們只在 service 用 save() 做「INSERT」(id 是新生成的 → JPA 走 persist 路徑)
 *     - 真要嚴格擋，可繼承 Repository<PolicyChangeLog, UUID> 而不是 JpaRepository，
 *       自己只暴露需要的方法 — 這在「合規嚴格」的金融業常見作法
 *
 *  本階段先用 JpaRepository 偷懶，但寫註解提醒未來要不要收緊。
 */
@Repository
public interface PolicyChangeLogRepository extends JpaRepository<PolicyChangeLog, UUID> {

    /**
     * 撈某保單的完整變更歷史，按時間倒序 (新的在上)。
     *
     * 對應索引：idx_policy_change_log_policy_occurred_at (policy_id, occurred_at DESC)。
     */
    List<PolicyChangeLog> findByPolicyIdOrderByOccurredAtDesc(UUID policyId);
}
