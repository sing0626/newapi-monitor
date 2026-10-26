package com.newapimonitor.widget

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.newapimonitor.data.store.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class WidgetChoice(val source: String = "active", val hideMoney: Boolean = false)
class WidgetOptionsStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun read(id: Int): WidgetChoice = runCatching {
        json.decodeFromString<WidgetChoice>(context.dataStore.data.first()[key(id)] ?: "{}")
    }.getOrDefault(WidgetChoice())
    suspend fun save(id: Int, choice: WidgetChoice) { context.dataStore.edit { it[key(id)] = json.encodeToString(choice) } }
    suspend fun remove(id: Int) { context.dataStore.edit { it.remove(key(id)) } }
    private fun key(id: Int) = stringPreferencesKey("widget.choice.v1.$id")
}
