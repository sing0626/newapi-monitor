package com.newapimonitor.ui.tokens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newapimonitor.data.api.ApiException
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.demo.DemoEngine
import com.newapimonitor.domain.AppError
import com.newapimonitor.domain.ChannelInfo
import com.newapimonitor.domain.ErrorKind
import com.newapimonitor.domain.Quota
import com.newapimonitor.domain.TokenInfo
import com.newapimonitor.domain.TokenSpendStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * 令牌頁（GET /api/token/?p=1&size=100，Access Token 管理 API）：
 *  - 切換實例／demo → 重置重新拉取（同 LogsViewModel 同款來源 key 隔離）
 *  - 啟用/停用切換、新增／編輯／刪除：成功後重拉列表；失敗出錯誤條（actionError），唔會炸
 *  - 批量管理：多選後逐張順序啟用/停用/刪除（一張一張，溫柔對伺服器）；完成重拉＋(成功, 失敗) 摘要
 *  - seg「令牌 | 渠道」：渠道頁（GET /api/channel/，admin）唯讀監察
 *  - keyOnly（無 Access Token）實例 → noAccessToken 空狀態
 */
class TokensViewModel(private val repo: NewApiRepository) : ViewModel() {

    /** seg：令牌列表 / 渠道監察 */
    object Seg {
        const val TOKENS = "tokens"
        const val CHANNELS = "channels"
    }

    companion object {
        private const val TOKEN_PAGE_SIZE = 100
        /** 分頁拉全量上限：10 頁 × 100 = 1000 張（超過標示 truncated，唔會無限拉） */
        private const val TOKEN_MAX_PAGES = 10
    }

    data class UiState(
        val loading: Boolean = true,
        val tokens: List<TokenInfo> = emptyList(),
        /** 已達載入上限（10 頁 × 100）或伺服器仲有下一頁 */
        val truncated: Boolean = false,
        /** 伺服器端搜尋關鍵字（新版站點 &keyword=；demo 本地過濾） */
        val keyword: String = "",
        val error: AppError? = null,
        val noAccessToken: Boolean = false,
        /** 正在切換狀態嘅令牌 id（按鈕 loading／防重入） */
        val togglingId: Long? = null,
        /** 切換失敗錯誤條文案（snackbar 位置） */
        val actionError: String? = null,
        val qpu: Double = Quota.DEFAULT_QPU,
        val revealedKey: String? = null,
        val revealingId: Long? = null,
        val revealError: String? = null,
        /** 令牌排序策略：default | used_desc | remain_asc | exp_asc */
        val tokenSort: String = "default",
        /** 令牌 | 渠道 seg */
        val seg: String = Seg.TOKENS,
        /** 渠道列表（seg == CHANNELS 時用） */
        val channels: List<ChannelInfo> = emptyList(),
        val channelsLoading: Boolean = false,
        val channelsError: AppError? = null,
        /** 正在測試或操作的渠道 id */
        val operatingChannelId: Long? = null,
        /** 本月燒錢榜（seg == TOKENS 時顯示）：rows 空 + spendLoaded = 今月未有紀錄 */
        val spendRows: List<TokenSpendStats.TokenSpend> = emptyList(),
        /** 抓取窗口撞 maxPages 封頂 → 顯示「（約）」 */
        val spendTruncated: Boolean = false,
        /** 燒錢榜已成功載入一次（rows 可能空）；失敗 → false，成張卡靜默收起 */
        val spendLoaded: Boolean = false,
        val spendLoading: Boolean = false,
        /* ---------------- 批量管理（多選 啟用/停用/刪除） ---------------- */
        /** 多選模式開關 */
        val selectionMode: Boolean = false,
        /** 多選中嘅令牌 id */
        val selectedIds: Set<Long> = emptySet(),
        /** 批量操作進行中（防重入；跑緊時所有批量按鈕停用） */
        val batchRunning: Boolean = false,
        /** 批量進度 (已完成, 總數) */
        val batchProgress: Pair<Int, Int>? = null,
        /** 批量結果 (成功數, 失敗數)；淨係數字，文案由 UI 譯（約 4 秒自動收起） */
        val batchResult: Pair<Int, Int>? = null,
    )

