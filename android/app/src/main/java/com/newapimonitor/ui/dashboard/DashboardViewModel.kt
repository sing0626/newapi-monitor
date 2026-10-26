package com.newapimonitor.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newapimonitor.data.api.ApiException
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.data.store.AlertSettingsStore
import com.newapimonitor.data.store.DashboardLayoutStore
import com.newapimonitor.data.store.HistoryStore
import com.newapimonitor.demo.DemoEngine
import com.newapimonitor.domain.AppError
import com.newapimonitor.domain.DashboardSnapshot
import com.newapimonitor.domain.ErrorKind
import com.newapimonitor.domain.LogEntry
import com.newapimonitor.domain.LogMath
import com.newapimonitor.domain.ModelCostStats
import com.newapimonitor.domain.ModelCostStats.ModelCost
import com.newapimonitor.domain.Quota
import com.newapimonitor.domain.SiteNotice
import com.newapimonitor.widget.WidgetSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 儀表板輪詢（移植 js App 輪詢主循環）：
 *  - ViewModel scope；ON_STOP / 離開畫面 → onPause()（cancel in-flight OkHttp call）
 *  - ON_START / 返嚟 → onResume()（即刻刷新 + 重啟）
 *  - 切換數據來源（demo ↔ 實例 ↔ 換實例／改實例資料）→ 重置全部畫面狀態（樣本、流水去重、快照）
 */
