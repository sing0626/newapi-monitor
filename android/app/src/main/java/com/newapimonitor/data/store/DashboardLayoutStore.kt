package com.newapimonitor.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 儀表板卡片顯示偏好（收埋咗邊啲卡）：
 *  - 持久化 stringSetPreferencesKey("dashboard_layout.v1")（JSON-free string set）
 *  - 跟 AppearanceStore 同款：DataStore-backed Flow，設定頁同儀表板各自 new 一個
 *    store 實例都得（DataStore delegate 係 process 內單例），任何實例改寫 → 所有收集者即時跟
 *  - sanitize：未知 id 一律剔走；"balance"（餘額 hero）永遠可見，唔設開關
 */
class DashboardLayoutStore(private val context: Context) {

    /** 收埋咗嘅卡片 id 集（DashboardScreen 以此跳過渲染；不在集內 = 顯示） */
    val hiddenCardIds: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        sanitize(prefs[key] ?: emptySet())
    }

    /** 收埋／重新顯示一張卡；唔存在或唔可以收埋嘅 id 靜默忽略 */
    suspend fun setCardHidden(id: String, hidden: Boolean) {
        if (id !in HIDEABLE_IDS) return
        context.dataStore.edit { prefs ->
            val current = sanitize(prefs[key] ?: emptySet())
            prefs[key] = if (hidden) current + id else current - id
        }
    }

    companion object {
        /** 儀表板內容欄全部卡片 id（渲染順序；設定頁標籤見 SettingsScreen dashboardCardLabels） */
        val ALL_IDS = listOf(
            "status", "instances", "balance", "budget", "report",
            "week", "history", "models", "cost", "heatmap", "stability", "feed",
        )

        /** 餘額 hero 永遠顯示，唔提供開關 */
        const val BALANCE_ID = "balance"

        /** 可以收埋嘅卡（設定頁 SwitchRow 順序 = 儀表板渲染順序扣起 balance） */
        val HIDEABLE_IDS = ALL_IDS.filter { it != BALANCE_ID }

        private val key = stringSetPreferencesKey("dashboard_layout.v1")

        /** 防禦式清洗：剔走未知 id 同 balance（永遠可見） */
        internal fun sanitize(raw: Set<String>): Set<String> =
            raw.filterTo(LinkedHashSet()) { it in ALL_IDS && it != BALANCE_ID }
    }
}
