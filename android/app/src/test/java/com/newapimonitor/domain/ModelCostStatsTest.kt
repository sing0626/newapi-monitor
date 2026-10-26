package com.newapimonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型性價比聚合：分組 / 零 token 跳過 / usdPer1M 數學 / 排序 / 空白名跳過 */
class ModelCostStatsTest {

    private fun entry(
        model: String? = "gpt-4o",
        quota: Double? = 500000.0,
        pt: Long = 1000,
        ct: Long = 1000,
        type: Int? = 2,
    ) = LogEntry(
        type = type,
        modelName = model,
        promptTokens = pt,
        completionTokens = ct,
        quota = quota,
    )

    @Test
    fun `同模型多筆記錄聚合 - calls 與 tokens 累加`() {
        val rows = listOf(
            entry(model = "gpt-4o", quota = 500000.0, pt = 1000, ct = 1000),
            entry(model = "gpt-4o", quota = 500000.0, pt = 2000, ct = 3000),
            entry(model = "claude", quota = 250000.0, pt = 500, ct = 500),
        )
        val out = ModelCostStats.compute(rows, 500000.0)
        assertEquals(2, out.size)
        val gpt = out.first { it.name == "gpt-4o" }
        assertEquals(2, gpt.calls)
        assertEquals(7000L, gpt.tokens)
        assertEquals(2.0, gpt.usd, 1e-12) // (500000 + 500000) / 500000
        val claude = out.first { it.name == "claude" }
        assertEquals(1, claude.calls)
        assertEquals(0.5, claude.usd, 1e-12)
    }

    @Test
    fun `零 token 嘅模型被跳過`() {
        val rows = listOf(
            entry(model = "gpt-4o", quota = 100000.0, pt = 0, ct = 0),
            entry(model = "claude", quota = 100000.0, pt = 10, ct = 0),
        )
        val out = ModelCostStats.compute(rows, 500000.0)
        assertEquals(listOf("claude"), out.map { it.name })
    }

    @Test
    fun `usdPer1M 數學 - usd 除以每百萬 token`() {
        // quota 500000 ÷ qpu 500000 = $1；tokens 2,000,000 → $1 ÷ 2 = $0.5 / 1M
        val rows = listOf(entry(model = "gpt-4o", quota = 500000.0, pt = 1_000_000L, ct = 1_000_000L))
        val out = ModelCostStats.compute(rows, 500000.0)
        assertEquals(1, out.size)
        assertEquals(1.0, out[0].usd, 1e-12)
        assertEquals(0.5, out[0].usdPer1M, 1e-12)
    }

    @Test
    fun `按 usdPer1M 降序排列`() {
        val rows = listOf(
            // b：$0.5 / 1M tok；a：$2.0 / 1M tok；c：$1.0 / 1M tok
            entry(model = "b-cheap", quota = 250000.0, pt = 500_000L, ct = 500_000L),
            entry(model = "a-dear", quota = 1_000_000.0, pt = 500_000L, ct = 500_000L),
            entry(model = "c-mid", quota = 500000.0, pt = 500_000L, ct = 500_000L),
        )
        val out = ModelCostStats.compute(rows, 500000.0)
        assertEquals(listOf("a-dear", "c-mid", "b-cheap"), out.map { it.name })
    }

    @Test
    fun `空白模型名被跳過`() {
        val rows = listOf(
            entry(model = "", quota = 100000.0, pt = 100, ct = 100),
            entry(model = null, quota = 100000.0, pt = 100, ct = 100),
            entry(model = "  ", quota = 100000.0, pt = 100, ct = 100),
            entry(model = "gpt-4o", quota = 100000.0, pt = 100, ct = 100),
        )
        val out = ModelCostStats.compute(rows, 500000.0)
        assertEquals(listOf("gpt-4o"), out.map { it.name })
    }

    @Test
    fun `防禦式再過濾 - 充值與錯誤行唔入數`() {
        val rows = listOf(
            entry(model = "gpt-4o", quota = 999999.0, pt = 1000, ct = 1000, type = 1),
            entry(model = "gpt-4o", quota = 999999.0, pt = 1000, ct = 1000, type = 5),
            entry(model = "gpt-4o", quota = 500000.0, pt = 1000, ct = 1000, type = 2),
        )
        val out = ModelCostStats.compute(rows, 500000.0)
        assertEquals(1, out.size)
        assertEquals(1, out[0].calls)
        assertEquals(1.0, out[0].usd, 1e-12)
    }

    @Test
    fun `空列表與非法 qpu 都安全`() {
        assertTrue(ModelCostStats.compute(emptyList(), 0.0).isEmpty())
        assertTrue(ModelCostStats.compute(emptyList(), -1.0).isEmpty())
        // qpu 非法 → fallback Quota.DEFAULT_QPU（500000）：quota 500000 → $1 ÷ 1M tok = $1.0
        val rows = listOf(entry(model = "gpt-4o", quota = 500000.0, pt = 1_000_000L, ct = 0))
        val out = ModelCostStats.compute(rows, Double.NaN)
        assertEquals(1, out.size)
        assertEquals(1.0, out[0].usdPer1M, 1e-9)
    }
}
