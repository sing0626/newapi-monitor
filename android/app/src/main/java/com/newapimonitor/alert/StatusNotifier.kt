package com.newapimonitor.alert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.newapimonitor.MainActivity
import com.newapimonitor.R
import com.newapimonitor.ui.theme.translated
import com.newapimonitor.util.Fmt

/** Only data from the active instance is shown; no credentials enter the notification. */
object StatusNotifier {
    private const val CHANNEL_ID = "status_persistent"
    private const val NOTIFICATION_ID = 2001

    fun applyPreference(context: Context, enabled: Boolean) {
        if (!enabled) cancel(context)
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    fun update(context: Context, balanceUsd: Double?, todayUsd: Double?, language: String) {
        if (balanceUsd == null || !balanceUsd.isFinite() || balanceUsd < 0.0) {
            cancel(context)
            return
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    translated("常駐狀態", language),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = "${translated("餘額", language)} ${Fmt.usd(balanceUsd, 2)}"
        val body = todayUsd?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { "${translated("今日", language)} ${Fmt.usd(it, 2)}" }
            ?: translated("常駐狀態", language)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }
}
