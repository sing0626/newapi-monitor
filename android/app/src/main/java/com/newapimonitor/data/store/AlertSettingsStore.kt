package com.newapimonitor.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.newapimonitor.domain.AlertSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** AlertSettings 持久化 DTO（全域一份，跟 SettingsDto 同款 JSON string key） */
@Serializable
data class AlertSettingsDto(
    val enabled: Boolean = false,
    val balanceBelowUsd: Double? = null,
    val dailySpendAboveUsd: Double? = null,
    val spikeMultiplier: Double = 3.0,
    val cooldownMinutes: Int = 60,
    val notifyTokenBanned: Boolean = true,
    val monthlyBudgetUsd: Double? = null,
    val notifyErrorRate: Boolean = false,
    val notifyTokenExpiry: Boolean = false,
    val persistentStatus: Boolean = false,
)

/**
 * 警報設定（全域，存入共用 DataStore）：AlertWorker 同之後嘅設定頁共用。
 * DataStore delegate 係 process 內單例，呢度每處 new 一個 store object 冇問題。
 */
class AlertSettingsStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _settings = MutableStateFlow(AlertSettings())
    val settings: StateFlow<AlertSettings> = _settings.asStateFlow()

    /** 載入一次（worker 開工 / Application 排程前呼叫）；已載過就唔會重讀 */
    suspend fun loadOnce() {
        val raw = context.dataStore.data.first()[key]
        _settings.value = try {
            if (raw != null) sanitize(fromDto(json.decodeFromString<AlertSettingsDto>(raw))) else AlertSettings()
        } catch (e: Exception) {
            AlertSettings()
        }
    }

    private fun fromDto(d: AlertSettingsDto): AlertSettings = AlertSettings(
        enabled = d.enabled,
        balanceBelowUsd = d.balanceBelowUsd,
        dailySpendAboveUsd = d.dailySpendAboveUsd,
        spikeMultiplier = d.spikeMultiplier,
        cooldownMinutes = d.cooldownMinutes,
        notifyTokenBanned = d.notifyTokenBanned,
        monthlyBudgetUsd = d.monthlyBudgetUsd,
        notifyErrorRate = d.notifyErrorRate,
        notifyTokenExpiry = d.notifyTokenExpiry,
        persistentStatus = d.persistentStatus,
    )

    /** 讀當前值（唔快取；scheduler 之類一次性場景用） */
    suspend fun current(): AlertSettings {
        loadOnce()
        return _settings.value
    }

    suspend fun update(transform: (AlertSettings) -> AlertSettings) {
        var next: AlertSettings? = null
        context.dataStore.edit { p ->
            val current = runCatching { p[key]?.let { sanitize(fromDto(json.decodeFromString<AlertSettingsDto>(it))) } }
                .getOrNull() ?: AlertSettings()
            val changed = sanitize(transform(current))
            p[key] = json.encodeToString(
                AlertSettingsDto(
                    enabled = changed.enabled,
                    balanceBelowUsd = changed.balanceBelowUsd,
                    dailySpendAboveUsd = changed.dailySpendAboveUsd,
                    spikeMultiplier = changed.spikeMultiplier,
                    cooldownMinutes = changed.cooldownMinutes,
                    notifyTokenBanned = changed.notifyTokenBanned,
                    monthlyBudgetUsd = changed.monthlyBudgetUsd,
                    notifyErrorRate = changed.notifyErrorRate,
                    notifyTokenExpiry = changed.notifyTokenExpiry,
                    persistentStatus = changed.persistentStatus,
                )
            )
            next = changed
        }
        _settings.value = checkNotNull(next)
    }

    /** 防禦式清洗：負數門檻／倍數、荒謬 cooldown 一律收返合理範圍 */
    internal fun sanitize(s: AlertSettings): AlertSettings = AlertSettings(
        enabled = s.enabled,
        balanceBelowUsd = s.balanceBelowUsd?.takeIf { it.isFinite() && it >= 0 },
        dailySpendAboveUsd = s.dailySpendAboveUsd?.takeIf { it.isFinite() && it > 0 },
        spikeMultiplier = s.spikeMultiplier.takeIf { it.isFinite() && it >= 0 } ?: 3.0,
        cooldownMinutes = s.cooldownMinutes.coerceIn(1, 24 * 60),
        notifyTokenBanned = s.notifyTokenBanned,
        monthlyBudgetUsd = s.monthlyBudgetUsd?.takeIf { it.isFinite() && it > 0 },
        notifyErrorRate = s.notifyErrorRate,
        notifyTokenExpiry = s.notifyTokenExpiry,
        persistentStatus = s.persistentStatus,
    )

    companion object {
        private val key = stringPreferencesKey("alert_settings.v1")
    }
}
