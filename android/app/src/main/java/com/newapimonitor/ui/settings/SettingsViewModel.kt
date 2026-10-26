package com.newapimonitor.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newapimonitor.alert.AlertScheduler
import com.newapimonitor.data.api.NewApiClient
import com.newapimonitor.data.backup.BackupManager
import com.newapimonitor.data.monitor.MonitorPreferencesStore
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.data.store.AlertSettingsStore
import com.newapimonitor.data.store.AppLockStore
import com.newapimonitor.data.store.AppearanceStore
import com.newapimonitor.data.store.DashboardLayoutStore
import com.newapimonitor.demo.DemoEngine
import com.newapimonitor.domain.AlertSettings
import com.newapimonitor.domain.Instance
import com.newapimonitor.domain.InstanceSanitizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 設定表單值（未清洗，save 時先 normalize） */
data class InstanceForm(
    val name: String = "",
    val baseUrl: String = "",
    val accessToken: String = "",
    val apiKey: String = "",
    val quotaPerUnitRaw: String = "",
) {
    fun normalizedQuotaPerUnit(): Double? =
        quotaPerUnitRaw.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }

    fun toInstance(): Instance = Instance(
        id = "",
        name = name,
        baseUrl = InstanceSanitizer.normalizeBaseUrlInput(baseUrl),
        accessToken = accessToken,
        apiKey = apiKey,
        quotaPerUnit = normalizedQuotaPerUnit(),
        authStyle = null,
        authStyleKey = null,
        createdAt = 0,
    )
}

data class TestUi(
    val running: Boolean = false,
    val ok: Boolean? = null,
    val lines: List<NewApiClient.TestLine> = emptyList(),
)

