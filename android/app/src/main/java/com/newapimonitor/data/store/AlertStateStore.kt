package com.newapimonitor.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 警報引擎狀態（per instance，AlertEngine.evaluate 輸入／輸出）：
 *  - lastFiredMs：各 AlertType 上次觸發時間（cooldown 用）
 *  - armedBalanceOk：餘額警報 arm 狀態（false = 已觸發未回復，避免狂彈）
 *  - lastBannedTokenIds：上次觀察到嘅 status==3 令牌 id（偵測「新」封禁）
 */
data class AlertState(
    val lastFiredMs: Map<String, Long> = emptyMap(),
    val armedBalanceOk: Boolean = true,
    val lastBannedTokenIds: Set<Long> = emptySet(),
    val tokenExpiryPhases: Map<String, Int> = emptyMap(),
)

@Serializable
private data class AlertStateDto(
    val lastFiredMs: Map<String, Long> = emptyMap(),
    val armedBalanceOk: Boolean = true,
    val lastBannedTokenIds: Set<Long> = emptySet(),
    val tokenExpiryPhases: Map<String, Int> = emptyMap(),
)

/** 警報狀態持久化（每實例一個 DataStore key；刪除實例時 clear） */
class AlertStateStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun read(instId: String): AlertState {
        val raw = context.dataStore.data.first()[keyFor(instId)] ?: return AlertState()
        return try {
            val d = json.decodeFromString<AlertStateDto>(raw)
            AlertState(d.lastFiredMs, d.armedBalanceOk, d.lastBannedTokenIds, d.tokenExpiryPhases)
        } catch (e: Exception) {
            AlertState()
        }
    }

    suspend fun write(instId: String, state: AlertState) {
        context.dataStore.edit { p ->
            p[keyFor(instId)] = json.encodeToString(
                AlertStateDto(
                    lastFiredMs = state.lastFiredMs,
                    armedBalanceOk = state.armedBalanceOk,
                    lastBannedTokenIds = state.lastBannedTokenIds,
                    tokenExpiryPhases = state.tokenExpiryPhases,
                )
            )
        }
    }

    suspend fun clear(instId: String) {
        context.dataStore.edit { it.remove(keyFor(instId)) }
    }

    suspend fun clearAll() {
        context.dataStore.edit { p ->
            for (k in p.asMap().keys) {
                if (k.name.startsWith(KEY_PREFIX)) p.remove(k)
            }
        }
    }

    private fun keyFor(instId: String) = stringPreferencesKey(KEY_PREFIX + instId)

    companion object {
        private const val KEY_PREFIX = "alert_state.v1."
    }
}
