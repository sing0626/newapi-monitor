package com.newapimonitor.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*

/** Broadcast finishes immediately; bounded network work belongs to WorkManager. */
class BalanceWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WorkManager.getInstance(context).enqueueUniqueWork("newapi-widget-refresh", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build())
    }
    override fun onDeleted(context: Context, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { ids.forEach { WidgetOptionsStore(context).remove(it) } } finally { pending.finish() }
        }
    }
}
