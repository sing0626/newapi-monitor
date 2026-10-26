package com.newapimonitor.ui.speedtest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.demo.DemoEngine
import com.newapimonitor.domain.Instance
import com.newapimonitor.domain.PingResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PingHistoryItem(
    val model: String,
    val latencyMs: Long,
    val ok: Boolean,
    val status: Int,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val firstTokenMs: Long? = null,
    val completionTokens: Long? = null,
    val tokensPerSecond: Double? = null,
)

data class SpeedTestState(
    val isRunning: Boolean = false,
    val currentTestingModel: String? = null,
    val modelsInput: String = "gpt-4o\ngpt-4o-mini\nclaude-3-5-sonnet-20241022\ngemini-1.5-pro\ndeepseek-chat",
    val availableModels: List<String> = emptyList(),
    val latestResults: Map<String, PingResult> = emptyMap(),
    val history: List<PingHistoryItem> = emptyList(),
    val error: String? = null,
)

/** One source is either demo or an instance ID; never use the active instance while a batch is running. */
internal fun speedTestSourceId(demo: Boolean, inst: Instance?): String? =
    if (demo) SpeedTestHistoryStore.DEMO_SOURCE else inst?.id?.let { "instance.$it" }

internal fun selectedModels(input: String): List<String> =
    input.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(5)

/** A failed model is recorded as a failed result, without preventing later models from running. */
internal suspend fun runSpeedTestBatch(
    models: List<String>,
    ping: suspend (String) -> PingResult,
    onResult: suspend (String, PingResult) -> Unit,
) {
    for (model in models.take(5)) {
        val started = System.currentTimeMillis()
        val result = try {
            ping(model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PingResult(false, System.currentTimeMillis() - started, model,
                message = e.message ?: "測速過程發生異常")
        }
        onResult(model, result)
    }
}

class SpeedTestViewModel(
    private val repo: NewApiRepository,
    private val historyStore: SpeedTestHistoryStore = SpeedTestHistoryStore(repo.store.appContext),
) : ViewModel() {

    private val _state = MutableStateFlow(SpeedTestState())
    val state: StateFlow<SpeedTestState> = _state.asStateFlow()

    private var testJob: Job? = null
    private var sourceId: String? = null

    init {
        viewModelScope.launch {
            combine(repo.store.settings, repo.store.activeInstance) { settings, inst ->
                Triple(speedTestSourceId(settings.demo, inst), settings.demo, inst)
            }.collectLatest { (source, demo, inst) ->
                if (source == sourceId) return@collectLatest
                testJob?.cancel()
                testJob = null
                sourceId = source
                _state.update {
                    it.copy(isRunning = false, currentTestingModel = null, availableModels = emptyList(),
                        latestResults = emptyMap(), history = emptyList(), error = null)
                }
                if (source == null) return@collectLatest
                try {
                    val history = historyStore.read(source)
                    if (sourceId != source) return@collectLatest
                    _state.update { it.copy(history = history) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A local storage error must not prevent testing.
                }
                try {
                    val models = if (demo) {
                        DemoEngine.start()
                        DemoEngine.distinctModels()
                    } else {
                        repo.modelsForFilter(inst)
                    }
                    if (sourceId == source) _state.update { it.copy(availableModels = models) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Model suggestions are optional.
                }
            }
        }
    }

    fun setModelsInput(input: String) {
        _state.update { it.copy(modelsInput = input) }
    }

    fun addModelToInput(model: String) {
        if (_state.value.isRunning) return
        val current = selectedModels(_state.value.modelsInput)
        if (model !in current && current.size < 5) {
            _state.update { it.copy(modelsInput = (current + model).joinToString("\n")) }
        }
    }

    fun startAllAvailableModels() {
        val models = _state.value.availableModels.distinct().filter { it.isNotBlank() }
        if (models.isEmpty()) {
            _state.update { it.copy(error = "沒有可用模型可以測試") }
        } else if (models.size > 5) {
            _state.update { it.copy(error = "可用模型超過 5 個，請手動選擇最多 5 個") }
        } else {
            startModels(models)
        }
    }

    fun startSpeedTest() {
        val models = selectedModels(_state.value.modelsInput)
        if (models.isEmpty()) {
            _state.update { it.copy(error = "請輸入至少一個模型名稱") }
            return
        }
        startModels(models)
    }

    private fun startModels(models: List<String>) {
        if (_state.value.isRunning) return
        val demo = repo.store.settings.value.demo
        val inst = repo.store.activeInstance.value
        val source = speedTestSourceId(demo, inst)
        if (!demo && (inst == null || inst.apiKey.isBlank())) {
            _state.update { it.copy(error = "測速需要提供有效嘅 API Key（sk-…）") }
            return
        }
        if (source == null || source != sourceId) return // Source switch still loading.

        _state.update { it.copy(isRunning = true, error = null, latestResults = emptyMap()) }
        testJob = viewModelScope.launch {
            try {
                runSpeedTestBatch(models, ping = { model ->
                    _state.update { it.copy(currentTestingModel = model) }
                    if (demo) {
                        delay(220)
                        DemoEngine.pingModel(model)
                    } else repo.pingModel(inst!!, model)
                }, onResult = { model, result ->
                    if (sourceId != source || !currentCoroutineContext().isActive) return@runSpeedTestBatch
                    val item = PingHistoryItem(result.model, result.latencyMs, result.ok, result.status, result.message)
                    _state.update { s ->
                        s.copy(latestResults = s.latestResults + (model to result),
                            history = (listOf(item) + s.history).take(SpeedTestHistoryStore.MAX_ITEMS))
                    }
                    try {
                        val persisted = historyStore.add(source, item)
                        if (sourceId == source) _state.update { it.copy(history = persisted) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Keep the in-memory result if persistence is temporarily unavailable.
                    }
                })
            } catch (_: CancellationException) {
                // Cancelled by the user or by a source switch.
            } finally {
                if (sourceId == source && testJob == currentCoroutineContext()[Job]) {
                    testJob = null
                    _state.update { it.copy(isRunning = false, currentTestingModel = null) }
                }
            }
        }
    }

    fun stopSpeedTest() {
        testJob?.cancel()
        testJob = null
        _state.update { it.copy(isRunning = false, currentTestingModel = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
