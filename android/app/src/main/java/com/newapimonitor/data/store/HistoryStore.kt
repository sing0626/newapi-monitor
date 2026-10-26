package com.newapimonitor.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.newapimonitor.domain.BalanceSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.ceil

/**
 * 餘額歷史持久化（每實例一個 DataStore preferences key，JSON 列表，跟 InstanceRepository 模式）：
 *  - 每個實例最多 1000 點；超出就抽稀（每第 N 個保留，保證唔超標，最新一點永遠留低）
 *  - 30 日以前嘅點刪走
 *  - 同一分鐘內多個取樣合併成最後一個（同值冇變都可以照存）
 *  - 刪除實例時由 InstanceRepository 呼叫 clearHistory 清走
 */
class HistoryStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    /* ---------------- 讀 ---------------- */

    /** 實例歷史（舊→新 ascending）；實例冇歷史返回空列表 */
    fun historyFlow(instId: String): Flow<List<BalanceSample>> =
        context.dataStore.data.map { prefs -> parse(prefs[keyFor(instId)]) }

    suspend fun historyOnce(instId: String): List<BalanceSample> =
        historyFlow(instId).first()

    /* ---------------- 寫 ---------------- */

    /** 記一個餘額取樣：同一分鐘內合併成最後一個；寫入前做 30 日修剪＋1000 點抽稀 */
    suspend fun addSample(instId: String, t: Long, v: Double) =
        addSamples(instId, listOf(BalanceSample(t, v)))

    /** 批量寫入（DemoEngine.historySeed 種子用），同樣過 merge／修剪／抽稀 */
    suspend fun addSamples(instId: String, samples: List<BalanceSample>): Unit = mutex.withLock {
        if (samples.isEmpty()) return
        val existing = historyOnceLocked(instId)
        val next = mergeAndTrim(existing, samples, System.currentTimeMillis())
        persistLocked(instId, next)
    }

    /** 刪除實例時清走該實例歷史 */
    suspend fun clearHistory(instId: String): Unit = mutex.withLock {
        context.dataStore.edit { it.remove(keyFor(instId)) }
    }

    /** wipeAll：清走所有實例嘅歷史 key */
    suspend fun clearAll(): Unit = mutex.withLock {
        context.dataStore.edit { p ->
            for (k in p.asMap().keys) {
                if (k.name.startsWith(KEY_PREFIX)) p.remove(k)
            }
        }
    }

    /* ---------------- 合併＋修剪＋抽稀（純邏輯，內部可測） ---------------- */

    /**
     * existing ＋ added → 合併排序（舊→新）→ 同分鐘合併成最後一個 →
     * 修剪 30 日以前 → 超過 MAX_POINTS 就每第 N 個保留（最新一點永遠留）。
     */
    internal fun mergeAndTrim(
        existing: List<BalanceSample>,
        added: List<BalanceSample>,
        nowMs: Long,
    ): List<BalanceSample> {
        val all = (existing + added)
            .filter { it.t >= 0 && it.v.isFinite() }
            .sortedBy { it.t }
        // 同一分鐘（t / 60000 同 key）合併成最後一個
        val perMinute = LinkedHashMap<Long, BalanceSample>()
        for (s in all) perMinute[s.t / 60_000L] = s
        val merged = perMinute.values.toList()
        // 30 日以前刪走
        val cutoff = nowMs - RETENTION_MS
        val kept = merged.filter { it.t >= cutoff }
        if (kept.size <= MAX_POINTS) return kept
        // 抽稀：每第 N 個保留，保證 ≤ MAX_POINTS；最新一點永遠留低
        val n = ceil(kept.size.toDouble() / MAX_POINTS).toInt().coerceAtLeast(2)
        val thinned = kept.filterIndexed { i, _ -> i % n == 0 }
        val last = kept.last()
        return if (thinned.lastOrNull()?.t == last.t) thinned else thinned + last
    }

    /* ---------------- 內部 ---------------- */

    private suspend fun historyOnceLocked(instId: String): List<BalanceSample> =
        historyFlow(instId).first()

    private suspend fun persistLocked(instId: String, samples: List<BalanceSample>) {
        context.dataStore.edit { p ->
            if (samples.isEmpty()) {
                p.remove(keyFor(instId))
            } else {
                p[keyFor(instId)] = json.encodeToString(samples.map { SampleDto(it.t, it.v) })
            }
        }
    }

    private fun parse(raw: String?): List<BalanceSample> {
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            json.decodeFromString<List<SampleDto>>(raw).map { BalanceSample(it.t, it.v) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun keyFor(instId: String) = stringPreferencesKey(KEY_PREFIX + instId)

    @Serializable
    private data class SampleDto(val t: Long = 0, val v: Double = 0.0)

    companion object {
        private const val KEY_PREFIX = "balance_history.v1."

        /** 每實例最多 1000 點 */
        const val MAX_POINTS = 1000

        /** 30 日保留期 */
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        /** 同分鐘取樣合併嘅分桶（epoch millis / 60000） */
        const val MINUTE_BUCKET_MS = 60_000L
    }
}