/** 設定頁：實例 CRUD／切換、測試連接、刷新間隔、演示模式、警報設定、App lock、清除數據 */
class SettingsViewModel(
    private val repo: NewApiRepository,
    private val alertStore: AlertSettingsStore = AlertSettingsStore(repo.store.appContext),
    private val appLockStore: AppLockStore = AppLockStore(repo.store.appContext),
    private val appearanceStore: AppearanceStore = AppearanceStore(repo.store.appContext),
    /** 儀表板卡片顯示偏好；同 DashboardViewModel 各自 new 一個實例，DataStore 即時同步 */
    private val layoutStore: DashboardLayoutStore = DashboardLayoutStore(repo.store.appContext),
) : ViewModel() {

    val instances = repo.store.instances
    val settings = repo.store.settings
    val alerts: StateFlow<AlertSettings> = alertStore.settings
    fun refreshAlertSettings() { viewModelScope.launch { alertStore.loadOnce() } }
    val appLock: StateFlow<Boolean> = appLockStore.isEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** 收埋咗嘅儀表板卡 id（balance hero 永遠可見，sanitize 已剔走） */
    val hiddenCards: StateFlow<Set<String>> = layoutStore.hiddenCardIds
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        viewModelScope.launch {
            alertStore.loadOnce()
        }
    }

    private val _test = MutableStateFlow(TestUi())
    val test: StateFlow<TestUi> = _test.asStateFlow()

    fun save(form: InstanceForm, editingId: String?, onDone: () -> Unit) {
        viewModelScope.launch {
            val data = form.toInstance()
            if (editingId != null) {
                repo.store.updateInstance(editingId, data)
            } else {
                val created = repo.store.addInstance(data)
                if (created != null) repo.store.setActive(created.id)
            }
            onDone()
        }
    }

    fun deleteInstance(id: String) {
        viewModelScope.launch { repo.store.removeInstance(id) }
    }

    fun setActive(id: String) {
        viewModelScope.launch { repo.store.setActive(id) }
    }

    fun setIntervalSec(sec: Int) {
        viewModelScope.launch { repo.store.setIntervalSec(sec) }
    }

    fun setDemo(v: Boolean) {
        viewModelScope.launch {
            repo.store.setDemo(v)
            if (v) DemoEngine.start()
        }
    }

    /* ---------------- 警報設定 ---------------- */

    fun setAlertEnabled(enabled: Boolean) {
        viewModelScope.launch {
            alertStore.update { it.copy(enabled = enabled) }
            AlertScheduler.ensure(repo.store.appContext, enabled)
            if (!enabled) com.newapimonitor.alert.StatusNotifier.cancel(repo.store.appContext)
        }
    }

    fun setAlertBalanceBelow(v: Double?) {
        viewModelScope.launch {
            alertStore.update { it.copy(balanceBelowUsd = v) }
        }
    }

    fun setAlertDailySpendAbove(v: Double?) {
        viewModelScope.launch {
            alertStore.update { it.copy(dailySpendAboveUsd = v) }
        }
    }

    fun setAlertMonthlyBudget(v: Double?) {
        viewModelScope.launch { alertStore.update { it.copy(monthlyBudgetUsd = v) } }
    }

    fun setAlertErrorRate(enabled: Boolean) {
        viewModelScope.launch { alertStore.update { it.copy(notifyErrorRate = enabled) } }
    }

    fun setAlertTokenExpiry(enabled: Boolean) {
        viewModelScope.launch { alertStore.update { it.copy(notifyTokenExpiry = enabled) } }
    }

    fun setAlertPersistent(enabled: Boolean) {
        viewModelScope.launch {
            alertStore.update { it.copy(persistentStatus = enabled) }
            com.newapimonitor.alert.StatusNotifier.applyPreference(repo.store.appContext, enabled)
        }
    }

    fun setAlertSpikeMultiplier(m: Double) {
        viewModelScope.launch {
            alertStore.update { it.copy(spikeMultiplier = m) }
        }
    }

    fun setAlertCooldownMinutes(min: Int) {
        viewModelScope.launch {
            alertStore.update { it.copy(cooldownMinutes = min) }
        }
    }

    fun setAlertNotifyTokenBanned(notify: Boolean) {
        viewModelScope.launch {
            alertStore.update { it.copy(notifyTokenBanned = notify) }
        }
    }

    /* ---------------- 儀表板卡片顯示 ---------------- */

    /** 收埋／重新顯示一張儀表板卡（寫入 DashboardLayoutStore；儀表板即時跟） */
    fun setCardHidden(id: String, hidden: Boolean) {
        viewModelScope.launch { layoutStore.setCardHidden(id, hidden) }
    }

    /* ---------------- App Lock 生物辨識鎖定 ---------------- */

    fun setAppLock(enabled: Boolean) {
        viewModelScope.launch {
            appLockStore.setEnabled(enabled)
        }
    }

    /* ---------------- 備份與還原 ---------------- */

    /**
     * 匯入備份：實例逐個 upsert（baseUrl 及名稱同現有實例相同就覆寫嗰個，否則新增；
     * 永遠唔會刪走現有實例）。appearance／alertSettings 檔案入面有先至套用。
     * onDone 帶成功寫入嘅實例數。
     */
    fun importBackup(parsed: BackupManager.ParsedBackup, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            var imported = 0
            for (draft in parsed.instances) {
                val existing = repo.store.instances.value.firstOrNull { it.baseUrl == draft.baseUrl && it.name == draft.name }
                val ok = if (existing != null) {
                    repo.store.updateInstance(existing.id, draft.copy(
                        accessToken = draft.accessToken.ifBlank { existing.accessToken },
                        apiKey = draft.apiKey.ifBlank { existing.apiKey },
                    )) != null
                } else {
                    repo.store.addInstance(draft) != null
                }
                if (ok) imported++
            }
            for ((url, budget) in parsed.budgets) {
                repo.store.instances.value.firstOrNull { it.baseUrl == InstanceSanitizer.sanitizeBaseUrl(url) }?.let {
                    com.newapimonitor.data.monitor.MonitorStore(repo.store.appContext).setBudget(it.id, budget)
                }
            }
            for (budget in parsed.instanceBudgets) {
                repo.store.instances.value.firstOrNull { it.baseUrl == budget.baseUrl && it.name == budget.name }?.let {
                    com.newapimonitor.data.monitor.MonitorStore(repo.store.appContext).setBudget(it.id, budget.usd)
                }
            }
            val homeStore = MonitorPreferencesStore(repo.store.appContext)
            val restored = parsed.instanceOptions.mapNotNull { option ->
                repo.store.instances.value.firstOrNull { it.baseUrl == option.baseUrl && it.name == option.name }
                    ?.let { it.id to option }
            }
            if (parsed.homeOptions != null || restored.isNotEmpty()) homeStore.update { current ->
                val pinned = current.pinned.toMutableSet()
                val excluded = current.excluded.toMutableSet()
                for ((id, option) in restored) {
                    if (option.pinned == true) pinned += id else if (option.pinned == false) pinned -= id
                    if (option.balanceIncluded == false) excluded += id else if (option.balanceIncluded == true) excluded -= id
                }
                val importedOrder = restored.filter { it.second.homeOrder != null }.sortedBy { it.second.homeOrder }.map { it.first }
                val global = parsed.homeOptions
                current.copy(compact = global?.compact ?: current.compact,
                    hideMoney = global?.hideMoney ?: current.hideMoney,
                    favorites = if (global != null) global.favorites else current.favorites,
                    suites = if (global != null) global.suites else current.suites,
                    pinned = pinned, excluded = excluded,
                    order = if (importedOrder.isEmpty()) current.order else importedOrder + current.order.filterNot { it in importedOrder })
            }
            for ((id, option) in restored) option.alertRule?.let { homeStore.saveRule(id, it) }
            parsed.appearance?.let { ap ->
                ap.language?.let { appearanceStore.setLanguage(it) }
                ap.mode?.let { appearanceStore.setMode(it) }
                ap.accent?.let { appearanceStore.setAccent(it) }
            }
            parsed.alertSettings?.let { next ->
                alertStore.update { next }
                AlertScheduler.ensure(repo.store.appContext, next.enabled)
                com.newapimonitor.alert.StatusNotifier.applyPreference(repo.store.appContext, next.enabled && next.persistentStatus)
            }
            onDone(imported)
        }
    }

    /* ---------------- 清除數據 ---------------- */

    fun wipeAll() {
        viewModelScope.launch {
            repo.store.wipeAll()
            alertStore.update { AlertSettings() }
            AlertScheduler.ensure(repo.store.appContext, false)
            com.newapimonitor.alert.StatusNotifier.cancel(repo.store.appContext)
            appLockStore.setEnabled(false)
            DemoEngine.reset()
        }
    }

    /** 測試連接：用表單當前值（唔使先儲存），實測 /api/user/self 或 billing 端點 */
    fun testConnection(form: InstanceForm) {
        _test.value = TestUi(running = true)
        viewModelScope.launch {
            try {
                val data = form.toInstance()
                val client = NewApiClient(
                    baseUrl = data.baseUrl,
                    accessToken = data.accessToken,
                    apiKey = data.apiKey,
                    quotaPerUnit = data.quotaPerUnit,
                )
                val result = client.testConnection()
                _test.value = TestUi(running = false, ok = result.ok, lines = result.lines)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _test.value = TestUi(
                    running = false,
                    ok = false,
                    lines = listOf(NewApiClient.TestLine("err", "✗ 測試異常：" + (e.message ?: e.toString()))),
                )
            }
        }
    }
}
