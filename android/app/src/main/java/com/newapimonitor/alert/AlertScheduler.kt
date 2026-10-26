package com.newapimonitor.alert

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 警報排程：唯一 work 名 "newapi-alerts"，15 分鐘週期（系統下限）。
 * settings.enabled = true → enqueueUpdate（KEEP：已有排程唔會重設，慳電唔重跑）；
 * 關閉就 cancel。App 啟動同設定頁改動都行呢一個入口。
 */
object AlertScheduler {

    const val WORK_NAME = "newapi-alerts"

    /** 系統 PeriodicWorkRequest 下限 15 分鐘 */
    const val PERIOD_MINUTES = 15L

    fun ensure(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        val wm = WorkManager.getInstance(appContext)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<AlertWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
