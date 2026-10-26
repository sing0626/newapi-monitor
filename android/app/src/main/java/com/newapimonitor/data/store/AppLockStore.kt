package com.newapimonitor.data.store

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 應用程式生物辨識鎖定開關（持久化到 DataStore）
 */
internal fun lockRequired(enabled: Boolean?): Boolean = enabled != false

class AppLockStore(private val context: Context) {

    val isEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_APP_LOCK] ?: false
    }

    suspend fun isEnabledOnce(): Boolean = isEnabled.first()

    suspend fun setEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_APP_LOCK] = enabled
        }
    }

    companion object {
        private val KEY_APP_LOCK = booleanPreferencesKey("app_lock.v1")
    }
}
