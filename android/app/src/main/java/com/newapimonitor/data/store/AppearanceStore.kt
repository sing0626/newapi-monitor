package com.newapimonitor.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Appearance(val language: String = "yue", val mode: String = "dark", val accent: String = "purple") {
    companion object {
        val languages = setOf("yue", "zh-TW", "zh-CN", "en")
        val modes = setOf("dark", "oled", "light")
        val accents = setOf("purple", "blue", "green", "orange")
    }
}

class AppearanceStore(private val context: Context) {
    private val languageKey = stringPreferencesKey("appearance.language")
    private val modeKey = stringPreferencesKey("appearance.mode")
    private val accentKey = stringPreferencesKey("appearance.accent")
    val appearance: Flow<Appearance> = context.dataStore.data.map { prefs ->
        Appearance(
            prefs[languageKey]?.takeIf { it in Appearance.languages } ?: "yue",
            prefs[modeKey]?.takeIf { it in Appearance.modes } ?: "dark",
            prefs[accentKey]?.takeIf { it in Appearance.accents } ?: "purple",
        )
    }
    suspend fun setLanguage(value: String) { if (value in Appearance.languages) context.dataStore.edit { it[languageKey] = value } }
    suspend fun setMode(value: String) { if (value in Appearance.modes) context.dataStore.edit { it[modeKey] = value } }
    suspend fun setAccent(value: String) { if (value in Appearance.accents) context.dataStore.edit { it[accentKey] = value } }
}
