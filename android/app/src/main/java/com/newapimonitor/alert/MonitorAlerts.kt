package com.newapimonitor.alert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.newapimonitor.MainActivity
import com.newapimonitor.R
import com.newapimonitor.data.monitor.MonitorSnapshot
import com.newapimonitor.data.monitor.MonitorStore
import com.newapimonitor.data.store.AlertSettingsStore
import com.newapimonitor.data.store.InstanceRepository
import com.newapimonitor.util.Fmt
import kotlinx.coroutines.flow.first

object MonitorAlerts {
    suspend fun evaluate(store: InstanceRepository, rows: List<MonitorSnapshot>) {
        if (!AlertSettingsStore(store.appContext).current().enabled || store.settings.value.demo) return
        val cache = MonitorStore(store.appContext)
        val budgets = cache.budgets.first()
        val ruleStore = com.newapimonitor.data.monitor.MonitorPreferencesStore(store.appContext)
        val rules = ruleStore.rules.first()
        val now = System.currentTimeMillis()
        for (row in rows) {
            val source = store.instances.value.firstOrNull { it.id == row.id && MonitorSnapshot.stamp(it) == row.sourceStamp } ?: continue
            val rule = rules[row.id]
            if (rule?.enabled == true && rule.quiet(java.time.LocalTime.now())) continue
            val budget = budgets[row.id]
            val spent = row.monthUsd?.takeIf { row.spendFresh() }
            store.withCurrentSource(source) {
            if (!AlertSettingsStore(store.appContext).current().enabled || store.settings.value.demo) return@withCurrentSource
            if (rule?.enabled == true) {
                val events = listOfNotNull(
                    rule.balanceBelow?.takeIf { row.balanceFresh(now) && row.balanceUsd!! < it }?.let { "balance" to ("實例餘額過低" to "${source.name}：${Fmt.usd(row.balanceUsd!!)}，低於 ${Fmt.usd(it)}") },
                    rule.dailyAbove?.takeIf { row.spendFresh(now) && row.todayUsd!! > it }?.let { "daily" to ("實例每日消費超標" to "${source.name}：今日 ${Fmt.usd(row.todayUsd!!)}，高於 ${Fmt.usd(it)}") },
                )
                for ((kind, message) in events) if (ruleStore.claim(row.id, kind, rule, now)) {
                    AlertHistoryStore(store.appContext).add(AlertRecord(sourceId = row.id, title = message.first, body = message.second, type = kind))
                    notify(store, row.id, if (kind == "balance") 201 else 202, message.first, message.second)
                }
            }
            if (budget != null && spent != null) for (phase in listOf(90, 100)) {
                if (spent >= budget * phase / 100 && cache.claimBudgetPhase(row.id, row.month, phase)) {
                    val title = "實例月度預算已達 $phase%"
                    val body = "本月 ${Fmt.usd(spent)}／${Fmt.usd(budget)}"
                    AlertHistoryStore(store.appContext).add(AlertRecord(sourceId = row.id, title = title, body = body, type = "budget_$phase"))
                    notify(store, row.id, phase, title, body)
                }
            }
            }
        }
    }
    private fun notify(store: InstanceRepository, id: String, phase: Int, title: String, body: String) {
        val context = store.appContext
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(NotificationChannel("monitor_budgets", "實例警報", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(context, 20,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, com.newapimonitor.ui.TAB_ALERTS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { manager.notify("budget:$id", phase,
            Notification.Builder(context, "monitor_budgets").setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title).setContentText(body).setContentIntent(intent).setAutoCancel(true).build()) }
    }
}
