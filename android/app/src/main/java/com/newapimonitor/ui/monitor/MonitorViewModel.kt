package com.newapimonitor.ui.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newapimonitor.alert.*
import com.newapimonitor.data.api.*
import com.newapimonitor.data.monitor.*
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.demo.DemoEngine
import com.newapimonitor.domain.*
import com.newapimonitor.ui.speedtest.PingHistoryItem
import com.newapimonitor.ui.speedtest.SpeedTestHistoryStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.time.ZoneId

data class Drilldown(val name: String, val token: Boolean, val page: Int = 1, val rows: List<LogEntry> = emptyList(),
    val loading: Boolean = false, val more: Boolean = false, val failure: String? = null)
data class MonitorUiState(
    val refreshing: Boolean = false, val error: String? = null,
    val reportLoading: Boolean = false, val report: PeriodReport? = null,
    val drilldown: Drilldown? = null,
    val crossReport: CrossReport? = null,
    val modelCatalog: Map<String, List<String>> = emptyMap(), val catalogFailures: Map<String, String> = emptyMap(), val catalogLoading: Boolean = false,
    val probing: Boolean = false, val probeProgress: String = "", val results: List<StreamProbeResult> = emptyList(),
    val probeHistory: List<Pair<String, PingHistoryItem>> = emptyList(),
)

class MonitorViewModel(private val repo: NewApiRepository) : ViewModel() {
    private val service = MonitorService(repo.store)
    val snapshots = service.cache.snapshots.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val budgets = service.cache.budgets.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val alertHistory = AlertHistoryStore(repo.store.appContext)
    val alerts = alertHistory.records.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _state = MutableStateFlow(MonitorUiState())
    val state = _state.asStateFlow()
    private var loop: Job? = null
    private var refreshJob: Job? = null
    private var reportJob: Job? = null
    private var detailJob: Job? = null
    private var probeJob: Job? = null
    private var catalogJob: Job? = null
    private var catalogRequest = 0L
    private var visible = false
    private var section = 0
    private var generation = 0L
    private var reportRequest = 0L
    private var probeRequest = 0L

