package com.newapimonitor.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.demo.DemoEngine
import com.newapimonitor.domain.DataFlowSelf
import com.newapimonitor.domain.Heatmap
import com.newapimonitor.domain.LogEntry
import com.newapimonitor.domain.LogMath
import com.newapimonitor.domain.LogSelfStat
import com.newapimonitor.domain.ModelCostStats
import com.newapimonitor.domain.ModelCostStats.ModelCost
import com.newapimonitor.domain.Quota
import com.newapimonitor.domain.SiteNotice
import com.newapimonitor.domain.SpendCalendar
import com.newapimonitor.domain.SpendStats
import com.newapimonitor.domain.TokenSpendStats
import com.newapimonitor.domain.TopUpInfo
import com.newapimonitor.domain.TopUpRecord
import com.newapimonitor.domain.UserSubscription
import com.newapimonitor.util.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * 月度報告畫面狀態：
 *  - daily 稀疏（只含 usd > 0 嘅日）→ 空列表 = 本月暫無消費（日曆／摘要出空狀態）
 *  - lastMonthFetched=false（端點失敗／demo）→ 對比行顯示「上月無數據」
 *  - logsReady=false（日誌窗口抓取失敗）→ 統計／燒錢令牌卡成張收起，唔會炸
 */
data class ReportState(
    val loading: Boolean = true,
    val sourceKey: String = "",
    val noInstance: Boolean = false,
    /** 報告年月（載入當刻嘅當月） */
    val year: Int = 0,
    val month: Int = 0,
    /** 載入當刻「現在」— 日曆今日／未來格用 */
    val nowMs: Long = 0L,
    /** 本月每日消費（稀疏；當地日 0 點 epoch 秒 + USD） */
    val daily: List<SpendCalendar.DailyUsd> = emptyList(),
    val totalUsd: Double = 0.0,
    val lastMonthFetched: Boolean = false,
    val lastTotalUsd: Double = 0.0,
    val qpu: Double = Quota.DEFAULT_QPU,
    /** 本月日誌成功攞過至少一次（false = 統計／燒錢令牌卡收起） */
    val logsReady: Boolean = false,
    /** 日誌窗口撞 maxPages 封頂 → 顯示「最多 1000 行」提示 */
    val logsTruncated: Boolean = false,
    val calls: Int = 0,
    val totalTokens: Long = 0L,
    /** 高峰時段（0..23；-1 = 冇數據） */
    val peakHour: Int = -1,
    val priciest: ModelCost? = null,
    val cheapest: ModelCost? = null,
    val topTokens: List<TokenSpendStats.TokenSpend> = emptyList(),
    /** 實時個人統計（/api/log/self/stat）；null = 端點失敗／不支援／key-only → 成張卡收起，唔會出 0 */
    val stat: LogSelfStat? = null,
    /** 官方多維度流量分佈（/api/data/flow/self，窗口自動夾 30 日）；null = 失敗／不支援 → 卡收起 */
    val flow: DataFlowSelf? = null,
    /** 流量窗口由本月起點夾到官方 30 日上限 → 標「最近 30 日」 */
    val flowClamped: Boolean = false,
)

/**
 * 月度報告 ViewModel（同 DashboardViewModel 同一套來源切換／生命周期慣例）：
 *  - demo：DemoEngine.monthLogs() 本地聚日（demo 冇上月數據 → lastMonthFetched=false）
 *  - 真實：repo.monthDailyTotals(inst)（/api/data/self 兩個窗口 + 月誌窗口）
 *  - ON_STOP / 切走 tab → pause()（cancel in-flight）；ON_START → resume() 重抓（跨日月份／今日標記都會更新）
 */
class ReportViewModel(private val repo: NewApiRepository) : ViewModel() {

    private val _state = MutableStateFlow(ReportState())
    val state: StateFlow<ReportState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var sourceKey: String? = null

    init {
        viewModelScope.launch {
            repo.store.ready.filter { it }.first()
            combine(repo.store.settings, repo.store.activeInstance) { s, inst ->
                if (s.demo) "demo" else (inst?.id ?: "none")
            }
                .distinctUntilChanged()
                .collectLatest { key ->
                    sourceKey = key
                    load(key)
                }
        }
    }