class DashboardViewModel(
    private val repo: NewApiRepository,
    /** 月度預算來源（AlertSettingsStore.monthlyBudgetUsd）；跟 SettingsViewModel 同一個 store 樣式 */
    private val alertStore: AlertSettingsStore = AlertSettingsStore(repo.store.appContext),
    /** 儀表板卡片顯示偏好（收埋咗嘅卡 id）；跟 SettingsViewModel 各自 new 一個實例，DataStore 即時同步 */
    private val layoutStore: DashboardLayoutStore = DashboardLayoutStore(repo.store.appContext),
) : ViewModel() {

    data class Sample(val t: Long, val v: Double)
    data class FeedItem(val entry: LogEntry, val isNew: Boolean)

    /** 多實例總覽一行：現用實例用即時快照值（live），其餘用 HistoryStore 最後已知取樣 */
    data class OverviewEntry(
        val key: String,
        val name: String,
        val active: Boolean,
        val isDemo: Boolean,
        val balanceUsd: Double?,
        val sampleT: Long?,
        val live: Boolean,
    )

    /** 歷史卡時間範圍 */
    object HistoryRange {
        const val SESSION = "session"
        const val H24 = "24h"
        const val D7 = "7d"
        const val D30 = "30d"
    }

    data class UiState(
        val name: String = "—",
        val sourceKey: String = "",
        val display: DashboardSnapshot? = null,
        val latest: DashboardSnapshot? = null,
        val samples: List<Sample> = emptyList(),
        val feed: List<FeedItem> = emptyList(),
        val loadingFirst: Boolean = true,
        val intervalMs: Long = 15_000L,
        val nextPollAtMs: Long = 0L,
        val paused: Boolean = false,
        /** 下拉刷新進行中（pollOnce 完成後歸位；唔會同倒數環打架） */
        val refreshing: Boolean = false,
        /** 持久化餘額歷史（舊→新；HistoryStore historyFlow 收集） */
        val history: List<Sample> = emptyList(),
        /** 歷史卡時間範圍（HistoryRange 常量） */
        val historyRange: String = HistoryRange.SESSION,
        /** 多實例總覽（< 2 個數據源時 UI 唔顯示） */
        val overview: List<OverviewEntry> = emptyList(),
        /** 月度預算（AlertSettingsStore.monthlyBudgetUsd；null = 未設定 → 預算卡出引導） */
        val budgetUsd: Double? = null,
        /** 本月日誌（模型性價比用；demo 即時重算，真實 5 分鐘節流重抓） */
        val monthLogs: List<LogEntry> = emptyList(),
        /** 本月日誌窗口撞 maxPages 封頂 → 性價比卡標「（約）」 */
        val monthTruncated: Boolean = false,
        /** 模型性價比（VM 用 monthLogs + 同源快照 qpu 計好） */
        val modelCosts: List<ModelCost> = emptyList(),
        /** 本月日誌成功攞過至少一次（未攞到／失敗又冇舊數 → 性價比卡成張收起） */
        val monthDataReady: Boolean = false,
        /** 站點公告（GET /api/notice，10 分鐘節流）；null = 未攞到／攞取失敗 → 公告卡收起 */
        val notice: SiteNotice? = null,
    )

    private val seenKeys = LinkedHashSet<String>()
    private var loopJob: Job? = null
    private var pollJob: Job? = null
    private var historyJob: Job? = null
    private var monthJob: Job? = null
    private val historyStore = HistoryStore(repo.store.appContext)

    /** 公告抓取節流（同一來源 10 分鐘一次；/api/notice 公開端點，keyOnly 都照攞） */
    private var noticeSourceKey: String? = null
    private var noticeFetchedAtMs = 0L

    /** 真實模式本月日誌重抓節流（同 repo 月份窗口快取 TTL 一致） */
    private val monthRefetchMs = 5 * 60_000L

    @Volatile
    private var monthFetchedAtMs = 0L

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 收埋咗嘅儀表板卡 id（設定頁改 → 即時跟；balance hero 永遠顯示，sanitize 已剔走） */
    val hiddenCards: StateFlow<Set<String>> = layoutStore.hiddenCardIds
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        // 月度預算：載入一次再持續跟蹤（設定頁改完即刻反映）
        viewModelScope.launch {
            alertStore.loadOnce()
            alertStore.settings.collect { s ->
                _state.update { it.copy(budgetUsd = s.monthlyBudgetUsd) }
            }
        }
        viewModelScope.launch {
            repo.store.ready.filter { it }.first()
            combine(repo.store.settings, repo.store.activeInstance) { s, inst ->
                Triple(s.demo, inst, s.intervalSec)
            }
                .distinctUntilChanged()
                .collect { (demo, inst, _) ->
                    val key = if (demo) "demo" else (inst?.id ?: "none")
                    val name = if (demo) "演示實例" else (inst?.name ?: "未設定實例")
                    if (key != _state.value.sourceKey || name != _state.value.name) {
                        // 數據來源切換 → 清晒 demo 快照／舊實例狀態（本月日誌一併清，等第一輪 poll 重抓）
                        seenKeys.clear()
                        monthJob?.cancel()
                        monthJob = null
                        monthFetchedAtMs = 0L
                        noticeSourceKey = null
                        noticeFetchedAtMs = 0L
                        _state.value = UiState(
                            sourceKey = key,
                            name = name,
                            paused = _state.value.paused,
                            budgetUsd = alertStore.settings.value.monthlyBudgetUsd,
                        )
                        collectHistory(key)
                        refreshOverview(key)
                        startLoop(immediate = true)
                    }
                }
        }
    }

    /* ---------------- 歷史 / 總覽 ---------------- */

    fun setHistoryRange(r: String) {
        _state.update { it.copy(historyRange = r) }
    }

    /** 收集現數據來源嘅持久化歷史（demo 實例先 seed 一次種子） */
    private fun collectHistory(key: String) {
        historyJob?.cancel()
        historyJob = null
        if (key == "none") {
            _state.update { it.copy(history = emptyList()) }
            return
        }
        historyJob = viewModelScope.launch {
            if (key == "demo" && historyStore.historyOnce("demo").isEmpty()) {
                historyStore.addSamples("demo", DemoEngine.historySeed("demo"))
            }
            historyStore.historyFlow(key).collect { list ->
                _state.update { it.copy(history = list.map { s -> Sample(s.t, s.v) }) }
            }
        }
    }

    /** 多實例總覽：現用來源用即時快照值，其餘用最後已知取樣 */
    private suspend fun refreshOverview(activeKey: String) {
        val demoOn = repo.store.settings.value.demo
        val entries = ArrayList<OverviewEntry>()
        for (inst in repo.store.instances.value) {
            val active = !demoOn && inst.id == activeKey
            val live = _state.value.latest?.takeIf { active && it.ok && it.balanceUsd != null }
            if (live != null) {
                entries.add(OverviewEntry(inst.id, inst.name, true, false, live.balanceUsd, null, true))
            } else {
                val last = historyStore.historyOnce(inst.id).lastOrNull()
                entries.add(OverviewEntry(inst.id, inst.name, active, false, last?.v, last?.t, false))
            }
        }
        val demoActive = demoOn || activeKey == "demo"
        if (demoOn || repo.store.instances.value.isNotEmpty()) {
            val live = _state.value.latest?.takeIf { demoActive && it.ok && it.balanceUsd != null }
            if (live != null) {
                entries.add(OverviewEntry("demo", "演示模式", demoActive, true, live.balanceUsd, null, true))
            } else {
                val last = historyStore.historyOnce("demo").lastOrNull()
                entries.add(OverviewEntry("demo", "演示模式", demoActive, true, last?.v, last?.t, false))
            }
        }
        _state.update { it.copy(overview = entries) }
    }

    /** 總覽卡撳行 → 切換實例／入演示模式（同頂欄 dropdown 同一邏輯） */
    fun switchSource(key: String) {
        if (key == "demo") {
            setDemo(true)
        } else {
            viewModelScope.launch {
                if (repo.store.settings.value.demo) repo.store.setDemo(false)
                repo.store.setActive(key)
            }
        }
    }

    /* ---------------- lifecycle 接口 ---------------- */

    fun onResume() {
        if (!_state.value.paused) return
        _state.update { it.copy(paused = false) }
        startLoop(immediate = true)
    }

    fun onPause() {
        if (_state.value.paused) return
        _state.update { it.copy(paused = true, nextPollAtMs = 0, refreshing = false) }
        loopJob?.cancel()
        pollJob?.cancel()
        monthJob?.cancel()
        loopJob = null
        pollJob = null
        monthJob = null
    }

    /**
     * 下拉刷新／錯誤卡「重試」：即刻打一輪。
     * paused 時唔動作（pull-to-refresh 觸發唔到，倒數環暫停邏輯唔會被打架）。
     */
    fun refreshNow() {
        if (_state.value.paused) return
        _state.update { it.copy(refreshing = true) }
        startLoop(immediate = true)
    }

    fun setDemo(v: Boolean) {
        viewModelScope.launch { repo.store.setDemo(v) }
    }

    /* ---------------- 輪詢循環 ---------------- */

    private fun currentIntervalMs(): Long =
        if (_state.value.sourceKey == "demo") {
            DemoEngine.DEMO_TICK_MS
        } else {
            maxOf(5_000L, repo.store.settings.value.intervalSec.toLong() * 1_000L)
        }

    private fun startLoop(immediate: Boolean) {
        if (_state.value.paused) return
        loopJob?.cancel()
        pollJob?.cancel()
        loopJob = viewModelScope.launch {
            var delayMs = if (immediate) 0L else currentIntervalMs()
            while (isActive) {
                val iv = currentIntervalMs()
                _state.update { it.copy(intervalMs = iv, nextPollAtMs = System.currentTimeMillis() + delayMs) }
                pollJob = launch { pollOnce() }
                pollJob?.join()
                if (!isActive) break
                delayMs = currentIntervalMs()
                if (delayMs > 0) delay(delayMs)
            }
        }
    }

    private suspend fun pollOnce() {
        val snap = try {
            repo.buildSnapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = if (e is ApiException) {
                e.toAppError()
            } else {
                AppError(ErrorKind.API, e.message ?: "未知錯誤")
            }
            DashboardSnapshot(
                ok = false,
                demo = repo.store.settings.value.demo,
                name = repo.store.activeInstance.value?.name ?: _state.value.name,
                errors = mapOf("fatal" to err),
            )
        }
        // 卡片收埋咗就唔使做對應嘅數據工作（重開卡下一輪 poll 自動補返）
        val feedHidden = "feed" in hiddenCards.value
        val costHidden = "cost" in hiddenCards.value
        _state.update { st ->
            val samples = if (snap.ok && snap.balanceUsd != null && snap.balanceUsd.isFinite()) {
                (st.samples + Sample(System.currentTimeMillis(), snap.balanceUsd)).takeLast(240)
            } else {
                st.samples
            }
            // 失敗但有舊數據 → 照顯示舊卡 + banner（js 同款：唔清 dash-body）
            val display = when {
                snap.ok -> snap
                st.display?.ok == true -> st.display
                else -> snap
            }
            val feed = if (snap.ok && !snap.keyOnly && !feedHidden) buildFeed(st.feed, snap.recentLogs) else st.feed
            st.copy(
                display = display,
                latest = snap,
                samples = samples,
                feed = feed,
                loadingFirst = false,
                refreshing = false, // 下拉刷新歸位（成功／失敗都算完成）
            )
        }
        // 輪詢成功 → 同步主畫面小工具（demo／真實都覆蓋；冇加小工具時 push 內部直接 no-op）
        if (snap.ok) {
            WidgetSync.pushFromSnapshot(repo.store.appContext, snap)
            maybeRefreshNotice(snap)
            // 持久化餘額取樣（HistoryStore：30 日保留 / 1000 點上限）
            val bal = snap.balanceUsd
            if (bal != null && bal.isFinite() && _state.value.sourceKey != "none") {
                historyStore.addSample(_state.value.sourceKey, System.currentTimeMillis(), bal)
            }
            // 本月日誌重抓（模型性價比卡；卡收埋咗就慳返）
            if (!costHidden) maybeRefreshMonthData()
        }
        // 總覽卡刷新（成功／失敗都刷：失敗都照出「最後已知」）
        refreshOverview(_state.value.sourceKey)
    }

    /** 流水 slide-in 去重（js seenLogKeys：>400 時裁到 300） */
    private fun buildFeed(prev: List<FeedItem>, rows: List<LogEntry>): List<FeedItem> {
        val items = ArrayList<FeedItem>(rows.size)
        for (r in rows) {
            val k = LogMath.logKey(r)
            val isNew = k !in seenKeys
            seenKeys.add(k)
            items.add(FeedItem(r, isNew))
        }
        if (seenKeys.size > 400) {
            val it = seenKeys.iterator()
            while (seenKeys.size > 300) {
                it.next()
                it.remove()
            }
        }
        return items
    }

    /**
     * 站點公告抓取（GET /api/notice，公開端點）：同一來源 10 分鐘節流；
     * demo 用 DemoEngine；失敗靜默（公告卡收起，唔會為公告加錯誤 banner）。
     */
    private fun maybeRefreshNotice(snap: DashboardSnapshot) {
        val srcKey = _state.value.sourceKey
        if (srcKey == "none") return
        if (snap.demo) {
            if (_state.value.notice == null) {
                _state.update { it.copy(notice = DemoEngine.notice()) }
            }
            return
        }
        val now = System.currentTimeMillis()
        if (srcKey == noticeSourceKey && now - noticeFetchedAtMs < 10 * 60_000L) return
        val inst = repo.store.activeInstance.value ?: return
        noticeSourceKey = srcKey
        noticeFetchedAtMs = now
        viewModelScope.launch {
            try {
                val n = repo.fetchNotice(inst)
                if (_state.value.sourceKey == srcKey) _state.update { it.copy(notice = n) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /* ---------------- 本月日誌（模型性價比卡） ---------------- */

    /**
     * 本月日誌重抓（模型性價比）：
     *  - demo：每輪 poll 都重算（DemoEngine.monthLogs 純記憶體，唔使節流）
     *  - 真實：repo.monthLogsPage（/api/log/self 本月窗口 3×100）5 分鐘節流，
     *    唔好同快照本身嘅月份窗口抓取每輪搶頻寬
     *  - 失敗靜默：有舊數照顯示舊數；冇舊數 → monthDataReady=false，成張卡收起，絕不炸
     */
    private fun maybeRefreshMonthData() {
        val key = _state.value.sourceKey
        if (key == "none") return
        val demo = repo.store.settings.value.demo
        if (!demo) {
            val inst = repo.store.activeInstance.value
            if (inst == null || inst.accessToken.isBlank()) return
            if (System.currentTimeMillis() - monthFetchedAtMs < monthRefetchMs) return
        }
        monthJob?.cancel()
        monthJob = viewModelScope.launch {
            try {
                if (_state.value.sourceKey != key) return@launch // 切走咗數據來源 → 丟棄
                if (demo) {
                    DemoEngine.start()
                    publishMonthData(key, LogMath.filterConsume(DemoEngine.monthLogs()), false)
                } else {
                    val inst = repo.store.activeInstance.value ?: return@launch
                    val page = repo.monthLogsPage(inst)
                    publishMonthData(key, page.rows, page.truncated)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 失敗都節流（唔好每輪 poll 都撞）；冇舊數 → 收起卡片
                monthFetchedAtMs = System.currentTimeMillis()
                if (_state.value.sourceKey == key && _state.value.monthLogs.isEmpty()) {
                    _state.update {
                        it.copy(monthDataReady = false, modelCosts = emptyList(), monthTruncated = false)
                    }
                }
            }
        }
    }

    /** 發布本月數據＋用同源快照嘅 qpu 計模型性價比（同儀表板金額同一換算） */
    private fun publishMonthData(key: String, rows: List<LogEntry>, truncated: Boolean) {
        if (_state.value.sourceKey != key) return
        val inst = repo.store.activeInstance.value
        val qpu = if (key == "demo") {
            DemoEngine.QPU
        } else {
            repo.snapshotForSource(demo = false, inst)?.qpu
                ?: inst?.quotaPerUnit?.takeIf { it.isFinite() && it > 0 }
                ?: Quota.DEFAULT_QPU
        }
        monthFetchedAtMs = System.currentTimeMillis()
        _state.update {
            it.copy(
                monthLogs = rows,
                monthTruncated = truncated,
                modelCosts = ModelCostStats.compute(rows, qpu),
                monthDataReady = true,
            )
        }
    }
}
