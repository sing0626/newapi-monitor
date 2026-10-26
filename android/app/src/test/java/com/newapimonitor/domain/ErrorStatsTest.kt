package com.newapimonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 錯誤統計（type=5 錯誤行 vs type=2 消費行分類）：24 小時窗口、按模型計數、錯誤率。
 */
class ErrorStatsTest {

    private val nowMs = 1_800_000_000_000L

    private fun consume(tsSec: Long, model: String = "gpt-4o", quota: Double = 1000.0) =
        LogEntry(type = 2, createdAt = tsSec, modelName = model, quota = quota)

    private fun error(tsSec: Long, model: String = "gpt-4o", content: String? = "boom") =
        LogEntry(type = 5, createdAt = tsSec, modelName = model, quota = 0.0, content = content)

    @Test
    fun `錯誤與消費分類正確 - 24h 窗口計數`() {
        val cutoff = nowMs / 1000 - 86_400L
        val rows = listOf(
            consume(cutoff + 100),
            consume(cutoff + 200, model = "claude-3"),
            error(cutoff + 300),                      // 24h 內
            error(cutoff - 3600, model = "claude-3"), // 24h 外（仍計入窗口）
        )
        val s = ErrorStats.compute(rows, nowMs)
        assertEquals(1, s.last24hCount)
        assertEquals(2, s.windowCount)
        assertEquals(2, s.consumeCount)
        assertEquals(0.5, s.rate, 1e-9)
        assertEquals(mapOf("gpt-4o" to 1, "claude-3" to 1), s.byModel)
    }

    @Test
    fun `充值與系統行唔會入消費數`() {
        val rows = listOf(
            LogEntry(type = 1, createdAt = nowMs / 1000, modelName = "topup", quota = 500000.0),
            LogEntry(type = 5, createdAt = nowMs / 1000, modelName = "gpt-4o"),
        )
        val s = ErrorStats.compute(rows, nowMs)
        assertEquals(0, s.consumeCount)
        assertEquals(1, s.windowCount)
        assertEquals(1.0, s.rate, 1e-9)
    }

    @Test
    fun `type 缺失行靠 model 與 quota 把關 - 同 filterConsume 口徑`() {
        val rows = listOf(
            LogEntry(type = null, createdAt = nowMs / 1000, modelName = "gpt-4o", quota = 100.0),
            LogEntry(type = null, createdAt = nowMs / 1000, modelName = null, quota = 100.0), // 無 model → 唔計
            LogEntry(type = null, createdAt = nowMs / 1000, modelName = "x", quota = null),   // 無 quota → 唔計
        )
        val s = ErrorStats.compute(rows, nowMs)
        assertEquals(1, s.consumeCount)
        assertEquals(0, s.windowCount)
        assertEquals(0.0, s.rate, 1e-9)
    }

    @Test
    fun `缺 model_name 嘅錯誤計入 unknown`() {
        val rows = listOf(
            error(nowMs / 1000, model = ""),
            error(nowMs / 1000 - 10, model = "gpt-4o"),
        )
        val s = ErrorStats.compute(rows, nowMs)
        assertEquals(2, s.windowCount)
        assertEquals(mapOf("unknown" to 1, "gpt-4o" to 1), s.byModel)
    }

    @Test
    fun `空輸入全零`() {
        val s = ErrorStats.compute(emptyList(), nowMs)
        assertEquals(0, s.last24hCount)
        assertEquals(0, s.windowCount)
        assertEquals(0, s.consumeCount)
        assertEquals(0.0, s.rate, 1e-9)
        assertEquals(emptyMap<String, Int>(), s.byModel)
    }

    @Test
    fun `content 錯誤信息保留喺行內 - 統計唔需要但行照帶`() {
        val rows = listOf(error(nowMs / 1000, content = "无可用渠道"))
        assertEquals("无可用渠道", rows[0].content)
    }
}