    private var loadJob: Job? = null
    private var spendJob: Job? = null
    private var searchJob: Job? = null
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
                    _state.value = UiState()
                    load()
                }
        }
    }

    fun retry() = load()

    /**
     * 伺服器端搜尋（350ms 防抖）：關鍵字非空即帶 keyword= 重拉列表；
     * demo 冇伺服器 → 本地按名稱過濾 DemoEngine.tokens()。清空 → 重拉全量。
     */
    fun setTokenSearch(q: String) {
        _state.update { it.copy(keyword = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(350)
            load()
        }
    }

    fun clearActionError() {
        if (_state.value.actionError != null) _state.update { it.copy(actionError = null) }
    }

    fun setSeg(seg: String) {
        if (_state.value.seg == seg) return
        _state.update { it.copy(seg = seg) }
        if (seg == Seg.CHANNELS) loadChannels()
    }

    fun setTokenSort(sort: String) {
        _state.update { it.copy(tokenSort = sort) }
    }

    fun sortedTokens(): List<TokenInfo> {
        val list = _state.value.tokens
        return when (_state.value.tokenSort) {
            "used_desc" -> list.sortedByDescending { it.usedQuota }
            "remain_asc" -> list.sortedWith(
                compareBy<TokenInfo> { if (it.unlimitedQuota) 1 else 0 }
                    .thenBy { it.remainQuota }
            )
            "exp_asc" -> list.sortedWith(
                compareBy<TokenInfo> { if (it.expiredTime <= 0) 1 else 0 }
                    .thenBy { it.expiredTime }
            )
            else -> list
        }
    }

    /* ---------------- 渠道（admin 操作與監察） ---------------- */

    fun retryChannels() = loadChannels()

    /** 切換渠道狀態（1=啟用 2=手動停用） */
    fun toggleChannelStatus(channel: ChannelInfo) {
        if (_state.value.operatingChannelId != null) return
        val next = if (channel.status == 1) 2 else 1
        val demo = repo.store.settings.value.demo
        _state.update { it.copy(operatingChannelId = channel.id, actionError = null) }
        viewModelScope.launch {
            try {
                if (demo) {
                    DemoEngine.updateChannelStatus(channel.id, next)
                    _state.update { s ->
                        s.copy(
                            channels = s.channels.map { if (it.id == channel.id) it.copy(status = next) else it },
                            operatingChannelId = null
                        )
                    }
                } else {
                    val inst = repo.store.activeInstance.value ?: return@launch
                    repo.updateChannelStatus(inst, channel, next)
                    _state.update { it.copy(operatingChannelId = null) }
                    loadChannels()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                val err = if (e.status == 401 || e.status == 403) "操作需要管理員權限嘅 Access Token" else "操作失敗"
                _state.update { it.copy(operatingChannelId = null, actionError = err) }
            } catch (e: Exception) {
                _state.update { it.copy(operatingChannelId = null, actionError = "操作失敗") }
            }
        }
    }

    /** 測試單個渠道連通性 */
    fun testChannel(channel: ChannelInfo) {
        if (_state.value.operatingChannelId != null) return
        val demo = repo.store.settings.value.demo
        _state.update { it.copy(operatingChannelId = channel.id, actionError = null) }
        viewModelScope.launch {
            try {
                if (demo) {
                    kotlinx.coroutines.delay(180)
                    val ms = DemoEngine.testChannel(channel.id)
                    _state.update { s ->
                        s.copy(
                            channels = s.channels.map { if (it.id == channel.id) it.copy(responseTime = ms) else it },
                            operatingChannelId = null
                        )
                    }
                } else {
                    val inst = repo.store.activeInstance.value ?: return@launch
                    val ms = repo.testChannel(inst, channel.id)
                    _state.update { s ->
                        s.copy(
                            channels = s.channels.map { if (it.id == channel.id) it.copy(responseTime = ms) else it },
                            operatingChannelId = null
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                val err = if (e.status == 401 || e.status == 403) "測試需要管理員權限嘅 Access Token" else "測試失敗"
                _state.update { it.copy(operatingChannelId = null, actionError = err) }
            } catch (e: Exception) {
                _state.update { it.copy(operatingChannelId = null, actionError = "操作失敗") }
            }
        }
    }

    /** 刪除渠道 */
    fun deleteChannel(channel: ChannelInfo) {
        if (_state.value.operatingChannelId != null) return
        val demo = repo.store.settings.value.demo
        _state.update { it.copy(operatingChannelId = channel.id, actionError = null) }
        viewModelScope.launch {
            try {
                if (demo) {
                    DemoEngine.deleteChannel(channel.id)
                    _state.update { s ->
                        s.copy(
                            channels = s.channels.filter { it.id != channel.id },
                            operatingChannelId = null
                        )
                    }
                } else {
                    val inst = repo.store.activeInstance.value ?: return@launch
                    repo.deleteChannel(inst, channel.id)
                    _state.update { it.copy(operatingChannelId = null) }
                    loadChannels()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                val err = if (e.status == 401 || e.status == 403) "刪除需要管理員權限嘅 Access Token" else "刪除失敗"
                _state.update { it.copy(operatingChannelId = null, actionError = err) }
            } catch (e: Exception) {
                _state.update { it.copy(operatingChannelId = null, actionError = "操作失敗") }
            }
        }
    }

    private fun loadChannels() {
        if (_state.value.channelsLoading) return
        val demo = repo.store.settings.value.demo
        _state.update { it.copy(channelsLoading = true, channelsError = null) }
        viewModelScope.launch {
            try {
                if (demo) {
                    DemoEngine.start()
                    kotlinx.coroutines.delay(120)
                    _state.update { it.copy(channelsLoading = false, channels = DemoEngine.channels()) }
                } else {
                    val inst = repo.store.activeInstance.value
                    if (inst == null || inst.accessToken.isBlank()) {
                        _state.update { it.copy(channelsLoading = false, channels = emptyList()) }
                        return@launch
                    }
                    val page = repo.fetchChannels(inst)
                    _state.update { it.copy(channelsLoading = false, channels = page.channels) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _state.update { it.copy(channelsLoading = false, channelsError = AppError(e.kind, "渠道載入失敗", e.status)) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(channelsLoading = false, channelsError = AppError(ErrorKind.API, "渠道載入失敗"))
                }
            }
        }
    }

    /* ---------------- 令牌 CRUD ---------------- */

    fun clearReveal() {
        _state.update { it.copy(revealedKey = null, revealingId = null, revealError = null) }
    }

    fun reveal(token: TokenInfo) {
        if (_state.value.revealingId != null) return
        clearReveal()
        val source = lastSourceKey
        _state.update { it.copy(revealingId = token.id) }
        viewModelScope.launch {
            try {
                val key = if (repo.store.settings.value.demo) token.key.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Demo Key unavailable")
                else repo.store.activeInstance.value?.let { repo.revealTokenKey(it, token.id) }
                    ?: throw IllegalStateException("Instance unavailable")
                if (source == lastSourceKey && _state.value.revealingId == token.id)
                    _state.update { it.copy(revealingId = null, revealedKey = key) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (source == lastSourceKey) _state.update {
                    it.copy(revealingId = null, revealedKey = null,
                        revealError = if (e.status == 401 || e.status == 403) "無權限查看令牌 Key"
                        else if (e.status == 404) "此站點不支援顯示令牌 Key" else "無法安全顯示令牌 Key")
                }
            } catch (e: Exception) {
                if (source == lastSourceKey) _state.update {
                    it.copy(revealingId = null, revealedKey = null, revealError = "無法安全顯示令牌 Key")
                }
            }
        }
    }

    /** 新增令牌：name 必填；remainUsd 有限額時先有意義；expiryDays -1 = 永不 */
    fun createToken(name: String, remainUsd: Double?, unlimited: Boolean, expiryDays: Long,
        group: String, modelLimitsEnabled: Boolean, modelLimits: String, allowIps: String) {
        if (_state.value.togglingId != null) return
        val demo = repo.store.settings.value.demo
        val qpu = _state.value.qpu
        _state.update { it.copy(togglingId = -1L, actionError = null) }
        viewModelScope.launch {
            try {
                if (demo) {
                    DemoEngine.addToken(
                        name = name,
                        remainQuota = (remainUsd ?: 0.0) * qpu,
                        unlimited = unlimited,
                        expiredTime = if (expiryDays <= 0) -1L
                        else System.currentTimeMillis() / 1000 + expiryDays * 86400L,
                    )
                    _state.update { it.copy(togglingId = null) }
                    load()
                } else {
                    val inst = repo.store.activeInstance.value ?: return@launch
                    repo.createToken(
                        inst,
                        name = name,
                        remainQuota = (((remainUsd ?: 0.0) * qpu).toLong()),
                        unlimited = unlimited,
                        expiredTime = if (expiryDays <= 0) -1L
                        else System.currentTimeMillis() / 1000 + expiryDays * 86400L,
                        group = group, modelLimitsEnabled = modelLimitsEnabled,
                        modelLimits = modelLimits, allowIps = allowIps,
                    )
                    _state.update { it.copy(togglingId = null) }
                    load()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _state.update { it.copy(togglingId = null, actionError = "操作失敗") }
            } catch (e: Exception) {
                _state.update { it.copy(togglingId = null, actionError = "操作失敗") }
            }
        }
    }

    /** 儲存編輯（名稱／額度）；status 原樣保留 */
    fun saveEdits(token: TokenInfo, newName: String, remainUsd: Double?, unlimited: Boolean,
        expiryDays: Long, group: String, modelLimitsEnabled: Boolean, modelLimits: String, allowIps: String) {
        if (_state.value.togglingId != null) return
        val demo = repo.store.settings.value.demo
        val qpu = _state.value.qpu
        _state.update { it.copy(togglingId = token.id, actionError = null) }
        viewModelScope.launch {
            try {
                if (demo) {
                    DemoEngine.updateTokenFields(token.id, newName, remainUsd ?: 0.0, unlimited)
                    _state.update { it.copy(togglingId = null) }
                    load()
                } else {
                    val inst = repo.store.activeInstance.value ?: return@launch
                    val changes = mutableMapOf<String, JsonPrimitive>(
                        "name" to JsonPrimitive(newName),
                        "unlimited_quota" to JsonPrimitive(unlimited),
                        "group" to JsonPrimitive(group),
                        "model_limits_enabled" to JsonPrimitive(modelLimitsEnabled),
                        "model_limits" to JsonPrimitive(modelLimits),
                        "allow_ips" to JsonPrimitive(allowIps),
                    )
                    if (!unlimited) changes["remain_quota"] = JsonPrimitive(((remainUsd ?: 0.0) * qpu).toLong())
                    if (expiryDays > 0) changes["expired_time"] = JsonPrimitive(System.currentTimeMillis() / 1000 + expiryDays * 86400L)
                    repo.saveTokenEdits(inst, token, changes)
                    _state.update { it.copy(togglingId = null) }
                    load()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _state.update { it.copy(togglingId = null, actionError = "操作失敗") }
            } catch (e: Exception) {
                _state.update { it.copy(togglingId = null, actionError = "操作失敗") }
            }
        }
    }

    /** 刪除令牌 */
    fun delete(token: TokenInfo) {
        if (_state.value.togglingId != null) return
        val demo = repo.store.settings.value.demo
        _state.update { it.copy(togglingId = token.id, actionError = null) }
        viewModelScope.launch {
            try {
                if (demo) {
                    DemoEngine.removeToken(token.id)
                    _state.update { it.copy(togglingId = null) }
                    load()
                } else {
                    val inst = repo.store.activeInstance.value ?: return@launch
                    repo.deleteToken(inst, token.id)
                    _state.update { it.copy(togglingId = null) }
                    load()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _state.update { it.copy(togglingId = null, actionError = "操作失敗") }
            } catch (e: Exception) {
                _state.update { it.copy(togglingId = null, actionError = "操作失敗") }
            }
        }
    }

    /** 啟用（1）/停用（2）切換；成功後重拉列表，失敗出錯誤條 */
    fun toggle(token: TokenInfo) {
        val st = _state.value
        if (st.togglingId != null) return
        val demo = repo.store.settings.value.demo
        if (demo) {
            val next = if (token.status == 1) 2 else 1
            DemoEngine.setTokenStatus(token.id, next)
            _state.update { s ->
                s.copy(
                    tokens = s.tokens.map { if (it.id == token.id) it.copy(status = next) else it },
                    actionError = null,
                )
            }
            return
        }
        val inst = repo.store.activeInstance.value ?: return
        val next = if (token.status == 1) 2 else 1
        _state.update { it.copy(togglingId = token.id, actionError = null) }
        viewModelScope.launch {
            try {
                repo.setTokenStatus(inst, token.id, next)
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _state.update { it.copy(togglingId = null, actionError = "操作失敗") }
            } catch (e: Exception) {
                _state.update { it.copy(togglingId = null, actionError = "操作失敗") }
            }
        }
    }

    /* ---------------- 批量管理（多選 啟用/停用/刪除） ---------------- */

    fun enterSelectionMode() {
        _state.update { it.copy(selectionMode = true) }
    }

    /** 退出多選（清空選取）；批量跑緊時唔畀退出 */
    fun exitSelectionMode() {
        val st = _state.value
        if (st.batchRunning) return
        if (st.selectionMode || st.selectedIds.isNotEmpty()) {
            _state.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
        }
    }

    fun toggleSelect(id: Long) {
        _state.update { s ->
            s.copy(selectedIds = if (id in s.selectedIds) s.selectedIds - id else s.selectedIds + id)
        }
    }

    /** 全選：而家列表入面所有令牌 id */
    fun selectAll() {
        _state.update { s -> s.copy(selectedIds = s.tokens.map { it.id }.toSet()) }
    }

    /** 批量結果摘要由 UI 約 4 秒後收起 */
    fun clearBatchResult() {
        if (_state.value.batchResult != null) _state.update { it.copy(batchResult = null) }
    }

    /** 批量啟用（status=1）／停用（status=2）：逐張順序打 */
    fun batchSetStatus(enabled: Boolean) {
        val newStatus = if (enabled) 1 else 2
        val demo = repo.store.settings.value.demo
        val inst = repo.store.activeInstance.value
        runBatch { id, token ->
            when {
                demo -> DemoEngine.setTokenStatus(id, newStatus)
                // 令牌已經唔喺列表 → skip（唔計成功定失敗）
                token == null -> null
                inst != null -> {
                    repo.setTokenStatus(inst, id, newStatus)
                    true
                }
                else -> false
            }
        }
    }

    /** 批量刪除（UI confirm 完先會叫到呢度） */
    fun batchDelete() {
        val demo = repo.store.settings.value.demo
        val inst = repo.store.activeInstance.value
        runBatch { id, token ->
            when {
                demo -> DemoEngine.removeToken(id)
                token == null -> null
                inst != null -> {
                    repo.deleteToken(inst, id)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 批量骨架：逐 id 順序執行（一張一張，唔並發——溫柔對伺服器）；每張完更新進度。
     * action 回傳 true = 成功、false = 失敗、null = skip（實例模式已經唔喺列表嘅 id）；
     * 任何 ApiException（401/403 等）都只計失敗，絕不炸。
     * 完成後：重拉列表、自動退出多選、batchResult = (ok, fail)；全軍覆沒再出 actionError。
     */
    private fun runBatch(action: suspend (id: Long, token: TokenInfo?) -> Boolean?) {
        val st = _state.value
        if (st.batchRunning || st.selectedIds.isEmpty()) return
        val ids = st.selectedIds.toList()
        val tokensById = st.tokens.associateBy { it.id }
        _state.update {
            it.copy(
                batchRunning = true,
                batchProgress = Pair(0, ids.size),
                batchResult = null,
                actionError = null,
                // 借 togglingId 嘅 -1「全域忙碌」慣例，順手擋住單張 toggle／CRUD 防重入
                togglingId = -1L,
            )
        }
        viewModelScope.launch {
            var ok = 0
            var fail = 0
            var done = 0
            for (id in ids) {
                val outcome = try {
                    action(id, tokensById[id])
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                when (outcome) {
                    true -> ok++
                    false -> fail++
                    else -> {} // null：skip
                }
                done++
                _state.update { it.copy(batchProgress = Pair(done, ids.size)) }
            }
            _state.update {
                it.copy(
                    batchRunning = false,
                    batchProgress = null,
                    batchResult = Pair(ok, fail),
                    selectionMode = false,
                    selectedIds = emptySet(),
                    togglingId = null,
                    actionError = if (ok == 0 && fail > 0) "操作失敗" else null,
                )
            }
            load()
        }
    }

    private fun load() {
        loadJob?.cancel()
        val demo = repo.store.settings.value.demo
        val inst = repo.store.activeInstance.value
        _state.update { it.copy(loading = true, error = null, noAccessToken = false) }
        loadJob = viewModelScope.launch {
            try {
                if (demo) {
                    DemoEngine.start()
                    delayDemo()
                    val kw = _state.value.keyword.trim()
                    val all = DemoEngine.tokens()
                    _state.update {
                        it.copy(
                            loading = false,
                            tokens = if (kw.isEmpty()) all else all.filter { t ->
                                t.name.contains(kw, ignoreCase = true)
                            },
                            truncated = false,
                            qpu = DemoEngine.QPU,
                        )
                    }
                } else {
                    if (inst == null || inst.accessToken.isBlank()) {
                        _state.update { it.copy(loading = false, noAccessToken = true, tokens = emptyList()) }
                        return@launch
                    }
                    // 分頁拉全量：逐頁 100 張，最多 10 頁（1000 張）；撞頂 → truncated 標示
                    val kw = _state.value.keyword.trim()
                    val acc = ArrayList<TokenInfo>()
                    var capped = false
                    for (p in 1..TOKEN_MAX_PAGES) {
                        val page = repo.fetchTokensPage(inst, p = p, size = TOKEN_PAGE_SIZE, keyword = kw)
                        acc += page.tokens
                        if (!page.truncated) break
                        if (p == TOKEN_MAX_PAGES) capped = true
                    }
                    _state.update {
                        it.copy(
                            loading = false,
                            tokens = acc,
                            truncated = capped,
                            // 同儀表板同一個換算：同來源快照嘅 qpu，切來源後用實例設定 fallback
                            qpu = repo.snapshotForSource(demo, inst)?.qpu ?: inst.quotaPerUnit ?: Quota.DEFAULT_QPU,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = AppError(e.kind, "令牌載入失敗", e.status)) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = AppError(ErrorKind.API, "令牌載入失敗"))
                }
            }
            // 本月燒錢榜：跟 load() 一齊刷新（重試／CRUD 後都重拉）；任何失敗都靜默，唔影響令牌列表
            loadSpend()
        }
    }

    /**
     * 本月燒錢榜：GET /api/log/self（per-user 端點唔使 admin）本月窗口 → TokenSpendStats 聚合。
     *  - demo 用 DemoEngine.monthLogs()
     *  - 失敗（網絡／401 等）→ 清空並令成張卡靜默收起，絕不炸
     *  - 上次成功結果保留喺 state（刷新中照顯示舊數）；切實例／demo 時 UiState 整個重置再重拉
     */
    private fun loadSpend() {
        spendJob?.cancel()
        val demo = repo.store.settings.value.demo
        val inst = repo.store.activeInstance.value
        if (!demo && (inst == null || inst.accessToken.isBlank())) return
        _state.update { it.copy(spendLoading = true) }
        spendJob = viewModelScope.launch {
            try {
                if (demo) {
                    DemoEngine.start()
                    val res = TokenSpendStats.compute(DemoEngine.monthLogs(), DemoEngine.QPU)
                    _state.update {
                        it.copy(
                            spendLoading = false,
                            spendLoaded = true,
                            spendRows = res.rows,
                            spendTruncated = res.truncated,
                        )
                    }
                } else {
                    val i = inst ?: return@launch
                    val page = repo.monthLogsPage(i)
                    // 同儀表板同一個換算：同來源快照嘅 qpu，切來源後用實例設定 fallback
                    val qpu = repo.snapshotForSource(demo = false, i)?.qpu
                        ?: i.quotaPerUnit
                        ?: Quota.DEFAULT_QPU
                    val res = TokenSpendStats.compute(page.rows, qpu, page.truncated)
                    _state.update {
                        it.copy(
                            spendLoading = false,
                            spendLoaded = true,
                            spendRows = res.rows,
                            spendTruncated = res.truncated,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 靜默失敗：成張卡收起（清返 loading 同舊數據）
                _state.update {
                    it.copy(
                        spendLoading = false,
                        spendLoaded = false,
                        spendRows = emptyList(),
                        spendTruncated = false,
                    )
                }
            }
        }
    }

    private suspend fun delayDemo() = kotlinx.coroutines.delay(120) // demo shimmer 一瞬（日誌頁同款）
}
