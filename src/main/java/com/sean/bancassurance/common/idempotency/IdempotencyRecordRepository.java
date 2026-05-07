package com.sean.bancassurance.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 冪等性紀錄 Repository。
 *
 * 介面只暴露最小集合：findById (PK = idempotency_key) + save。
 * 沒有 update — 冪等紀錄一次寫死，更新等於改稽核資料。
 */
@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {
    // 用 PK 查 = findById(idempotencyKey)
    // save = INSERT (新 key)
    // 其他方法 (delete*, count) 來自 JpaRepository 預設，本系統不會主動呼叫
}
