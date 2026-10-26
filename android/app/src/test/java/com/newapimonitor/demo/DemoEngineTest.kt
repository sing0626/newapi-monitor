package com.newapimonitor.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 演示模式：即開即有完整數據、形狀同真實快照一致、tick 有變化 */
class DemoEngineTest {

    @Test
    fun `snapshot 即載即有完整數據`() {
        DemoEngine.reset()
        val snap = DemoEngine.snapshot()
        assertTrue(snap.ok)
        assertTrue(snap.demo)
        assertEquals("演示實例", snap.name)
        assertFalse(snap.keyOnly)
        assertTrue(snap.todayCount > 0)
        assertTrue(snap.recentLogs.isNotEmpty() && snap.recentLogs.size <= 10)
        // 引擎有 8 個模型，但 ~90 條加權隨機日誌下罕見模型（qwen-max w=3）有 ~7% 機率抽唔中，
        // 斷言 >= 5 先係統計上穩陣（缺 3 個以上嘅機率 < 0.1%）
        assertTrue(snap.models.size >= 5)
        assertTrue((snap.balanceUsd ?: -1.0) > 0.0)
        assertTrue((snap.todayUsd ?: -1.0) >= 0.0)
        assertEquals(DemoEngine.QPU, snap.qpu, 1e-9)
        assertEquals("v0.8.2-demo", snap.status?.version)
    }

    @Test
    fun `queryLogs 支援範圍 模型篩選與分頁`() {
        DemoEngine.reset()
        val page1 = DemoEngine.queryLogs("today", null, 1, 5)
        assertTrue(page1.rows.size <= 5)
        assertTrue(page1.total >= page1.rows.size)
        val page2 = DemoEngine.queryLogs("today", null, 2, 5)
        // 分頁唔重疊
        if (page1.rows.isNotEmpty() && page2.rows.isNotEmpty()) {
            assertTrue(page1.rows.first().createdAt >= page2.rows.first().createdAt)
        }
        val model = DemoEngine.snapshot().models.first()
        val filtered = DemoEngine.queryLogs("7d", model, 1, 1000)
        assertTrue(filtered.rows.all { it.modelName == model })
    }

    @Test
    fun `所有日誌都是消費記錄形狀 - type 2 帶 model 與 quota`() {
        DemoEngine.reset()
        val snap = DemoEngine.snapshot()
        snap.modelStats.forEach { stat ->
            assertTrue(stat.requests > 0)
            assertTrue(stat.usd >= 0.0)
        }
        snap.recentLogs.forEach { r ->
            assertEquals(2, r.type)
            assertFalse(r.modelName.isNullOrEmpty())
            assertTrue((r.quota ?: -1.0) > 0)
        }
    }

    @Test
    fun `tick 後 balance 有變化且不為負`() {
        DemoEngine.reset()
        val before = DemoEngine.snapshot().balanceUsd
        repeat(50) { DemoEngine.tick() }
        val after = DemoEngine.snapshot().balanceUsd
        assertTrue((after ?: -1.0) >= 0.0)
        org.junit.Assert.assertNotEquals(before, after)
    }

    @Test
    fun `reset 之後可以重新生成`() {
        DemoEngine.reset()
        val a = DemoEngine.snapshot()
        DemoEngine.reset()
        val b = DemoEngine.snapshot()
        assertTrue(a.ok && b.ok)
        assertTrue(b.todayCount > 0)
    }

    /* ---------- 演示令牌（令牌 tab 用） ---------- */

    @Test
    fun `demo 令牌即載即有 - 形狀合理`() {
        DemoEngine.reset()
        val tokens = DemoEngine.tokens()
        assertTrue(tokens.isNotEmpty())
        tokens.forEach { t ->
            assertTrue(t.id > 0)
            assertTrue(t.name.isNotBlank())
            assertTrue(t.key.length >= 20)
            assertTrue(t.status in intArrayOf(1, 2, 3)) // 上游 status 語義：1 啟用 2 停用 3 封禁
            assertTrue(t.usedQuota >= 0.0)
            assertTrue(t.unlimitedQuota || t.remainQuota >= 0.0)
        }
        // 有啟用卡先似樣（狀態 mix：停用／封禁用嚟展示徽章）
        assertTrue(tokens.any { it.status == 1 })
        assertTrue(tokens.any { !it.unlimitedQuota })
    }

    @Test
    fun `demo 令牌切換狀態 - 命中更新 未命中返回 false`() {
        DemoEngine.reset()
        val t = DemoEngine.tokens().first { it.status == 1 }
        assertTrue(DemoEngine.setTokenStatus(t.id, 2))
        assertEquals(2, DemoEngine.tokens().first { it.id == t.id }.status)
        assertFalse(DemoEngine.setTokenStatus(999_999L, 1))
    }

    @Test
    fun `demo 快照帶 7 日消費數據 - 7 槽位今日有消費`() {
        DemoEngine.reset()
        val snap = DemoEngine.snapshot()
        assertEquals(7, snap.weekDays.size)
        assertTrue((snap.weekTotalUsd ?: -1.0) >= 0.0)
        // 今日（最後一格）一定有消費（demo 即載即有今日日誌）
        assertTrue(snap.weekDays.last().usd > 0.0)
    }
}
