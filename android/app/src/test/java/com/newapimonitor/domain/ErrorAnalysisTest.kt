package com.newapimonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 錯誤深入分析聚合：按模型計數 Top3、最近 10 筆窗口、空輸入、blank model 跳過。
 */
class ErrorAnalysisTest {

    private fun err(tsSec: Long, model: String? = "gpt-4o", token: String? = "tk") =
        LogEntry(type = 5, createdAt = tsSec, modelName = model, tokenName = token, quota = 0.0)

    @Test
    fun `按模型計數 - 次數 desc 排序 - 取 Top3`() {
        val rows = listOf(
            err(100, "a"), err(200, "a"), err(300, "a"),                    // a ×3
            err(400, "b"), err(500, "b"),                                    // b ×2
            err(600, "c"), err(700, "c"), err(800, "c"), err(900, "c"),      // c ×4
            err(1000, "d"),                                                  // d ×1（落榜）
            err(1100, "e"),                                                  // e ×1（落榜）
        )
        val r = ErrorAnalysis.compute(rows)
        assertEquals(11, r.total)
        assertEquals(3, r.topModels.size)
        assertEquals(listOf("c" to 4, "a" to 3, "b" to 2), r.topModels.map { it.name to it.count })
    }

    @Test
    fun `同分模型按名稱 asc 穩定排序`() {
        val rows = listOf(err(1, "zeta"), err(2, "alpha"), err(3, "mid"))
        val r = ErrorAnalysis.compute(rows)
        assertEquals(listOf("alpha", "mid", "zeta"), r.topModels.map { it.name })
    }

    @Test
    fun `recent 只留最新 10 筆 - 時間 desc`() {
        val rows = (1..12).map { err(it * 100L, "m$it") }
        val r = ErrorAnalysis.compute(rows)
        assertEquals(10, r.recent.size)
        assertEquals((12 downTo 3).map { "m$it" }, r.recent.map { it.model })
    }

    @Test
    fun `空輸入 - 全空`() {
        val r = ErrorAnalysis.compute(emptyList())
        assertEquals(0, r.total)
        assertEquals(emptyList<ErrorAnalysis.ErrGroup>(), r.topModels)
        assertEquals(emptyList<ErrorAnalysis.RecentError>(), r.recent)
    }

    @Test
    fun `blank model 行整筆跳過 - 唔入 group 唔入 recent 唔入 total`() {
        val rows = listOf(
            err(100, null),
            err(200, ""),
            err(300, "   "),
            err(400, "gpt-4o"),
        )
        val r = ErrorAnalysis.compute(rows)
        assertEquals(1, r.total)
        assertEquals(listOf("gpt-4o"), r.topModels.map { it.name })
        assertEquals(1, r.recent.size)
        assertEquals("gpt-4o", r.recent[0].model)
    }

    @Test
    fun `recent 帶 model token time`() {
        val r = ErrorAnalysis.compute(listOf(err(1_700_000_000, "gpt-4o", "demo-token")))
        val e = r.recent.single()
        assertEquals("gpt-4o", e.model)
        assertEquals("demo-token", e.token)
        assertEquals(1_700_000_000L, e.createdAtSec)
    }

    @Test
    fun `模型名前後空白會 trim 先聚合`() {
        val rows = listOf(err(1, " gpt-4o "), err(2, "gpt-4o"))
        val r = ErrorAnalysis.compute(rows)
        assertEquals(2, r.total)
        assertEquals(listOf("gpt-4o" to 2), r.topModels.map { it.name to it.count })
    }
}
