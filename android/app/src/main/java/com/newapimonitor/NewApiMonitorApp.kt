package com.newapimonitor

import android.app.Application
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.newapimonitor.alert.AlertScheduler
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.data.store.AlertSettingsStore
import com.newapimonitor.data.store.AppLockStore
import com.newapimonitor.data.store.AppearanceStore
import com.newapimonitor.data.store.HistoryStore
import com.newapimonitor.data.store.InstanceRepository
import com.newapimonitor.ui.TAB_DASHBOARD
import com.newapimonitor.ui.TAB_SPEEDTEST
import com.newapimonitor.ui.TAB_TOKENS
import com.newapimonitor.ui.theme.translated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Application：持有 repository 單例，啟動時載入本機實例／設定＋照警報設定排程背景檢查 */
open class NewApiMonitorApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    protected open fun createStore() = InstanceRepository(this)
    val store: InstanceRepository by lazy { createStore() }
    val repo: NewApiRepository by lazy { NewApiRepository(store) }
    val alertSettingsStore: AlertSettingsStore by lazy { AlertSettingsStore(this) }
    val historyStore: HistoryStore by lazy { HistoryStore(this) }
    val appLockStore: AppLockStore by lazy { AppLockStore(this) }
    val appearanceStore: AppearanceStore by lazy { AppearanceStore(this) }

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            store.load()
            // 警報設定載入後照 enabled 排程／取消（WorkManager 未初始化等環境問題靜默跳過）
            runCatching {
                alertSettingsStore.loadOnce()
                AlertScheduler.ensure(this@NewApiMonitorApp, alertSettingsStore.settings.value.enabled)
            }
        }
        // 動態捷徑（長撳 App icon）：每次進程啟動重設一次（語言改動靠下次冷啟動自然刷新）
        appScope.launch { runCatching { setupDynamicShortcuts() } }
    }

    /** 儀表板／令牌／模型探針三條捷徑，label 用 translated(key, language) 非 Composable 翻譯 */
    private suspend fun setupDynamicShortcuts() {
        val language = appearanceStore.appearance.first().language
        fun tabIntent(tab: String) = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setPackage(packageName)
            putExtra(MainActivity.EXTRA_TAB, tab)
        }
        val icon = IconCompat.createWithResource(this, R.mipmap.ic_launcher)
        val shortcuts = listOf(
            ShortcutInfoCompat.Builder(this, "tab-dashboard")
                .setShortLabel(translated("儀表板", language))
                .setIcon(icon)
                .setIntent(tabIntent(TAB_DASHBOARD))
                .build(),
            ShortcutInfoCompat.Builder(this, "tab-tokens")
                .setShortLabel(translated("令牌", language))
                .setIcon(icon)
                .setIntent(tabIntent(TAB_TOKENS))
                .build(),
            ShortcutInfoCompat.Builder(this, "tab-speedtest")
                .setShortLabel(translated("模型探針", language))
                .setIcon(icon)
                .setIntent(tabIntent(TAB_SPEEDTEST))
                .build(),
        )
        ShortcutManagerCompat.setDynamicShortcuts(this, shortcuts)
    }
}
