package com.newapimonitor.data.monitor

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.newapimonitor.data.store.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class MonitorSnapshot(
    val id: String,
    val balanceUsd: Double? = null,
    val todayUsd: Double? = null,
    val monthUsd: Double? = null,
    val balanceAt: Long = 0,
    val spendAt: Long = 0,
    val attemptedAt: Long = 0,
    val day: String = "",
    val month: String = "",
    val balanceFailure: String? = null,
    val spendFailure: String? = null,
    val sourceStamp: String = "",
) {
    fun balanceFresh(now: Long = System.currentTimeMillis()) = balanceUsd != null && balanceAt == attemptedAt && now - balanceAt < 90_000
    fun spendFresh(now: Long = System.currentTimeMillis()) = todayUsd != null && spendAt == attemptedAt && now - spendAt < 90_000
    companion object {
        fun stamp(i: com.newapimonitor.domain.Instance): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(listOf(i.baseUrl, i.accessToken, i.apiKey, i.quotaPerUnit.toString()).joinToString("\u0000").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** This cache contains amounts and timestamps only. Credentials never enter it. */
class MonitorStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("monitor.snapshots.v1")
    val snapshots = context.dataStore.data.map { parse(it[key]) }
    val budgets = context.dataStore.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            (value as? Double)?.takeIf { it.isFinite() && it > 0 && key.name.startsWith(BUDGET_PREFIX) }
                ?.let { key.name.removePrefix(BUDGET_PREFIX) to it }
        }.toMap()
    }
    suspend fun save(snapshot: MonitorSnapshot) {
        context.dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(parse(prefs[key]).filterNot { it.id == snapshot.id } + snapshot)
        }
    }
    suspend fun setBudget(id: String, value: Double?) {
        context.dataStore.edit { prefs ->
            val k = doublePreferencesKey(BUDGET_PREFIX + id)
            if (value != null && value.isFinite() && value > 0) prefs[k] = value else prefs.remove(k)
            // Changing the budget rearms its monthly threshold notifications.
            prefs.asMap().keys.filter { it.name.startsWith("monitor.fired.$id.") }.forEach { prefs.remove(it) }
        }
    }
    suspend fun claimBudgetPhase(id: String, month: String, phase: Int): Boolean {
        var claimed = false
        context.dataStore.edit { prefs ->
            val k = stringPreferencesKey("monitor.fired.$id.$month.$phase")
            if (prefs[k] == null) { prefs[k] = "1"; claimed = true }
        }
        return claimed
    }
    suspend fun remove(id: String) {
        context.dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(parse(prefs[key]).filterNot { it.id == id })
            prefs.asMap().keys.filter { it.name == BUDGET_PREFIX + id || it.name.startsWith("monitor.fired.$id.") }
                .forEach { prefs.remove(it) }
        }
    }
    private fun parse(raw: String?): List<MonitorSnapshot> =
        runCatching { json.decodeFromString<List<MonitorSnapshot>>(raw ?: "[]") }.getOrDefault(emptyList())
    companion object { const val BUDGET_PREFIX = "monitor.budget." }
}
