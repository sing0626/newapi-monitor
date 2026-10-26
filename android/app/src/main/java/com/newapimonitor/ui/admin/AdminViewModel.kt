package com.newapimonitor.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newapimonitor.data.api.ApiException
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.demo.DemoEngine
import com.newapimonitor.domain.AdminUserInfo
import com.newapimonitor.domain.Instance
import com.newapimonitor.domain.LogEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

internal fun adminSource(demo: Boolean, instance: Instance?): String =
    if (demo) "demo" else instance?.id ?: "none"

/** 結構化權限／端點錯誤：401 = PAT 無效，403 = 唔係管理員，404 = 站點唔支援 */
enum class AdminFailure { AUTH, FORBIDDEN, MISSING, NETWORK, INVALID }

internal fun adminFailure(e: ApiException): AdminFailure = when (e.status) {
    401 -> AdminFailure.AUTH
    403 -> AdminFailure.FORBIDDEN
    404 -> AdminFailure.MISSING
    else -> AdminFailure.NETWORK
}

data class AdminState(
    val source: String = "none",
    val instanceName: String? = null,
    val demo: Boolean = false,
    val needsAccessToken: Boolean = false,
    /** seg：使用者 | 全站日誌 */
    val seg: String = Seg.USERS,
    val loading: Boolean = false,
    /** 使用者列表（seg == USERS） */
    val users: List<AdminUserInfo> = emptyList(),
    val usersTruncated: Boolean = false,
    val usersFailure: AdminFailure? = null,
    /** 使用者搜尋（伺服器 username=） */
    val userKeyword: String = "",
    /** 全站日誌（seg == LOGS） */
    val logs: List<LogEntry> = emptyList(),
    val logsTruncated: Boolean = false,
    val logsFailure: AdminFailure? = null,
    val qpu: Double = com.newapimonitor.domain.Quota.DEFAULT_QPU,
) {
    object Seg {
        const val USERS = "users"
        const val LOGS = "logs"
    }
}

/**
 * 管理員 Hub（唯讀第一階段）：使用者列表＋全站日誌。
 *  - 有 PAT 先打管理 API；401 = PAT 無效、403 = 冇管理員權限、404 = 站點唔支援，各自出清楚狀態
 *  - 讀權限唔推斷寫權限：呢頁完全唯讀，唔會代管理員改任何使用者
 *  - 換實例／換憑證 → state 重置，上一個站嘅數據唔會殘留
 */
class AdminViewModel(private val repo: NewApiRepository) : ViewModel() {
    private val _state = MutableStateFlow(AdminState())
    val state: StateFlow<AdminState> = _state.asStateFlow()
    private var fetchJob: Job? = null
    private var searchJob: Job? = null
    private var observedInstance: Instance? = null
    private var requestId = 0L
    private var visible = false

    init {
        viewModelScope.launch {
            repo.store.ready.filter { it }.first()
            combine(repo.store.settings, repo.store.activeInstance) { s, inst ->
                Triple(adminSource(s.demo, inst), s.demo, inst)
            }
                .distinctUntilChanged()
                .collectLatest { (source, demo, inst) ->
                    if (_state.value.source == source && observedInstance == inst && _state.value.demo == demo) return@collectLatest
                    observedInstance = inst
                    fetchJob?.cancel()
                    requestId++
                    _state.value = AdminState(
                        source = source, instanceName = inst?.name, demo = demo,
                        needsAccessToken = inst != null && inst.accessToken.isBlank(),
                        qpu = if (demo) DemoEngine.QPU
                        else repo.snapshotForSource(demo, inst)?.qpu ?: inst?.quotaPerUnit
                        ?: com.newapimonitor.domain.Quota.DEFAULT_QPU,
                    )
                    if (visible) refresh()
                }
        }
    }

    fun resume() { visible = true; refresh() }
    fun pause() { visible = false; requestId++; fetchJob?.cancel(); fetchJob = null; _state.update { it.copy(loading = false) } }

    fun setSeg(seg: String) {
        _state.update { it.copy(seg = seg) }
        refresh()
    }

