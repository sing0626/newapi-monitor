package com.newapimonitor.alert

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.newapimonitor.data.store.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class AlertRecord(
    val id: String = UUID.randomUUID().toString(),
    val sourceId: String,
    val title: String,
    val body: String,
    val type: String,
    val time: Long = System.currentTimeMillis(),
    val read: Boolean = false,
)

/** Local, bounded alert audit trail; callers supply controlled text, never API errors or keys. */
class AlertHistoryStore(private val context: Context) {
    private val key = stringPreferencesKey("alert.history.v1")
    private val json = Json { ignoreUnknownKeys = true }
    val records = context.dataStore.data.map { parse(it[key]) }
    suspend fun add(record: AlertRecord) {
        context.dataStore.edit { it[key] = json.encodeToString((listOf(record) + parse(it[key])).take(200)) }
    }
    suspend fun markRead() { context.dataStore.edit { it[key] = json.encodeToString(parse(it[key]).map { r -> r.copy(read = true) }) } }
    suspend fun clear() { context.dataStore.edit { it.remove(key) } }
    suspend fun removeSource(id: String) {
        context.dataStore.edit { it[key] = json.encodeToString(parse(it[key]).filterNot { r -> r.sourceId == id }) }
    }
    private fun parse(raw: String?): List<AlertRecord> =
        runCatching { json.decodeFromString<List<AlertRecord>>(raw ?: "[]") }.getOrDefault(emptyList())
}
