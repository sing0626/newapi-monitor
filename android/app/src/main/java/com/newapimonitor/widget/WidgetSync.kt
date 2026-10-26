package com.newapimonitor.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.newapimonitor.MainActivity
import com.newapimonitor.R
import com.newapimonitor.domain.DashboardSnapshot
import com.newapimonitor.util.Fmt
import com.newapimonitor.data.store.AppearanceStore
import com.newapimonitor.ui.theme.translated
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** 小工具一幀要渲染嘅數據（純字串，遠端視圖同數據面完全解耦） */
data class WidgetData(
    val title: String,
    val label: String,
    val balanceText: String,
    val balanceSmall: Boolean,
    val todayText: String,
    val updatedText: String,
    val dotGreen: Boolean,
    val budgetText: String = "",
    val budgetProgress: Int? = null,
) {
    companion object {
        /** 無實例／無任何令牌 → 引導去 App 設定 */
        fun setup(): WidgetData = WidgetData(
            title = "NewAPI Monitor",
            label = "仲未設定實例",
            balanceText = "—",
            balanceSmall = false,
            todayText = "—",
            updatedText = "撳一下開 App 設定實例",
            dotGreen = false,
        )

        /** 有實例但拉數失敗／超時 → 離線狀態 */
        fun error(title: String): WidgetData = WidgetData(
            title = title.ifBlank { "NewAPI Monitor" },
            label = "剩餘額度",
            balanceText = "—",
            balanceSmall = false,
            todayText = "—",
            updatedText = "離線",
            dotGreen = false,
        )
    }
}

/** 快照 → 小工具數據（儀表板每次輪詢成功後同步） */
fun widgetDataFromSnapshot(snap: DashboardSnapshot): WidgetData {
    val balance = snap.balanceUsd
    return WidgetData(
        title = snap.name ?: "NewAPI Monitor",
        label = "剩餘額度",
        balanceText = if (balance != null && balance.isFinite()) Fmt.usd(balance, 2) else "—",
        balanceSmall = false,
        todayText = snap.todayUsd?.let { (if (snap.todayTruncated) "約 " else "") + Fmt.usd(it, 2) } ?: "—",
        updatedText = "更新 " + Fmt.clock(snap.fetchedAtMs / 1000),
        dotGreen = true,
    )
}

/** 小工具渲染 + 推送（App 內同步同 onUpdate 共用） */
object WidgetSync {

    fun buildViews(context: Context, data: WidgetData, widgetId: Int = 0): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_balance)
        val language = runCatching { runBlocking { AppearanceStore(context).appearance.first().language } }.getOrDefault("yue")
        rv.setTextViewText(R.id.widget_title, data.title)
        rv.setTextViewText(R.id.widget_label, translated(data.label, language))
        rv.setTextViewText(R.id.widget_balance, data.balanceText)
        rv.setTextViewText(R.id.widget_today, data.todayText)
        rv.setTextViewText(R.id.widget_budget, data.budgetText)
        rv.setViewVisibility(R.id.widget_budget, if (data.budgetText.isBlank()) android.view.View.GONE else android.view.View.VISIBLE)
        rv.setViewVisibility(R.id.widget_budget_progress, if (data.budgetProgress == null) android.view.View.GONE else android.view.View.VISIBLE)
        rv.setProgressBar(R.id.widget_budget_progress, 100, data.budgetProgress ?: 0, false)
        rv.setTextViewText(R.id.widget_updated, translated(data.updatedText, language))
        rv.setTextViewTextSize(R.id.widget_balance, android.util.TypedValue.COMPLEX_UNIT_SP, if (data.balanceSmall) 18f else 30f)
        rv.setInt(R.id.widget_dot, "setBackgroundResource", if (data.dotGreen) R.drawable.widget_dot_ok else R.drawable.widget_dot_off)
        // 撳任何位置開 App
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, widgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        rv.setOnClickPendingIntent(R.id.widget_root, pi)
        if (widgetId != 0) {
            val configure = Intent(context, WidgetConfigureActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            rv.setOnClickPendingIntent(R.id.widget_title, PendingIntent.getActivity(context, 10000 + widgetId, configure,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return rv
    }

    /** 推去所有已加到桌面嘅小工具 */
    fun push(context: Context, data: WidgetData) {
        val mgr = AppWidgetManager.getInstance(context) ?: return
        val ids = mgr.getAppWidgetIds(ComponentName(context, BalanceWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val rv = buildViews(context, data)
        mgr.updateAppWidget(ids, rv)
    }

    /** Legacy active widgets can follow the detailed dashboard; configured widgets use monitor snapshots. */
    fun pushFromSnapshot(context: Context, snap: DashboardSnapshot) {
        if (!snap.ok) return
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, BalanceWidgetProvider::class.java))
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            for (id in ids) {
                val choice = WidgetOptionsStore(context).read(id)
                if (choice.source == "active") {
                    val data = widgetDataFromSnapshot(snap)
                    mgr.updateAppWidget(id, buildViews(context, if (choice.hideMoney) data.copy(balanceText = "••••", todayText = "••••") else data, id))
                }
            }
        }
    }
}
