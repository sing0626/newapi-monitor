package com.newapimonitor.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newapimonitor.data.api.ApiException
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.demo.DemoEngine
import com.newapimonitor.domain.AppError
import com.newapimonitor.domain.ErrorKind
import com.newapimonitor.domain.ErrorStats
import com.newapimonitor.domain.LogEntry
import com.newapimonitor.domain.Quota
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 調用日誌頁（移植 js renderLogs / loadLogsPage）：
 *  - 分頁（每頁 20）、按模型篩選、今日／7 日切換
 *  - 切換實例／demo → 重置篩選、分頁、模型列表（舊實例嘅篩選唔可以漏落新實例）
 *  - in-flight 請求喺新查詢時 cancel（viewModelScope + Job）
 */
class LogsViewModel(private val repo: NewApiRepository) : ViewModel() {

    data class UiState(
        val range: String = "today", // today | 7d
        val model: String = "",
        val page: Int = 1,
        val pageSize: Int = 20,
        val rows: List<LogEntry> = emptyList(),
        val total: Long? = null,
        val hasMore: Boolean = false,
        val loading: Boolean = true,
        val error: AppError? = null,
        val noAccessToken: Boolean = false,
        val models: List<String> = emptyList(),
        val qpu: Double = Quota.DEFAULT_QPU,
        /**
         * 今日 type=5 錯誤行（「錯誤分析」卡用，同範圍／模型篩選無關，永遠今日窗口）：
         * null = 未載入／抓取失敗 → 卡片靜默收起；非 null（可空列表）→ 正常渲染。
         */
        val errorRows: List<LogEntry>? = null,
    )

    // Document picker pauses the activity and temporarily uncomposes the locked screen.
    // Keep only this explicit export snapshot until its result is delivered.
    private var pendingExport: String? = null
    fun prepareExport(payload: String) { pendingExport = payload }
    fun takeExport(): String? = pendingExport.also { pendingExport = null }

    private var loadJob: Job? = null
    private var loadModelsJob: Job? = null
    private var errorJob: Job? = null

    /** 目前數據來源 key（"demo" 或實例 id）：key 冇變就唔好重置 — 同來源嘅資料更新（如 persistAuthStyles 寫回）唔可以清走用戶睇緊嘅篩選 */
    private var lastSourceKey: String? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.store.ready.filter { it }.first()
            combine(repo.store.settings, repo.store.activeInstance) { s, inst -> Pair(s.demo, inst) }
                .distinctUntilChanged()
                .collect { (demo, inst) ->
                    val key = if (demo) "demo" else (inst?.id ?: "none")
                    if (key == lastSourceKey) return@collect
                    lastSourceKey = key
                    // 數據來源切換 → 完全重置（篩選/分頁/緩存）
                    _state.value = UiState()
                    load()
                    loadModels(demo, inst)
                }
        }
    }

    fun setRange(range: String) {
        if (_state.value.range == range) return
        _state.update { it.copy(range = range, page = 1) }
        load()
    }

    fun setModel(model: String) {
        if (_state.value.model == model) return
        _state.update { it.copy(model = model, page = 1) }
        load()
    }

    fun prevPage() {
        val p = _state.value.page
        if (p > 1) {
            _state.update { it.copy(page = p - 1) }
            load()
        }
    }

    fun nextPage() {
        if (_state.value.hasMore) {
            _state.update { it.copy(page = _state.value.page + 1) }
            load()
        }
    }

    fun retry() = load()

    private fun loadModels(demo: Boolean, inst: com.newapimonitor.domain.Instance?) {
        loadModelsJob?.cancel()
        loadModelsJob = viewModelScope.launch {
            val key = if (demo) "demo" else (inst?.id ?: return@launch)
            val models = if (demo) {
                DemoEngine.start()
                DemoEngine.distinctModels()
            } else {
                repo.modelsForFilter(inst)
            }
            // 慢請求完成前來源又換咗 → 舊實例結果作廢，唔可以寫入新實例狀態
            if (key != lastSourceKey) return@launch
            _state.update { it.copy(models = models) }
        }
    }

    /**
     * 今日錯誤行（type=5）平行輕量抓取（最多 2×100 行）：
     * 同主列表查詢並行（獨立 Job，分頁／篩選重查唔會打斷佢自己嘅重試），
     * 失敗靜默（errorRows 保持 null → 卡片收起，唔好為統計卡加錯誤 banner）；
     * 舊實例慢回應喺來源切換後作廢（同 loadModels 口徑）。
     */
    private fun loadErrors() {
        errorJob?.cancel()
        errorJob = viewModelScope.launch {
            val keyAtStart = lastSourceKey
            val inst = repo.store.activeInstance.value
            val demo = repo.store.settings.value.demo
            try {
                val rows = if (demo) {
                    DemoEngine.start()
                    DemoEngine.todayLogs().filter { it.type == ErrorStats.LOG_TYPE_ERROR }
                } else {
                    if (inst == null || inst.accessToken.isBlank()) return@launch
                    repo.todayErrorLogs(inst)
                }
                if (keyAtStart != lastSourceKey) return@launch
                _state.update { it.copy(errorRows = rows) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 抓錯誤行失敗 → 卡片靜默收起
            }
        }
    }

    private fun load() {
        loadJob?.cancel()
        val st = _state.value
        val inst = repo.store.activeInstance.value
        val demo = repo.store.settings.value.demo
        _state.update { it.copy(loading = true, error = null, noAccessToken = false, rows = emptyList(), total = null, hasMore = false) }
        loadErrors() // 錯誤分析卡：同主列表查詢並行嘅輕量抓取（永遠今日窗口）
        loadJob = viewModelScope.launch {
            try {
                if (demo) {
                    DemoEngine.start()
                    delay(150) // 演示模式保留 shimmer 一瞬（js 120ms 同款）
                    val res = DemoEngine.queryLogs(st.range, st.model, st.page, st.pageSize)
                    _state.update {
                        it.copy(
                            loading = false,
                            rows = res.rows,
                            total = res.total.toLong(),
                            hasMore = res.rows.size >= st.pageSize,
                            qpu = DemoEngine.QPU,
                        )
                    }
                } else {
                    if (inst == null || inst.accessToken.isBlank()) {
                        _state.update { it.copy(loading = false, noAccessToken = true, rows = emptyList(), total = null, hasMore = false) }
                        return@launch
                    }
                    val startTs = if (st.range == "today") {
                        com.newapimonitor.util.Fmt.todayStartTs()
                    } else {
                        System.currentTimeMillis() / 1000 - 7 * 86400L
                    }
                    val endTs = System.currentTimeMillis() / 1000
                    val page = repo.queryLogsPage(inst, st.page, st.pageSize, startTs, endTs, st.model)
                    _state.update {
                        it.copy(
                            loading = false,
                            rows = page.rows,
                            total = page.total,
                            hasMore = page.rawCount >= st.pageSize,
                            // 日誌頁同儀表板用同一個 qpu（必須係同一來源嘅快照，切來源後用實例設定 fallback）
                            qpu = repo.snapshotForSource(demo, inst)?.qpu ?: inst.quotaPerUnit ?: Quota.DEFAULT_QPU,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.toAppError()) }
            } catch (e: Exception) {
                // header 構建（token 含控制字符）等非 ApiException 都唔可以炸冧 App（儀表板同款 catch-all）
                _state.update {
                    it.copy(loading = false, error = AppError(ErrorKind.API, e.message ?: "未知錯誤"))
                }
            }
        }
    }
}