    /** 使用者搜尋（350ms 防抖；demo 本地過濾） */
    fun setUserSearch(q: String) {
        _state.update { it.copy(userKeyword = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            loadUsers()
        }
    }

    fun refresh() {
        fetchJob?.cancel()
        val generation = ++requestId
        val settings = repo.store.settings.value
        val inst = repo.store.activeInstance.value
        val source = adminSource(settings.demo, inst)
        observedInstance = inst
        if (!visible || inst == null || inst.accessToken.isBlank()) {
            if (settings.demo) {
                _state.update {
                    it.copy(loading = false, demo = true, source = "demo", usersFailure = null, logsFailure = null)
                }
                loadDemo()
            } else {
                _state.value = _state.value.copy(
                    source = source, instanceName = inst?.name,
                    needsAccessToken = inst != null && inst.accessToken.isBlank(), loading = false,
                )
            }
            return
        }
        _state.update { it.copy(loading = true, source = source, instanceName = inst.name) }
        fetchJob = viewModelScope.launch {
            val usersJob = launch { loadUsers(generation) }
            val logsJob = launch { loadLogs(generation) }
            usersJob.join()
            logsJob.join()
            if (visible && generation == requestId) _state.update { it.copy(loading = false) }
        }
    }

    private fun loadDemo() {
        val kw = _state.value.userKeyword.trim()
        _state.update {
            it.copy(
                loading = false,
                users = if (kw.isEmpty()) DemoEngine.adminUsers()
                else DemoEngine.adminUsers().filter { u ->
                    u.username.contains(kw, ignoreCase = true) || u.displayName.contains(kw, ignoreCase = true)
                },
                usersTruncated = false, usersFailure = null,
                logs = DemoEngine.monthLogs().take(100), logsTruncated = true, logsFailure = null,
                qpu = DemoEngine.QPU,
            )
        }
    }

    private suspend fun loadUsers(generation: Long = requestId) {
        if (_state.value.demo) { if (generation == requestId) loadDemoUsersOnly(); return }
        val inst = repo.store.activeInstance.value ?: return
        try {
            val kw = _state.value.userKeyword.trim()
            val acc = ArrayList<AdminUserInfo>()
            var capped = false
            for (p in 1..ADMIN_MAX_PAGES) {
                val page = repo.fetchAdminUsersPage(inst, p = p, size = ADMIN_PAGE_SIZE, keyword = kw)
                acc += page.users
                if (!page.truncated) break
                if (p == ADMIN_MAX_PAGES) capped = true
            }
            if (!visible || generation != requestId) return
            _state.update { it.copy(users = acc, usersTruncated = capped, usersFailure = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (visible && generation == requestId) _state.update { it.copy(users = emptyList(), usersFailure = adminFailure(e)) }
        } catch (_: Exception) {
            if (visible && generation == requestId) _state.update { it.copy(users = emptyList(), usersFailure = AdminFailure.INVALID) }
        }
    }

    private fun loadDemoUsersOnly() {
        val kw = _state.value.userKeyword.trim()
        _state.update {
            it.copy(
                users = if (kw.isEmpty()) DemoEngine.adminUsers()
                else DemoEngine.adminUsers().filter { u ->
                    u.username.contains(kw, ignoreCase = true) || u.displayName.contains(kw, ignoreCase = true)
                },
                usersTruncated = false, usersFailure = null,
            )
        }
    }

    private suspend fun loadLogs(generation: Long = requestId) {
        if (_state.value.demo) return // demo 由 loadDemo 一併處理
        val inst = repo.store.activeInstance.value ?: return
        try {
            val acc = ArrayList<LogEntry>()
            var capped = false
            for (p in 1..ADMIN_MAX_PAGES) {
                val page = repo.fetchAdminLogsPage(inst, p = p, pageSize = ADMIN_PAGE_SIZE)
                acc += page.rows
                if (!page.truncated) break
                if (p == ADMIN_MAX_PAGES) capped = true
            }
            if (!visible || generation != requestId) return
            _state.update { it.copy(logs = acc, logsTruncated = capped, logsFailure = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (visible && generation == requestId) _state.update { it.copy(logs = emptyList(), logsFailure = adminFailure(e)) }
        } catch (_: Exception) {
            if (visible && generation == requestId) _state.update { it.copy(logs = emptyList(), logsFailure = AdminFailure.INVALID) }
        }
    }

    companion object {
        private const val ADMIN_PAGE_SIZE = 100
        /** 唯讀列表上限：10 頁 × 100 行（撞頂標「約」；唔會無限拉） */
        private const val ADMIN_MAX_PAGES = 10
    }
}
