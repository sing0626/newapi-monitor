package com.newapimonitor.ui.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newapimonitor.data.api.ApiException
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.demo.DemoEngine
import com.newapimonitor.domain.Instance
import com.newapimonitor.domain.TopUpInfo
import com.newapimonitor.domain.TopUpRecord
import com.newapimonitor.domain.UserSubscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun billingSource(demo: Boolean, instance: Instance?): String =
    if (demo) "demo" else instance?.id ?: "none"

/** 結構化錯誤碼；永遠唔會把可能帶憑證嘅上游訊息直接渲染出嚟 */
enum class BillingFailure { AUTH, FORBIDDEN, MISSING, NETWORK, INVALID }

internal fun billingFailure(e: ApiException): BillingFailure = when (e.status) {
    401 -> BillingFailure.AUTH
    403 -> BillingFailure.FORBIDDEN
    404 -> BillingFailure.MISSING
    else -> BillingFailure.NETWORK
}

data class BillingState(
    val source: String = "none",
    val instanceName: String? = null,
    val demo: Boolean = false,
    val needsAccessToken: Boolean = false,
    val loading: Boolean = false,
    /** 訂閱／套餐（GET /api/subscription/self）；null = 未攞到（失敗見 subscriptionFailure） */
    val subscription: UserSubscription? = null,
    val subscriptionFailure: BillingFailure? = null,
    /** 充值方式（GET /api/user/topup/info） */
    val topUpInfo: TopUpInfo? = null,
    val topUpInfoFailure: BillingFailure? = null,
    /** 充值記錄（GET /api/user/topup/self） */
    val topUps: List<TopUpRecord>? = null,
    val topUpsFailure: BillingFailure? = null,
    /** 站點首頁 URL（「前往站點充值」用；demo = null 唔出掣） */
    val siteUrl: String? = null,
)

/**
 * 帳務二級頁 ViewModel（同 PricingViewModel 同一套慣例）：
 *  - demo → DemoEngine 模擬數據（明示模擬）
 *  - key-only（冇 Access Token）→ needsAccessToken，唔打管理 API
 *  - 換實例／換憑證 → 成個 state 重置，舊帳戶數據唔會殘留
 *  - 三個區塊各自獨立失敗：404 = 站點不支援，401/403 = 權限，唔會扮成功或出 0
 */
class BillingViewModel(private val repo: NewApiRepository) : ViewModel() {
    private val _state = MutableStateFlow(BillingState())
    val state: StateFlow<BillingState> = _state.asStateFlow()
    private var fetchJob: Job? = null
    private var observedInstance: Instance? = null
    private var requestId = 0L
    private var visible = false

    init {
        viewModelScope.launch {
            combine(repo.store.settings, repo.store.activeInstance) { settings, instance ->
                Triple(billingSource(settings.demo, instance), settings.demo, instance)
            }.collectLatest { (source, demo, instance) ->
                // 換咗實例／憑證就唔可以顯示上一個帳戶嘅帳務數據
                if (_state.value.source == source && observedInstance == instance && _state.value.demo == demo) return@collectLatest
                observedInstance = instance
                fetchJob?.cancel()
                requestId++
                _state.value = BillingState(source = source, instanceName = instance?.name, demo = demo,
                    needsAccessToken = instance != null && instance.accessToken.isBlank())
                if (visible) refresh()
            }
        }
    }

    fun resume() { visible = true; refresh() }
    fun pause() { visible = false; requestId++; fetchJob?.cancel(); fetchJob = null; _state.update { it.copy(loading = false) } }

    fun refresh() {
        fetchJob?.cancel()
        val generation = ++requestId
        val settings = repo.store.settings.value
        val instance = repo.store.activeInstance.value
        val source = billingSource(settings.demo, instance)
        observedInstance = instance
        if (settings.demo) {
            // 演示：完全離線模擬數據（UI 明示「模擬」）
            _state.value = BillingState(
                source = "demo",
                instanceName = instance?.name,
                demo = true,
                subscription = DemoEngine.subscriptionSelf(),
                topUpInfo = DemoEngine.topUpInfo(),
                topUps = DemoEngine.userTopUps(),
                siteUrl = null,
            )
            return
        }
        if (!visible || instance == null || instance.accessToken.isBlank()) {
            _state.value = BillingState(source = source, instanceName = instance?.name,
                needsAccessToken = instance != null && instance.accessToken.isBlank())
            return
        }
        _state.value = BillingState(source = source, instanceName = instance.name, loading = true,
            siteUrl = instance.baseUrl)
        fetchJob = viewModelScope.launch {
            fun publish(change: (BillingState) -> BillingState) {
                if (visible && generation == requestId) _state.update(change)
            }
            try {
                try {
                    val sub = repo.fetchSubscriptionSelf(instance)
                    publish { it.copy(subscription = sub) }
                } catch (e: CancellationException) { throw e }
                  catch (e: ApiException) { publish { it.copy(subscriptionFailure = billingFailure(e)) } }
                  catch (_: Exception) { publish { it.copy(subscriptionFailure = BillingFailure.INVALID) } }
                try {
                    val info = repo.fetchTopUpInfo(instance)
                    publish { it.copy(topUpInfo = info) }
                } catch (e: CancellationException) { throw e }
                  catch (e: ApiException) { publish { it.copy(topUpInfoFailure = billingFailure(e)) } }
                  catch (_: Exception) { publish { it.copy(topUpInfoFailure = BillingFailure.INVALID) } }
                try {
                    val records = repo.fetchUserTopUps(instance)
                    publish { it.copy(topUps = records) }
                } catch (e: CancellationException) { throw e }
                  catch (e: ApiException) { publish { it.copy(topUpsFailure = billingFailure(e)) } }
                  catch (_: Exception) { publish { it.copy(topUpsFailure = BillingFailure.INVALID) } }
            } finally {
                publish { it.copy(loading = false) }
            }
        }
    }
}
