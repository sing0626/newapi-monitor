package com.newapimonitor.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DemoEngine 擴充：渠道池、7 日餘額歷史種子、type=5 錯誤日誌池 */
class DemoEngineExtrasTest {

    /* ---------------- 渠道 ---------------- */

    @Test
    fun `demo 渠道 5 條 - status 混合 1_2_3 有響應時間`() {
        DemoEngine.reset()
        val channels = DemoEngine.channels()
        assertEquals(5, channels.size)
        assertTrue(channels.any { it.status == 1 })
        assertTrue(channels.any { it.status == 2 })
        assertTrue(channels.any { it.status == 3 })
        channels.forEach { c ->
            assertTrue(c.id > 0)
            assertTrue(c.name.isNotBlank())
            assertTrue(c.type > 0)
            assertTrue((c.responseTime ?: -1) > 0)
        }
        // id 唯一
        assertEquals(channels.size, channels.map { it.id }.toSet().size)
    }

    /* ---------------- 餘額歷史種子 ---------------- */

    @Test
    fun `historySeed 7 日每小時一點 - 最新點等於當前餘額`() {
        DemoEngine.reset()
        val snap = DemoEngine.snapshot()
        val seed = DemoEngine.historySeed("demo")
        // 7 日 × 24 小時 + 1（最新一點）
        assertEquals(7 * 24 + 1, seed.size)
        // 時間遞增、起點 ≈ 7 日前（生成時 now 同測試 now 有幾 ms 差，上界加容差）
        assertTrue(seed.zipWithNext().all { (a, b) -> a.t < b.t })
        val now = System.currentTimeMillis()
        assertTrue(now - seed.first().t in 7 * 3_600_000L..7 * 86_400_000L + 5_000L)
        // 最新點：時間 ≈ now、值 = 當前 balance
        assertTrue(now - seed.last().t < 5_000L)
        assertEquals(snap.balanceUsd!!, seed.last().v, 1e-9)
    }

    @Test
    fun `historySeed 帶自然下降曲線 - 起初明顯高過而家`() {
        DemoEngine.reset()
        DemoEngine.snapshot()
        val seed = DemoEngine.historySeed("demo")
        val firstDayAvg = seed.take(24).map { it.v }.average()
        val last = seed.last().v
        assertTrue(
            "頭 24 點平均 ($firstDayAvg) 應明顯高過尾點 ($last)",
            firstDayAvg > last * 1.1,
        )
        seed.forEach { assertTrue(it.v.isFinite() && it.v >= 0.0) }
    }

    @Test
    fun `historySeed 同一 instId 短時間內重生成一致`() {
        DemoEngine.reset()
        DemoEngine.snapshot()
        val a = DemoEngine.historySeed("demo")
        val b = DemoEngine.historySeed("demo")
        // 兩次連續生成（同一 6 小時窗）數值應一致
        assertEquals(a.size, b.size)
        assertEquals(a.last().v, b.last().v, 1e-9)
    }

    /* ---------------- type=5 錯誤日誌 ---------------- */

    @Test
    fun `demo 日誌池有 type_5 錯誤記錄 - 帶 model 與 content quota 為零`() {
        // ~4% 佔比：單次 start 有 ~2.5% 機率零錯誤 → 最多試 5 次（全零機率 ~1e-8）
        repeat(5) {
            DemoEngine.reset()
            DemoEngine.snapshot()
            val errors = DemoEngine.queryLogs("7d", null, 1, 1000).rows.filter { it.type == 5 }
            if (errors.isNotEmpty()) {
                errors.forEach { e ->
                    assertEquals(5, e.type)
                    assertTrue(!e.modelName.isNullOrEmpty())
                    assertEquals(0.0, e.quota!!, 1e-9)
                    assertTrue(!e.content.isNullOrEmpty())
                }
                return
            }
        }
        throw AssertionError("5 次重生成都搵唔到 type=5 錯誤記錄（機率 < 1e-8）")
    }

    @Test
    fun `錯誤記錄唔會入消費統計 - snapshot today 為純消費`() {
        DemoEngine.reset()
        val snap = DemoEngine.snapshot()
        // recentLogs 只會有 type=2 消費行
        snap.recentLogs.forEach { r ->
            assertEquals(2, r.type)
            assertTrue((r.quota ?: 0.0) > 0.0)
        }
    }

    /* ---------------- 令牌封禁展示 ---------------- */

    @Test
    fun `demo 令牌確保有 status 3 封禁卡（TOKEN_BANNED 演示用）`() {
        DemoEngine.reset()
        assertTrue(DemoEngine.tokens().any { it.status == 3 })
    }
}
