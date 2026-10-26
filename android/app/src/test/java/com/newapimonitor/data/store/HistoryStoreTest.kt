package com.newapimonitor.data.store

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.newapimonitor.domain.BalanceSample
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * 餘額歷史持久化（DataStore）：讀寫 roundtrip、同分鐘合併、30 日修剪、1000 點抽稀、清除。
 * DataStore 係 process 內單例 → 每個測試用獨立 instId 避免互相污染。
 */
@RunWith(RobolectricTestRunner::class)
class HistoryStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Application>()

    private fun newInstId(): String = "hist-" + UUID.randomUUID().toString().replace("-", "").take(16)

    /* ---------------- 讀寫 roundtrip ---------------- */

    @Test
    fun `addSample 與 historyOnce roundtrip`() = runBlocking {
        val store = HistoryStore(context)
        val id = newInstId()
        // 用近現在嘅時間戳（對齊分鐘邊界）：30 日保留期會刪走太舊嘅點
        val base = (System.currentTimeMillis() / 60_000L) * 60_000L - 2 * 60_000L
        store.addSample(id, base, 12.5)
        store.addSample(id, base + 60_000L, 12.4)
        val hist = store.historyOnce(id)
        assertEquals(
            listOf(BalanceSample(base, 12.5), BalanceSample(base + 60_000L, 12.4)),
            hist,
        )
        // flow 同一數據
        assertEquals(hist, store.historyFlow(id).first())
        store.clearHistory(id)
    }

    @Test
    fun `新 store 實例讀到同一 DataStore - 持久化語義`() = runBlocking {
        val id = newInstId()
        val t = System.currentTimeMillis() - 60_000L
        HistoryStore(context).addSample(id, t, 3.0)
        val hist = HistoryStore(context).historyOnce(id)
        assertEquals(listOf(BalanceSample(t, 3.0)), hist)
        store_clear(id)
    }

    private suspend fun store_clear(id: String) = HistoryStore(context).clearHistory(id)

    /* ---------------- 同分鐘合併 ---------------- */

    @Test
    fun `同一分鐘內多個取樣合併成最後一個`() = runBlocking {
        val id = newInstId()
        val store = HistoryStore(context)
        // base 對齊分鐘邊界（保留期內）：base／base+30s 一定同桶，base+60s 一定下一桶
        val base = (System.currentTimeMillis() / 60_000L) * 60_000L - 3 * 60_000L
        store.addSamples(
            id,
            listOf(
                BalanceSample(base, 10.0),           // minute X
                BalanceSample(base + 30_000L, 11.0), // minute X（同分鐘）→ 應被 11 覆蓋
                BalanceSample(base + 60_000L, 12.0), // minute X+1
            ),
        )
        val hist = store.historyOnce(id)
        assertEquals(
            listOf(BalanceSample(base + 30_000L, 11.0), BalanceSample(base + 60_000L, 12.0)),
            hist,
        )
        store.clearHistory(id)
    }

    /* ---------------- 修剪＋抽稀（mergeAndTrim 純邏輯） ---------------- */

    private val store = HistoryStore(context)

    @Test
    fun `30 日以前嘅點刪走`() {
        val now = 1_800_000_000_000L
        val samples = listOf(
            BalanceSample(now - HistoryStore.RETENTION_MS - 1, 1.0), // 剛好界外 → 刪
            BalanceSample(now - HistoryStore.RETENTION_MS + 60_000L, 2.0), // 界內 → 留
            BalanceSample(now, 3.0),
        )
        val out = store.mergeAndTrim(emptyList(), samples, now)
        assertEquals(listOf(samples[1], samples[2]), out)
    }

    @Test
    fun `超過 1000 點抽稀 - 保證不超標且最新點保留`() {
        val now = 1_800_000_000_000L
        val n = 1001
        val samples = (0 until n).map { i -> BalanceSample(now - (n - 1L - i) * 60_000L, i.toDouble()) }
        val out = store.mergeAndTrim(emptyList(), samples, now)
        assertTrue("抽稀後要 ≤ 1000，實際 ${out.size}", out.size <= HistoryStore.MAX_POINTS)
        assertEquals(samples.last(), out.last()) // 最新一點永遠留
        // 每第 N 個保留：第一點一定喺度
        assertEquals(samples.first(), out.first())
    }

    @Test
    fun `抽稀 N 嘅計法 - 2001 點都唔超標`() {
        val now = 1_800_000_000_000L
        val n = 2001
        val samples = (0 until n).map { i -> BalanceSample(now - (n - 1L - i) * 60_000L, i.toDouble()) }
        val out = store.mergeAndTrim(emptyList(), samples, now)
        assertTrue(out.size <= HistoryStore.MAX_POINTS)
        assertEquals(samples.last(), out.last())
    }

    @Test
    fun `同值取樣照存 - 唔做值去重`() {
        val now = 1_800_000_000_000L
        val out = store.mergeAndTrim(
            emptyList(),
            listOf(
                BalanceSample(now - 120_000L, 5.0),
                BalanceSample(now - 60_000L, 5.0),
                BalanceSample(now, 5.0),
            ),
            now,
        )
        assertEquals(3, out.size)
    }

    @Test
    fun `非有限值同負時間戳被過濾`() {
        val now = 1_800_000_000_000L
        val out = store.mergeAndTrim(
            emptyList(),
            listOf(
                BalanceSample(now, Double.NaN),
                BalanceSample(-1L, 5.0),
                BalanceSample(now - 60_000L, 4.0),
            ),
            now,
        )
        assertEquals(listOf(BalanceSample(now - 60_000L, 4.0)), out)
    }

    /* ---------------- 清除 ---------------- */

    @Test
    fun `clearHistory 清走該實例`() = runBlocking {
        val id = newInstId()
        val store = HistoryStore(context)
        store.addSample(id, 1_000L, 1.0)
        store.clearHistory(id)
        assertTrue(store.historyOnce(id).isEmpty())
    }

    @Test
    fun `損壞 JSON 返回空列表唔會炸`() = runBlocking {
        val id = newInstId()
        context.dataStore.edit {
            it[androidx.datastore.preferences.core.stringPreferencesKey("balance_history.v1.$id")] = "not json{"
        }
        assertTrue(HistoryStore(context).historyOnce(id).isEmpty())
        HistoryStore(context).clearHistory(id)
    }
}
