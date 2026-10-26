package com.newapimonitor.alert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.newapimonitor.MainActivity
import com.newapimonitor.data.store.AppearanceStore
import com.newapimonitor.ui.theme.translated
import com.newapimonitor.ui.theme.alertMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.newapimonitor.NewApiMonitorApp
import com.newapimonitor.R
import com.newapimonitor.data.api.NewApiClient
import com.newapimonitor.data.api.ResponseParsing
import com.newapimonitor.data.api.asJsonObjectOrEmpty
import com.newapimonitor.data.api.doubleOf
import com.newapimonitor.data.api.longOf
import com.newapimonitor.data.store.AlertSettingsStore
import com.newapimonitor.data.store.AlertState
import com.newapimonitor.data.store.AlertStateStore
import com.newapimonitor.data.store.HistoryStore
import com.newapimonitor.domain.AlertEngine
import com.newapimonitor.domain.AlertEvent
import com.newapimonitor.domain.AlertSettings
import com.newapimonitor.domain.AlertType
import com.newapimonitor.domain.LogMath
import com.newapimonitor.domain.Quota
import com.newapimonitor.domain.SpendStats
import com.newapimonitor.domain.ErrorStats
import com.newapimonitor.util.Fmt
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * 警報背景檢查（15 分鐘週期，AlertScheduler 排程）：
 *  - 輕量拉現用實例數據（同 widget 同款短超時 HTTP，唔影響 App 內連線池行為）
 *  - demo 模式完全跳過 — 唔會為演示數據發真通知
 *  - 套 AlertEngine（純函數）→ 狀態寫返 AlertStateStore → 有事件先發本地通知
 *  - 順路記一個餘額取樣入 HistoryStore（同分鐘自動合併）
 */
class AlertWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? NewApiMonitorApp ?: return Result.success()
        try {
            val settings = AlertSettingsStore(applicationContext).current()
            if (!settings.enabled || !settings.persistentStatus) {
                StatusNotifier.cancel(applicationContext)
            }
            if (settings.enabled) {
                withTimeoutOrNull(CHECK_BUDGET_MS) {
                    runCatching { checkAndNotify(app, settings) }
                }
                withTimeoutOrNull(90_000L) {
                    if (!app.store.settings.value.demo) {
                        val monitor = com.newapimonitor.data.monitor.MonitorService(app.store)
                        val budgets = monitor.cache.budgets.first()
                        val rules = com.newapimonitor.data.monitor.MonitorPreferencesStore(app).rules.first()
                        val targets = app.store.instances.value.filter { it.id in budgets || rules[it.id]?.enabled == true }
                        if (targets.isNotEmpty()) {
                            val rows = monitor.refreshAll(targets, monitor.cache.snapshots.first())
                            MonitorAlerts.evaluate(app.store, rows)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 背景任務唔好炸：任何意外（DataStore／WorkManager 邊角）一律吞掉
        }
        return Result.success()
    }

    /* ---------------- 檢查本體 ---------------- */

    private suspend fun checkAndNotify(app: NewApiMonitorApp, settings: AlertSettings) {
        val context: Context = app
        val store = app.store
        withTimeoutOrNull(3_000L) { store.ready.filter { it }.first() } ?: return

        // demo 模式唔會觸發真通知（演示數據唔應該彈系統通知）
        if (store.settings.value.demo) {
            StatusNotifier.cancel(context)
            return
        }
        // Do not consume one-shot threshold/expiry state while notification permission is unavailable.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val inst = store.activeInstance.value ?: run {
            StatusNotifier.cancel(context)
            return
        }
        if (!inst.hasAnyToken || inst.accessToken.isBlank()) {
            StatusNotifier.cancel(context)
            return
        }

        val client = NewApiClient(
            baseUrl = inst.baseUrl,
            accessToken = inst.accessToken,
            apiKey = inst.apiKey,
            initialAccessStyle = inst.authStyle,
            initialKeyStyle = inst.authStyleKey,
            quotaPerUnit = inst.quotaPerUnit,
            http = ALERT_HTTP,
        )
        val nowMs = System.currentTimeMillis()

        // qpu：設定值優先，/api/status 可失敗
        val status = withTimeoutOrNull(2_500L) { runCatching { client.fetchStatus() }.getOrNull() }
        val qpu = Quota.resolveQpu(inst.quotaPerUnit, status?.doubleOf("quota_per_unit"))

        // user/self：失敗（401／網絡）就放棄今輪，唔會誤報
        val u = try {
            client.fetchUserSelf().asJsonObjectOrEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StatusNotifier.cancel(context)
            throw e
        }
        client.setUserId(u.longOf("id"))
        val balanceUsd = u.doubleOf("quota")?.div(qpu)?.takeIf { it.isFinite() && it >= 0 }

        // 今日消費：一頁 100 行輕量掃描（同 widget 同款）
        val nowSec = nowMs / 1000
        var todayErrors: ErrorStats.Stats? = null
        val todaySpendUsd: Double? = try {
            val w = client.fetchLogWindow(
                startTs = Fmt.todayStartTs(nowMs),
                endTs = nowSec,
                maxPages = 1,
                pageSize = 100,
            )
            val raw = w.rows.map { ResponseParsing.parseLogEntry(it) }
            if (!w.truncated) todayErrors = ErrorStats.compute(raw, nowMs)
            val rows = LogMath.filterConsume(raw)
            if (w.truncated) null else LogMath.sumQuota(rows) / qpu
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }

        // 7 日每日消費（SPIKE 基線）：/api/data/self 看板；失敗靜默（只會少咗 SPIKE）
        val daily7dUsd: List<Double> = try {
            val d = client.fetchDataSelf(startTs = Fmt.todayStartTs(nowMs) - 6 * 86_400L, endTs = nowSec)
            val points = ResponseParsing.pickLogItems(d)
                .mapNotNull { ResponseParsing.parseUsagePoint(it) }
            if (points.isEmpty()) {
                emptyList()
            } else {
                SpendStats.dailyFromPoints(points, days = 7, qpu = qpu, nowMs = nowMs).map { it.usd }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }

        // Monthly totals prefer the dashboard aggregation; a failed/empty endpoint must not become zero.
        val monthSpendUsd = if (settings.monthlyBudgetUsd != null) try {
            val data = client.fetchDataSelf(startTs = Fmt.monthStartTs(nowMs), endTs = nowSec)
            val points = ResponseParsing.pickLogItems(data).mapNotNull { ResponseParsing.parseUsagePoint(it) }
            points.takeIf { it.isNotEmpty() && it.all { p -> p.quota.isFinite() && p.quota >= 0 } }
                ?.sumOf { it.quota }?.div(qpu)?.takeIf { it.isFinite() && it >= 0 }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } else null

        // A failed or incomplete token page must not erase previous observations.
        val tokens = try {
            val page = client.fetchTokens(p = 1, size = 100)
            val items = ResponseParsing.pickTokenItems(page)
            if (items.size >= 100 || items.size != items.mapNotNull { ResponseParsing.parseToken(it) }.size) null
            else items.mapNotNull { ResponseParsing.parseToken(it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }

        // 上次狀態＋上次餘額（HistoryStore 最後一個取樣）
        val history = HistoryStore(context)
        val stateStore = AlertStateStore(context)
        val prevState = stateStore.read(inst.id)
        val prevBalanceUsd = history.historyOnce(inst.id).lastOrNull()?.v

        val individualRule = com.newapimonitor.data.monitor.MonitorPreferencesStore(context).rules.first()[inst.id]
        if (individualRule?.enabled == true && individualRule.quiet(java.time.LocalTime.now())) return
        val effectiveSettings = if (individualRule?.enabled == true) settings.copy(balanceBelowUsd = null, dailySpendAboveUsd = null) else settings
        val result = AlertEngine.evaluate(
            AlertEngine.Input(
                instId = inst.id,
                instName = inst.name,
                prev = prevBalanceUsd?.let { AlertEngine.SnapshotSummary(it, null) },
                current = AlertEngine.SnapshotSummary(balanceUsd, todaySpendUsd, monthSpendUsd,
                    todayErrors?.windowCount, todayErrors?.let { it.windowCount + it.consumeCount }),
                daily7dUsd = daily7dUsd,
                bannedTokenIds = tokens?.filter { it.status == 3 }?.map { it.id }?.toSet()
                    ?: prevState.lastBannedTokenIds,
                expiringTokens = tokens?.associate { it.id to it.expiredTime },
                prevTokenExpiryPhases = prevState.tokenExpiryPhases,
                prevBannedTokenIds = prevState.lastBannedTokenIds,
                lastFiredMs = prevState.lastFiredMs.mapNotNull { (k, v) ->
                    val type = runCatching { AlertType.valueOf(k) }.getOrNull() ?: return@mapNotNull null
                    type to v
                }.toMap(),
                armedBalanceOk = prevState.armedBalanceOk,
                settings = effectiveSettings,
                nowMs = nowMs,
            )
        )

        // 狀態寫返 DataStore（lastFiredMs／arm／已見封禁集），餘額取樣順路入 HistoryStore
        store.withCurrentSource(inst) {
        if (!AlertSettingsStore(context).current().enabled || store.settings.value.demo) return@withCurrentSource
        stateStore.write(
            inst.id,
            AlertState(
                lastFiredMs = result.lastFiredMs.mapKeys { (k, _) -> k.name },
                armedBalanceOk = result.armedBalanceOk,
                lastBannedTokenIds = result.bannedTokenIds,
                tokenExpiryPhases = result.tokenExpiryPhases,
            ),
        )
        if (balanceUsd != null) {
            history.addSample(inst.id, nowMs, balanceUsd)
        }

        if (settings.persistentStatus) {
            val language = AppearanceStore(context).appearance.first().language
            StatusNotifier.update(context, balanceUsd, todaySpendUsd, language)
        } else {
            StatusNotifier.cancel(context)
        }
        if (result.events.isNotEmpty()) notify(context, inst.id, result.events)
        }
    }

    /* ---------------- 通知 ---------------- */

    private suspend fun notify(context: Context, instId: String, events: List<AlertEvent>) {
        val mgr = NotificationManagerCompat.from(context)
        if (!mgr.areNotificationsEnabled()) return
        ensureChannel(context)
        val pi = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val language = runCatching { runBlocking { AppearanceStore(context).appearance.first().language } }.getOrDefault("yue")
        for (event in events) {
            val title = translated(event.title, language)
            val body = event.messageKey?.let { alertMessage(it, event.messageArgs, language) }
                ?: translated(event.body, language)
            AlertHistoryStore(context).add(AlertRecord(sourceId = instId, title = title, body = body, type = event.notificationId.toString()))
            val notif = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            runCatching { mgr.notify(instId, event.notificationId, notif) }
        }
    }

    private fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "餘額／消費警報",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "NewAPI 實例餘額過低、消費超標、異常飆升同令牌封禁通知"
        }
        mgr.createNotificationChannel(ch)
    }

    private companion object {
        const val CHANNEL_ID = "alerts"

        /** 15 分鐘一次，唔使長跑：整體預算封頂 30s，超時放棄今輪 */
        const val CHECK_BUDGET_MS = 30_000L

        /** 背景檢查專用短超時 HTTP（同 widget 同一套數值，唔共用 widget 代碼） */
        val ALERT_HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
