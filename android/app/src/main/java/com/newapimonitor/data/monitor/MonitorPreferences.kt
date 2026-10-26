package com.newapimonitor.data.monitor

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.newapimonitor.data.store.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalTime

@Serializable data class ProbeSuite(val name: String, val models: List<String>)
@Serializable data class MonitorPreferences(
    val compact: Boolean = false,
    val hideMoney: Boolean = false,
    val pinned: Set<String> = emptySet(),
    val order: List<String> = emptyList(),
    val excluded: Set<String> = emptySet(),
    val favorites: Set<String> = emptySet(),
    val suites: List<ProbeSuite> = emptyList(),
) {
    fun <T> sorted(items: List<T>, id: (T) -> String): List<T> = items.sortedWith(
        compareBy<T> { if (id(it) in pinned) 0 else 1 }.thenBy { order.indexOf(id(it)).takeIf { n -> n >= 0 } ?: Int.MAX_VALUE })
}

@Serializable data class InstanceAlertRule(
    val enabled: Boolean = false,
    val balanceBelow: Double? = null,
    val dailyAbove: Double? = null,
    val quietStart: String = "",
    val quietEnd: String = "",
    val cooldownMinutes: Int = 60,
) {
    fun validate(): InstanceAlertRule {
        require(balanceBelow == null || balanceBelow.isFinite() && balanceBelow >= 0)
        require(dailyAbove == null || dailyAbove.isFinite() && dailyAbove > 0)
        require(cooldownMinutes in 1..1440)
        require((quietStart.isBlank() && quietEnd.isBlank()) ||
            (LocalTime.parse(quietStart) != LocalTime.parse(quietEnd)))
        return this
    }
    fun quiet(now: LocalTime): Boolean {
        if (quietStart.isBlank() || quietEnd.isBlank()) return false
        val start = LocalTime.parse(quietStart); val end = LocalTime.parse(quietEnd)
        return if (start < end) now >= start && now < end else now >= start || now < end
    }
}

class MonitorPreferencesStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefsKey = stringPreferencesKey("monitor.preferences.v1")
    private val rulesKey = stringPreferencesKey("monitor.rules.v1")
    val preferences = context.dataStore.data.map { parsePrefs(it[prefsKey]) }
    val rules = context.dataStore.data.map { parseRules(it[rulesKey]) }
    suspend fun update(change: (MonitorPreferences) -> MonitorPreferences) {
        context.dataStore.edit { it[prefsKey] = json.encodeToString(change(parsePrefs(it[prefsKey]))) }
    }
    suspend fun saveRule(id: String, rule: InstanceAlertRule) {
        rule.validate()
        context.dataStore.edit { p ->
            p[rulesKey] = json.encodeToString(parseRules(p[rulesKey]) + (id to rule))
            p.asMap().keys.filter { it.name.startsWith("monitor.rule.fired.$id.") }.forEach { p.remove(it) }
        }
    }
    suspend fun claim(id: String, kind: String, rule: InstanceAlertRule, now: Long): Boolean {
        var claimed = false
        context.dataStore.edit { p ->
            val key = stringPreferencesKey("monitor.rule.fired.$id.$kind")
            val last = p[key]?.toLongOrNull()
            if (last == null || now - last >= rule.cooldownMinutes * 60_000L) {
                p[key] = now.toString(); claimed = true
            }
        }
        return claimed
    }
    suspend fun remove(id: String) {
        update { it.copy(pinned = it.pinned - id, excluded = it.excluded - id, order = it.order - id) }
        context.dataStore.edit { p -> p[rulesKey] = json.encodeToString(parseRules(p[rulesKey]) - id)
            p.asMap().keys.filter { it.name.startsWith("monitor.rule.fired.$id.") }.forEach { p.remove(it) } }
    }
    private fun parsePrefs(s: String?) = runCatching { json.decodeFromString<MonitorPreferences>(s ?: "{}") }.getOrDefault(MonitorPreferences())
    private fun parseRules(s: String?) = runCatching { json.decodeFromString<Map<String, InstanceAlertRule>>(s ?: "{}")
        .filterValues { runCatching { it.validate() }.isSuccess } }.getOrDefault(emptyMap())
}
