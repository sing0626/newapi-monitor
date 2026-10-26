package com.newapimonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 令牌燒錢榜聚合（本月每個令牌消費）：filterConsume 過濾、空白 tokenName 跳過、
 * 按令牌分組 sum(quota)÷qpu、usd 降序、take 5、truncated 透傳。純 JUnit。
 */
class TokenSpendStatsTest {

    private val qpu = 500000.0

    private fun consume(
        tokenName: String?,
        quota: Double?,
        type: Int? = 2,
        modelName: String? = "gpt-4o",
    ) = LogEntry(
        type = type,
        createdAt = 1_790_000_000L,
        modelName = modelName,
        tokenName = tokenName,
        quota = quota,
    )

    @Test
    fun `同令牌多行聚合 - quota 總和除 qpu 同次數正確`() {
        val logs = listOf(
            consume("ide-chat", 250_000.0), // 0.5 USD
            consume("ide-chat", 125_000.0), // 0.25 USD
            consume("prod-app", 500_000.0), // 1.0 USD
        )
        val res = TokenSpendStats.compute(logs, qpu)
        assertEquals(2, res.rows.size)
        assertEquals("prod-app", res.rows[0].name)
        assertEquals(1.0, res.rows[0].usd, 1e-9)
        assertEquals(1, res.rows[0].calls)
        assertEquals("ide-chat", res.rows[1].name)
        assertEquals(0.75, res.rows[1].usd, 1e-9)
        assertEquals(2, res.rows[1].calls)
    }

    @Test
    fun `空白或缺失 tokenName 嘅行跳過`() {
        val logs = listOf(
            consume("ide-chat", 250_000.0),
            consume(null, 999_999.0),
            consume("", 999_999.0),
            consume("   ", 999_999.0),
        )
        val res = TokenSpendStats.compute(logs, qpu)
        assertEquals(1, res.rows.size)
        assertEquals("ide-chat", res.rows[0].name)
        assertEquals(0.5, res.rows[0].usd, 1e-9)
    }

    @Test
    fun `非消費行唔計 - 充值同錯誤記錄被 filterConsume 剔除`() {
        val logs = listOf(
            consume("ide-chat", 250_000.0),
            consume("topup", 5_000_000.0, type = 1), // 充值
            consume("ide-chat", null, type = 5), // 錯誤記錄
        )
        val res = TokenSpendStats.compute(logs, qpu)
        assertEquals(1, res.rows.size)
        assertEquals("ide-chat", res.rows[0].name)
        assertEquals(1, res.rows[0].calls)
    }

    @Test
    fun `usd 降序排序`() {
        val logs = listOf(
            consume("a-low", 50_000.0), // 0.1
            consume("c-high", 900_000.0), // 1.8
            consume("b-mid", 250_000.0), // 0.5
        )
        val res = TokenSpendStats.compute(logs, qpu)
        assertEquals(listOf("c-high", "b-mid", "a-low"), res.rows.map { it.name })
        assertEquals(1.8, res.rows[0].usd, 1e-9)
    }

    @Test
    fun `只取前 5 名`() {
        val logs = (1..8).map { i ->
            consume("tok-$i", i * 100_000.0) // usd = 0.2i，tok-8 最多
        }
        val res = TokenSpendStats.compute(logs, qpu)
        assertEquals(TokenSpendStats.TOP_N, res.rows.size)
        assertEquals(listOf("tok-8", "tok-7", "tok-6", "tok-5", "tok-4"), res.rows.map { it.name })
    }

    @Test
    fun `qpu 換算 - quota 除以 qpu`() {
        val logs = listOf(consume("ide-chat", 1_000_000.0))
        val res = TokenSpendStats.compute(logs, qpu)
        assertEquals(2.0, res.rows[0].usd, 1e-9)
    }

    @Test
    fun `qpu 非法時用預設值兜底`() {
        val logs = listOf(consume("ide-chat", Quota.DEFAULT_QPU))
        val res = TokenSpendStats.compute(logs, qpu = 0.0)
        assertEquals(1.0, res.rows[0].usd, 1e-9)
    }

    @Test
    fun `truncated 透傳 - 預設 false`() {
        val logs = listOf(consume("ide-chat", 250_000.0))
        assertFalse(TokenSpendStats.compute(logs, qpu).truncated)
        assertTrue(TokenSpendStats.compute(logs, qpu, truncated = true).truncated)
    }

    @Test
    fun `空輸入返回空結果`() {
        val res = TokenSpendStats.compute(emptyList(), qpu)
        assertTrue(res.rows.isEmpty())
        assertFalse(res.truncated)
    }

    @Test
    fun `quota 為 null 嘅行被 filterConsume 剔除 - 唔計次數`() {
        val logs = listOf(
            consume("ide-chat", 250_000.0),
            consume("ide-chat", null), // filterConsume 要求 quota 非空 → 成行剔除
        )
        val res = TokenSpendStats.compute(logs, qpu)
        assertEquals(1, res.rows.size)
        assertEquals(1, res.rows[0].calls)
        assertEquals(0.5, res.rows[0].usd, 1e-9)
    }
}
