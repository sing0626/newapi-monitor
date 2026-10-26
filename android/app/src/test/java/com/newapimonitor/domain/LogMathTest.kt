package com.newapimonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 日誌 type 過濾 / 聚合 / 去重 key */
class LogMathTest {

    private fun entry(
        type: Int? = 2,
        model: String? = "gpt-4o",
        quota: Double? = 100.0,
        pt: Long = 10,
        ct: Long = 5,
        createdAt: Long = 1000,
        useTime: Double = 1.2,
        tokenName: String? = "tok",
    ) = LogEntry(
        type = type,
        createdAt = createdAt,
        modelName = model,
        tokenName = tokenName,
        promptTokens = pt,
        completionTokens = ct,
        quota = quota,
        useTime = useTime,
    )

    @Test
    fun `充值與系統記錄被剔除 - 只留消費`() {
        val rows = listOf(
            entry(type = 1, model = "充值", quota = 500000.0), // 充值：唔可以出現
            entry(type = 2, model = "gpt-4o", quota = 100.0),  // 消費：保留
            entry(type = 3, model = "系統", quota = 10.0),      // 系統：剔除
            entry(type = 5, model = "管理", quota = 10.0),      // 管理：剔除
        )
        val filtered = LogMath.filterConsume(rows)
        assertEquals(1, filtered.size)
        assertEquals("gpt-4o", filtered[0].modelName)
        assertEquals(100.0, filtered[0].quota!!, 1e-9)
    }

    @Test
    fun `type 缺失但帶 model 和 quota 的舊站點記錄保留`() {
        val rows = listOf(entry(type = null, model = "deepseek-chat", quota = 7.0))
        assertEquals(1, LogMath.filterConsume(rows).size)
    }

    @Test
    fun `缺 model_name 或缺 quota 的行被剔除`() {
        val rows = listOf(
            entry(model = "", quota = 100.0),
            entry(model = null, quota = 100.0),
            entry(model = "gpt-4o", quota = null),
        )
        assertTrue(LogMath.filterConsume(rows).isEmpty())
    }

    @Test
    fun `sumQuota 忽略 null quota`() {
        val rows = listOf(entry(quota = 100.0), entry(quota = 50.0), entry(quota = null))
        assertEquals(150.0, LogMath.sumQuota(rows), 1e-9)
    }

    @Test
    fun `aggregateStats 按 usd 降序再按 requests 降序`() {
        val rows = listOf(
            entry(model = "a", quota = 100.0, pt = 1, ct = 1),
            entry(model = "a", quota = 100.0, pt = 1, ct = 1),
            entry(model = "b", quota = 500.0, pt = 2, ct = 2),
            entry(model = "c", quota = 0.0, pt = 3, ct = 3),
        )
        val stats = LogMath.aggregateStats(rows, 500000.0)
        assertEquals(listOf("b", "a", "c"), stats.map { it.model })
        assertEquals(500.0 / 500000.0, stats[0].usd, 1e-12)
        assertEquals(2, stats[1].requests)
        assertEquals(4L, stats[1].tokens)
    }

    @Test
    fun `aggregateStats 空 rows 返回空列表`() {
        assertTrue(LogMath.aggregateStats(emptyList(), 500000.0).isEmpty())
    }

    @Test
    fun `logKey 由內容組成唔依賴 id`() {
        val a = entry(createdAt = 42, model = "m", tokenName = "t", pt = 1, ct = 2, quota = 3.0, useTime = 0.5)
        val b = entry(createdAt = 42, model = "m", tokenName = "t", pt = 1, ct = 2, quota = 3.0, useTime = 0.5)
        assertEquals(LogMath.logKey(a), LogMath.logKey(b))
        assertTrue(LogMath.logKey(a).contains("42"))
    }

    @Test
    fun `colorIndexForModel 穩定且落在 palette 範圍`() {
        val i1 = LogMath.colorIndexForModel("gpt-4o", 8)
        val i2 = LogMath.colorIndexForModel("gpt-4o", 8)
        assertEquals(i1, i2)
        assertTrue(i1 in 0 until 8)
    }
}
