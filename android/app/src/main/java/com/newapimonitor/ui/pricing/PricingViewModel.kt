package com.newapimonitor.ui.pricing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newapimonitor.data.api.ApiException
import com.newapimonitor.data.api.NewApiClient
import com.newapimonitor.data.api.OfficialCatalog
import com.newapimonitor.data.api.OfficialGroup
import com.newapimonitor.data.api.PricingParsing
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.domain.Instance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun pricingSource(demo: Boolean, instance: Instance?): String =
    if (demo) "demo" else instance?.id ?: "none"

/** Error code is intentionally structured; never render an upstream message that may contain credentials. */
enum class PricingFailure { AUTH, FORBIDDEN, MISSING, NETWORK, INVALID }

data class PricingState(
    val source: String = "none",
    val instanceName: String? = null,
    val demo: Boolean = false,
    val needsAccessToken: Boolean = false,
    val loading: Boolean = false,
    val catalog: OfficialCatalog? = null,
    val userGroups: List<OfficialGroup> = emptyList(),
    val catalogFailure: PricingFailure? = null,
    val groupsFailure: PricingFailure? = null,
)

internal fun pricingFailure(e: ApiException): PricingFailure = when (e.status) {
    401 -> PricingFailure.AUTH
    403 -> PricingFailure.FORBIDDEN
    404 -> PricingFailure.MISSING
    else -> PricingFailure.NETWORK
}

class PricingViewModel(private val repo: NewApiRepository) : ViewModel() {
    private val _state = MutableStateFlow(PricingState())
    val state: StateFlow<PricingState> = _state.asStateFlow()
    private var fetchJob: Job? = null
    private var observedInstance: Instance? = null
    private var requestId = 0L
    private var visible = false

    init {
        viewModelScope.launch {
            combine(repo.store.settings, repo.store.activeInstance) { settings, instance ->
                Triple(pricingSource(settings.demo, instance), settings.demo, instance)
            }.collectLatest { (source, demo, instance) ->
                // A changed instance/credential must not show the previous account's catalog.
                if (_state.value.source == source && observedInstance == instance && _state.value.demo == demo) return@collectLatest
                observedInstance = instance
                fetchJob?.cancel()
                requestId++
                _state.value = PricingState(source = source, instanceName = instance?.name, demo = demo,
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
        val source = pricingSource(settings.demo, instance)
        observedInstance = instance
        if (!visible || settings.demo || instance == null || instance.accessToken.isBlank()) {
            _state.value = PricingState(source = source, instanceName = instance?.name, demo = settings.demo,
                needsAccessToken = instance != null && instance.accessToken.isBlank())
            return
        }
        _state.value = PricingState(source = source, instanceName = instance.name, loading = true)
        fetchJob = viewModelScope.launch {
            fun publish(change: (PricingState) -> PricingState) {
                if (visible && generation == requestId) _state.update(change)
            }
            val client = NewApiClient(instance.baseUrl, instance.accessToken, instance.apiKey,
                initialAccessStyle = instance.authStyle, initialKeyStyle = instance.authStyleKey)
            try {
                val catalog = try {
                    val parsed = PricingParsing.catalog(client.fetchPricing())
                    publish { it.copy(catalog = parsed) }
                    parsed
                } catch (e: CancellationException) { throw e }
                  catch (e: ApiException) { publish { it.copy(catalogFailure = pricingFailure(e)) }; null }
                  catch (_: Exception) { publish { it.copy(catalogFailure = PricingFailure.INVALID) }; null }
                try {
                    val groups = PricingParsing.userGroups(client.fetchSelfGroups())
                    publish { it.copy(userGroups = PricingParsing.mergeGroups(catalog?.groups.orEmpty(), groups)) }
                } catch (e: CancellationException) { throw e }
                  catch (e: ApiException) { publish { it.copy(groupsFailure = pricingFailure(e), userGroups = catalog?.groups.orEmpty()) } }
                  catch (_: Exception) { publish { it.copy(groupsFailure = PricingFailure.INVALID, userGroups = catalog?.groups.orEmpty()) } }
            } finally {
                publish { it.copy(loading = false) }
            }
        }
    }
}
