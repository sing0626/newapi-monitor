package com.newapimonitor.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.newapimonitor.MainActivity
import com.newapimonitor.NewApiMonitorApp
import com.newapimonitor.data.store.AppearanceStore
import com.newapimonitor.data.store.HistoryStore
import com.newapimonitor.ui.theme.translated
import com.newapimonitor.util.Fmt
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Quick Settings「餘額」磁貼：
 *  - 淨係讀本機快取（HistoryStore 最後一個餘額取樣），零網絡——widget 嘅
 *    fetchWidgetData 係 status＋user/self＋今日日誌三連擊（最長 5s），唔可以搬入 QS 磁貼
 *  - 快取來源同儀表板一致：DashboardViewModel 每次輪詢成功都會 addSample 入
 *    HistoryStore（demo 模式記喺 "demo" key）；AlertWorker 背景檢查都會記
 *  - language 用 AppearanceStore runBlocking 短讀（同 AlertWorker／WidgetSync 同款 pattern，
 *    非 Composable translated(key, language)）
 *  - 讀數全程封頂 <1s，唔會卡 QS binder 線程
 */
class BalanceTileService : TileService() {

    override fun onTileAdded() {
        updateTile()
    }

    override fun onTileRemoved() {
        updateTile()
    }

    override fun onStartListening() {
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // API 34+ 一律用 PendingIntent 版 startActivityAndCollapse（Intent 版已 deprecated）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    /** 快取讀數 → 畫磁貼（STATE_ACTIVE；無快取顯示 —） */
    private fun updateTile() {
        val tile = qsTile ?: return
        val appContext = applicationContext
        val language = runCatching {
            runBlocking { AppearanceStore(appContext).appearance.first().language }
        }.getOrDefault("yue")

        val app = appContext as? NewApiMonitorApp
        val balanceUsd: Double? = if (app == null) null else runCatching {
            runBlocking {
                // 程序啱啱生嗰陣 store 未必 load 好 — 短等一下，封頂 900ms（磁貼唔可以長堵）
                withTimeoutOrNull(900L) { app.store.ready.filter { it }.first() }
                val sourceKey = if (app.store.settings.value.demo) {
                    "demo"
                } else {
                    app.store.activeInstance.value?.id
                }
                sourceKey?.let { HistoryStore(appContext).historyOnce(it).lastOrNull()?.v }
            }
        }.getOrNull()

        tile.state = Tile.STATE_ACTIVE
        tile.label = translated("餘額", language)
        val balanceText = balanceUsd?.takeIf { it.isFinite() }?.let { Fmt.usd(it, 2) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 今日消費冇本機快取（widget 快照唔持久化）— 照 spec 省略「今日 $Y」
            tile.subtitle = balanceText
        } else {
            balanceText?.let { tile.setContentDescription(it) }
        }
        tile.updateTile()
    }
}