    /** ON_START：重新抓一輪 */
    fun resume() {
        sourceKey?.let { load(it) }
    }

    /** ON_STOP / 離開畫面：cancel in-flight 請求 */
    fun pause() {
        loadJob?.cancel()
        loadJob = null
    }

    private fun load(key: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val nowMs = System.currentTimeMillis()
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            _state.update {
                ReportState(
                    loading = true,
                    sourceKey = key,
                    year = today.year,
                    month = today.monthValue,
                    nowMs = nowMs,
                )
            }
            if (key == "none") {
                _state.update { it.copy(loading = false, noInstance = true) }
                return@launch
            }
            if (key == "demo") {
                DemoEngine.start()
                val logs = LogMath.filterConsume(DemoEngine.monthLogs())
                publish(
                    key = key,
                    daily = SpendCalendar.dailyTotals(demoPoints(logs), DemoEngine.QPU),
                    lastMonth = emptyList(),
                    lastMonthFetched = false,
                    qpu = DemoEngine.QPU,
                    logs = logs,
                    logsTruncated = false,
                )
                _state.update {
                    it.copy(
                        stat = DemoEngine.logSelfStat(),
                        flow = DemoEngine.dataFlowSelf(),
                        flowClamped = false,
                    )
                }
            } else {
                val inst = repo.store.activeInstance.value
                if (inst == null || !inst.hasAnyToken) {
                    _state.update { it.copy(loading = false, noInstance = true) }
                    return@launch
                }
                try {
                    val t = repo.monthDailyTotals(inst)
                    if (sourceKey != key) return@launch // 切走咗數據來源 → 丟棄
                    publish(key, t.thisMonth, t.lastMonth, t.lastMonthFetched, t.qpu, t.monthLogs, t.logsTruncated)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 抓取失敗 → 受影響卡面全部收起（空狀態），絕不炸
                    if (sourceKey == key) _state.update { it.copy(loading = false) }
                }
                // 實時統計＋流量分佈：獨立失敗只收起對應卡，唔影響月報本身；
                // key-only（冇 Access Token）唔打管理 API，卡面保持收起（唔會出 0）
                if (inst.accessToken.isNotBlank()) {
                    val startTs = Fmt.monthStartTs()
                    val nowSec = System.currentTimeMillis() / 1000
                    val clamped = nowSec - startTs > 30 * 86400L // 官方 /api/data 上限 30 日
                    launch {
                        try {
                            val s = repo.fetchLogSelfStat(inst)
                            if (sourceKey == key) _state.update { it.copy(stat = s) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                    launch {
                        try {
                            val f = repo.fetchDataFlowSelf(inst, startTs, nowSec)
                            if (sourceKey == key) _state.update { it.copy(flow = f, flowClamped = clamped) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }

    private fun demoPoints(logs: List<LogEntry>): List<SpendStats.Point> =
        logs.mapNotNull { r -> r.quota?.let { SpendStats.Point(r.createdAt, it) } }

    private fun publish(
        key: String,
        daily: List<SpendCalendar.DailyUsd>,
        lastMonth: List<SpendCalendar.DailyUsd>,
        lastMonthFetched: Boolean,
        qpu: Double,
        logs: List<LogEntry>,
        logsTruncated: Boolean,
    ) {
        if (sourceKey != key) return
        val hourly = Heatmap.compute(logs, qpu).byHour
        val peak = hourly.indices.maxByOrNull { hourly[it] }?.takeIf { hourly[it] > 0 } ?: -1
        val costs = ModelCostStats.compute(logs, qpu)
        _state.update {
            it.copy(
                loading = false,
                noInstance = false,
                daily = daily,
                totalUsd = daily.sumOf { d -> d.usd },
                lastMonthFetched = lastMonthFetched,
                lastTotalUsd = lastMonth.sumOf { d -> d.usd },
                qpu = qpu,
                logsReady = true,
                logsTruncated = logsTruncated,
                calls = logs.size,
                totalTokens = logs.sumOf { r -> r.promptTokens + r.completionTokens },
                peakHour = peak,
                priciest = costs.firstOrNull(),
                cheapest = costs.lastOrNull(),
                topTokens = TokenSpendStats.compute(logs, qpu).rows.take(3),
            )
        }
    }
}