    init {
        viewModelScope.launch {
            repo.store.ready.filter { it }.first()
            combine(repo.store.instances, repo.store.settings) { instances, settings -> instances to settings }
                .distinctUntilChanged().collectLatest {
                    generation++
                    stopJobs()
                    _state.value = MonitorUiState()
                    if (visible) resume()
                }
        }
    }
    fun resume() {
        visible = true
        if (section == 0) startLoop()
        loadHistory()
    }
    fun pause() { visible = false; generation++; stopJobs(); _state.update { it.copy(refreshing = false, reportLoading = false, catalogLoading = false, probing = false, drilldown = it.drilldown?.copy(loading = false)) } }
    fun selectSection(next: Int) {
        section = next
        loop?.cancel(); refreshJob?.cancel()
        if (section == 0 && visible) startLoop()
        if (section != 2) stopProbe()
    }
    private fun stopJobs() { loop?.cancel(); refreshJob?.cancel(); reportJob?.cancel(); detailJob?.cancel(); probeJob?.cancel(); catalogJob?.cancel() }
    private fun startLoop() {
        loop?.cancel()
        loop = viewModelScope.launch { while (isActive) { refresh(); refreshJob?.join(); delay(maxOf(30, repo.store.settings.value.intervalSec) * 1000L) } }
    }
    fun refresh() {
        if (refreshJob?.isActive == true || repo.store.settings.value.demo) return
        val sources = repo.store.instances.value.toList()
        val currentGeneration = generation
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(refreshing = true, error = null) }
            try {
                // Read the persisted cache before refreshing. stateIn's initial empty list can
                // otherwise erase retained balances when the first connection attempt fails.
                val previous = service.cache.snapshots.first()
                val rows = service.refreshAll(sources, previous)
                if (currentGeneration == generation) { MonitorAlerts.evaluate(repo.store, rows); com.newapimonitor.widget.WidgetRefreshWorker.renderCached(repo.store.appContext) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { if (currentGeneration == generation) _state.update { it.copy(error = "更新失敗，請重試") }
            } finally { if (currentGeneration == generation) _state.update { it.copy(refreshing = false) } }
        }
    }
    fun budget(id: String, text: String) {
        val v = text.toDoubleOrNull()
        if (text.isNotBlank() && (v == null || !v.isFinite() || v <= 0)) {
            _state.update { it.copy(error = "預算必須係正數，留空可關閉") }; return
        }
        viewModelScope.launch { service.cache.setBudget(id, v); _state.update { it.copy(error = null) } }
    }
    fun report(start: String, end: String, previousMonth: Boolean = false) {
        val range = try { ReportRange(LocalDate.parse(start), LocalDate.parse(end)).validate() }
            catch (_: Exception) { _state.update { it.copy(error = "請輸入 YYYY-MM-DD 日期，範圍最多 366 日，不能超過今日") }; return }
        val inst = repo.store.activeInstance.value
        val demo = repo.store.settings.value.demo
        if (!demo && (inst == null || inst.accessToken.isBlank())) { _state.update { it.copy(error = "報告需要 Access Token") }; return }
        reportJob?.cancel(); detailJob?.cancel()
        val g = generation
        val request = ++reportRequest
        reportJob = viewModelScope.launch {
            _state.update { it.copy(error = null, reportLoading = true, report = null, crossReport = null, drilldown = null) }
            try {
                val comparison = if (previousMonth) range.previousMonth() else range.previous()
                val result = if (demo) PeriodReport(range, demoSlice(range), demoSlice(comparison), comparison)
                    else withTimeout(90_000) { service.report(inst!!, range, previousMonth) }
                if (generation == g && request == reportRequest) _state.update { it.copy(report = result) }
            } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); if (generation == g && request == reportRequest) _state.update { it.copy(error = "報告載入逾時，請縮短日期範圍或重試") }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { if (generation == g && request == reportRequest) _state.update { it.copy(error = "報告載入失敗，請檢查連線及 Access Token") }
            } finally { if (generation == g && request == reportRequest) _state.update { it.copy(reportLoading = false) } }
        }
    }
    fun reportAcross(ids: Set<String>, start: String, end: String, previousMonth: Boolean) {
        val range = runCatching { ReportRange(LocalDate.parse(start), LocalDate.parse(end)).validate() }.getOrNull()
        if (range == null || ids.isEmpty() && !repo.store.settings.value.demo) {
            _state.update { it.copy(error = "請選擇實例及有效日期，範圍最多 366 日") }; return
        }
        reportJob?.cancel(); detailJob?.cancel()
        val sources = repo.store.instances.value.filter { it.id in ids }
        val demo = repo.store.settings.value.demo
        val g = generation; val request = ++reportRequest
        reportJob = viewModelScope.launch {
            _state.update { it.copy(reportLoading = true, error = null, report = null, crossReport = null, drilldown = null) }
            try {
                val result = if (demo) CrossReport(range, listOf(SourceReport("demo", "演示實例", PeriodReport(range, demoSlice(range),
                    demoSlice(if (previousMonth) range.previousMonth() else range.previous()), if (previousMonth) range.previousMonth() else range.previous()))))
                    else withTimeout(120_000) { service.reportAcross(sources, range, previousMonth) }
                if (g == generation && request == reportRequest) _state.update { it.copy(crossReport = result) }
            } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); if (g == generation) _state.update { it.copy(error = "跨實例報告逾時，請減少實例或日期範圍") }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { if (g == generation) _state.update { it.copy(error = "跨實例報告失敗，請重試") }
            } finally { if (g == generation && request == reportRequest) _state.update { it.copy(reportLoading = false) } }
        }
    }

    fun loadModels(ids: Set<String>) {
        catalogJob?.cancel()
        val request = ++catalogRequest; val g = generation
        val demo = repo.store.settings.value.demo
        val sources = repo.store.instances.value.filter { it.id in ids && it.apiKey.isNotBlank() }
        catalogJob = viewModelScope.launch {
            _state.update { it.copy(catalogLoading = true, modelCatalog = emptyMap(), catalogFailures = emptyMap()) }
            try {
                val catalogs = mutableMapOf<String, List<String>>(); val failures = mutableMapOf<String, String>()
                if (demo) catalogs["demo"] = listOf("demo-fast", "demo-reasoning")
                else for (source in sources) {
                    try { catalogs[source.id] = withTimeout(15_000) { NewApiClient(source.baseUrl, source.accessToken, source.apiKey,
                        source.authStyle, source.authStyleKey).fetchKeyModels() }
                    } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); failures[source.id] = "模型清單逾時"
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { failures[source.id] = "模型清單不可用，可手動輸入" }
                }
                if (g == generation && request == catalogRequest) _state.update { it.copy(modelCatalog = catalogs, catalogFailures = failures) }
            } finally { if (g == generation && request == catalogRequest) _state.update { it.copy(catalogLoading = false) } }
        }
    }

    fun detail(name: String, token: Boolean, page: Int = 1) {
        val report = _state.value.report ?: return
        val inst = repo.store.activeInstance.value
        val demo = repo.store.settings.value.demo
        val g = generation
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            _state.update { it.copy(drilldown = Drilldown(name, token, page, loading = true)) }
            try {
                val rows: List<LogEntry>
                val more: Boolean
                if (demo || name.isEmpty()) {
                    val all = report.current.rows.orEmpty().filter { if (token) it.tokenName.orEmpty() == name else it.modelName.orEmpty() == name }
                    rows = all.drop((page - 1) * 20).take(20); more = all.size > page * 20
                } else {
                    require(inst != null)
                    val c = NewApiClient(inst.baseUrl, inst.accessToken, inst.apiKey, inst.authStyle, inst.authStyleKey)
                    val user = c.fetchUserSelf().asJsonObjectOrEmpty(); c.setUserId(user.longOf("id"))
                    val data = c.fetchLogs(p = page, pageSize = 20, startTs = report.range.startSeconds(),
                        endTs = minOf(report.range.endSeconds(), System.currentTimeMillis() / 1000),
                        modelName = name.takeIf { !token }, tokenName = name.takeIf { token }, type = 2)
                    val raw = ResponseParsing.pickLogItems(data)
                    rows = LogMath.filterConsume(raw.map(ResponseParsing::parseLogEntry)).filter {
                        (if (token) it.tokenName == name else it.modelName == name) && it.createdAt in report.range.startSeconds()..report.range.endSeconds()
                    }
                    more = raw.size >= 20
                }
                if (generation == g) _state.update { it.copy(drilldown = Drilldown(name, token, page, rows, more = more)) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { if (generation == g) _state.update { it.copy(drilldown = Drilldown(name, token, page, failure = "無法載入日誌，請重試")) } }
        }
    }
    fun closeDetail() { detailJob?.cancel(); _state.update { it.copy(drilldown = null) } }

    fun probe(ids: Set<String>, input: String) {
        if (probeJob?.isActive == true) return
        val models = input.lines().map(String::trim).filter(String::isNotEmpty).distinct()
        val demo = repo.store.settings.value.demo
        val sources = if (demo) listOf(Instance(id = "demo", name = "演示實例")) else repo.store.instances.value.filter { it.id in ids && it.apiKey.isNotBlank() }
        if (models.isEmpty() || models.size > 3 || models.any { it.length > 150 } || sources.isEmpty() || sources.size * models.size > 10) {
            _state.update { it.copy(error = "請選擇有 API Key 嘅實例，輸入 1 至 3 個模型；每輪最多 10 次測試") }; return
        }
        val g = generation
        val request = ++probeRequest
        probeJob = viewModelScope.launch {
            _state.update { it.copy(probing = true, results = emptyList(), error = null) }
            try {
                for (source in sources) for (model in models) {
                    ensureActive()
                    _state.update { it.copy(probeProgress = "${source.name} · $model") }
                    val result = if (demo) StreamProbeResult("demo", model, true, 180, 1200, 32, 30.4)
                        else StreamingProbe().run(source, model)
                    if (g != generation || request != probeRequest) return@launch
                    _state.update { it.copy(results = it.results + result) }
                    val record = PingHistoryItem(model, result.totalMs,
                        result.ok, if (result.ok) 200 else 0, result.failure ?: "串流完成", System.currentTimeMillis(),
                        result.firstTokenMs, result.completionTokens, result.tokensPerSecond)
                    val historyStore = SpeedTestHistoryStore(repo.store.appContext)
                    if (demo) historyStore.add("demo", record)
                    else repo.store.withCurrentSource(source) { historyStore.add("instance.${source.id}", record) }
                    loadHistory()
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { _state.update { it.copy(error = "測試失敗，請檢查實例設定") }
            } finally { if (g == generation && request == probeRequest) _state.update { it.copy(probing = false, probeProgress = "") } }
        }
    }
    val preferenceStore = MonitorPreferencesStore(repo.store.appContext)
    fun saveSuite(name: String, input: String) {
        val models = input.lines().map(String::trim).filter(String::isNotBlank).distinct()
        if (name.isBlank() || name.length > 40 || models.isEmpty() || models.size > 3 || models.any { it.length > 150 }) {
            _state.update { it.copy(error = "組合名稱最多 40 字，需有 1 至 3 個有效模型") }; return
        }
        viewModelScope.launch { preferenceStore.update { it.copy(suites = (it.suites.filterNot { s -> s.name == name.trim() } + ProbeSuite(name.trim(), models)).takeLast(20)) } }
    }
    fun stopProbe() { probeRequest++; probeJob?.cancel(); _state.update { it.copy(probing = false, probeProgress = "") } }
    private fun loadHistory() {
        viewModelScope.launch {
            val ids = if (repo.store.settings.value.demo) listOf("demo") else repo.store.instances.value.map { it.id }
            val store = SpeedTestHistoryStore(repo.store.appContext)
            val g = generation
            val rows = ids.flatMap { id -> store.read(if (id == "demo") "demo" else "instance.$id").map { id to it } }.sortedByDescending { it.second.timestamp }.take(100)
            if (g == generation) _state.update { it.copy(probeHistory = rows) }
        }
    }
    private fun demoSlice(range: ReportRange): ReportSlice {
        val rows = (0 until range.days).flatMap { offset ->
            val day = range.start.plusDays(offset)
            listOf("demo-fast", "demo-reasoning").mapIndexed { index, model ->
                LogEntry(type = 2, createdAt = day.atTime(12 + index, 0).atZone(ZoneId.systemDefault()).toEpochSecond(),
                    modelName = model, tokenName = "演示令牌 ${index + 1}", promptTokens = 120, completionTokens = 80,
                    quota = ((day.dayOfYear % 7 + index + 1) * 10_000).toDouble(), useTime = 1.2)
            }
        }
        return ReportSlice(rows.sumOf { it.quota ?: 0.0 } / Quota.DEFAULT_QPU, rows, false, qpu = Quota.DEFAULT_QPU)
    }
}
