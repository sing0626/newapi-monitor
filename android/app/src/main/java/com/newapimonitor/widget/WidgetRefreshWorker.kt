package com.newapimonitor.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.newapimonitor.NewApiMonitorApp
import com.newapimonitor.data.monitor.*
import com.newapimonitor.demo.DemoEngine
import com.newapimonitor.domain.Instance
import com.newapimonitor.util.Fmt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.YearMonth

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as NewApiMonitorApp
        app.store.ready.first { it }
        try {
            renderCached(app)
            if (!app.store.settings.value.demo) withTimeout(120_000) {
                val ids = AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, BalanceWidgetProvider::class.java))
                val choices = ids.map { WidgetOptionsStore(app).read(it) }
                val instances = app.store.instances.value
                val activeId = app.store.settings.value.activeId ?: instances.firstOrNull()?.id
                val targets = instances.filter { source -> choices.any { it.source == "all" || it.source == source.id || it.source == "active" && source.id == activeId } }
                if (targets.isNotEmpty()) {
                    val monitor = MonitorService(app.store)
                    monitor.refreshAll(targets, monitor.cache.snapshots.first())
                }
            }
            renderCached(app)
        } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); renderCached(app)
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { renderCached(app) }
        return Result.success()
    }
    companion object {
        suspend fun renderCached(context: Context) {
            val app = context.applicationContext as? NewApiMonitorApp ?: return
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, BalanceWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val cache = MonitorStore(app)
            val rows = cache.snapshots.first(); val budgets = cache.budgets.first()
            val options = MonitorPreferencesStore(app).preferences.first()
            ids.forEach { id ->
                val choice = WidgetOptionsStore(app).read(id)
                val data = if (app.store.settings.value.demo) {
                    DemoEngine.start()
                    widgetDataFromSnapshot(DemoEngine.snapshot()).let {
                        it.copy(title = "離線演示", balanceText = if (choice.hideMoney) "••••" else it.balanceText, todayText = if (choice.hideMoney) "••••" else it.todayText)
                    }
                } else widgetDataForChoice(choice, app.store.instances.value, app.store.settings.value.activeId, rows, budgets, options)
                manager.updateAppWidget(id, WidgetSync.buildViews(app, data, id))
            }
        }
    }
}

fun widgetDataForChoice(choice: WidgetChoice, sources: List<Instance>, activeId: String?, snapshots: List<MonitorSnapshot>,
    budgets: Map<String, Double>, options: MonitorPreferences, now: Long = System.currentTimeMillis()): WidgetData {
    val selected = when (choice.source) {
        "all" -> sources
        "active" -> sources.firstOrNull { it.id == activeId }?.let(::listOf) ?: sources.take(1)
        else -> sources.filter { it.id == choice.source }
    }
    if (selected.isEmpty()) return WidgetData.setup().copy(updatedText = "實例未設定或已移除，撳標題重新設定")
    val rows = snapshots.filter { row -> selected.any { it.id == row.id && MonitorSnapshot.stamp(it) == row.sourceStamp } }
        .map { row -> row.copy(todayUsd = row.todayUsd.takeIf { row.day == LocalDate.now().toString() }, monthUsd = row.monthUsd.takeIf { row.month == YearMonth.now().toString() }) }
    val eligible = rows.filter { choice.source != "all" || it.id !in options.excluded }
    val fresh = eligible.filter { it.balanceFresh(now) }
    val balance = if (choice.source == "all") fresh.takeIf { it.isNotEmpty() }?.sumOf { it.balanceUsd!! } else eligible.firstOrNull()?.balanceUsd
    val spent = rows.filter { it.spendFresh(now) }.takeIf { it.isNotEmpty() }?.sumOf { it.todayUsd!! }
    val spendCount = rows.count { it.spendFresh(now) }
    val budgetSources = selected.filter { it.id in budgets }
    val months = budgetSources.map { source -> rows.firstOrNull { it.id == source.id && it.spendFresh(now) }?.monthUsd }
    val limit = budgetSources.sumOf { budgets[it.id]!! }
    val ratio = if (months.isNotEmpty() && months.all { it != null } && limit > 0) months.sumOf { it!! } / limit else null
    val latest = eligible.maxOfOrNull { it.balanceAt } ?: 0L
    val totalCount = selected.count { choice.source != "all" || it.id !in options.excluded }
    val balanceLabel = if (choice.source == "all") "總餘額" + (if (fresh.size < totalCount) " ${fresh.size}/$totalCount" else "")
        else if (fresh.isEmpty()) "剩餘額度（舊）" else "剩餘額度"
    return WidgetData(title = if (choice.source == "all") "全部實例" else selected.first().name,
        label = balanceLabel,
        balanceText = if (choice.hideMoney) "••••" else balance?.let(Fmt::usdCompact) ?: "—", balanceSmall = false,
        todayText = if (choice.hideMoney) "••••" else (spent?.let(Fmt::usdCompact) ?: "—") +
            if (choice.source == "all" && spendCount < selected.size) " $spendCount/${selected.size}" else "",
        updatedText = if (latest > 0) "更新 ${Fmt.logTime(latest / 1000)}" else "等待更新",
        dotGreen = fresh.size == totalCount && totalCount > 0,
        budgetText = if (budgetSources.isEmpty()) "" else if (ratio == null) "預算：消費資料未齊" else "預算已用 ${String.format(java.util.Locale.US, "%.1f", ratio * 100)}%",
        budgetProgress = ratio?.let { (it * 100).toInt().coerceIn(0, 100) })
}
