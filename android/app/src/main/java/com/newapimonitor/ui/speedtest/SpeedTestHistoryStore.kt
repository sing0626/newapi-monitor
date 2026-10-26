package com.newapimonitor.ui.speedtest

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.newapimonitor.data.store.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Local-only speed test results, kept independently for each instance and for demo mode. */
class SpeedTestHistoryStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(sourceId: String): List<PingHistoryItem> =
        parse(context.dataStore.data.first()[key(sourceId)])

    /** DataStore's edit transaction prevents concurrent writers from losing results. */
    suspend fun add(sourceId: String, item: PingHistoryItem): List<PingHistoryItem> {
        var updated = emptyList<PingHistoryItem>()
        context.dataStore.edit { prefs ->
            updated = (listOf(item) + parse(prefs[key(sourceId)])).take(MAX_ITEMS)
            prefs[key(sourceId)] = json.encodeToString(updated.map { it.toDto() })
        }
        return updated
    }

    private fun parse(raw: String?): List<PingHistoryItem> = try {
        if (raw.isNullOrBlank()) emptyList()
        else json.decodeFromString<List<ItemDto>>(raw).take(MAX_ITEMS).map { it.toItem() }
    } catch (_: Exception) {
        emptyList()
    }

    private fun key(sourceId: String) = stringPreferencesKey("speed_test_history.v1.$sourceId")

    @Serializable
    private data class ItemDto(
        val model: String,
        val latencyMs: Long,
        val ok: Boolean,
        val status: Int,
        val message: String,
        val timestamp: Long,
        val firstTokenMs: Long? = null,
        val completionTokens: Long? = null,
        val tokensPerSecond: Double? = null,
    ) {
        fun toItem() = PingHistoryItem(model, latencyMs, ok, status, message, timestamp, firstTokenMs, completionTokens, tokensPerSecond)
    }

    private fun PingHistoryItem.toDto() = ItemDto(model, latencyMs, ok, status, message, timestamp, firstTokenMs, completionTokens, tokensPerSecond)

    companion object {
        const val DEMO_SOURCE = "demo"
        const val MAX_ITEMS = 20
    }
}
