package com.sean.bancassurance;

import org.junit.jupiter.api.Test;

/**
 * 啟動煙霧測試 — 確認所有 Bean 能正確組裝、Flyway migration 跑得起來。
 *
 * 繼承 IntegrationTestBase 取得 Testcontainers PostgreSQL：
 *   - 不必本機開 docker compose 也跑得了 (CI 友善)
 *   - 跟 production 一模一樣的 PG 16，不踩 H2 SQL 方言地雷
 *
 * 為什麼空殼 contextLoads() 是有意義的測試？
 *   - 任何 Bean wiring 錯誤、Flyway DDL 寫壞、application.yml typo
 *     都會讓 ApplicationContext 載入失敗 → 這支測試直接 RED
 *   - 是 CI 的「整合測試最後防線」，啟動爆炸不會等到 prod deploy 才發現
 */
class BancassuranceApplicationTests extends IntegrationTestBase {

	@Test
	void contextLoads() {
	}

}
