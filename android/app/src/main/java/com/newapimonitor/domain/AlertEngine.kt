package com.newapimonitor.domain

import com.newapimonitor.util.Fmt
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max

/** 警報類型（每類獨立 per-instance 冷卻，由 AlertStateStore.lastFiredMs 記） */
enum class AlertType {
    BALANCE_LOW,
    DAILY_SPEND,
    SPIKE,
    TOKEN_BANNED,
    MONTHLY_BUDGET_90,
    MONTHLY_BUDGET_100,
    ERROR_RATE,
    TOKEN_EXPIRY_3D,
    TOKEN_EXPIRY_1D,
    TOKEN_EXPIRED,
}

/** 一條警報事件（通知文案已喺呢度生成，UI／通知層直接用） */
data class AlertEvent(
    val type: AlertType,
    val title: String,
    val body: String,
    val messageKey: String? = null,
    val messageArgs: List<String> = emptyList(),
    val notificationId: Int = type.ordinal + 1,
)

/**
 * 警報引擎（純函數，可完全重現）：比較今次同上次 snapshot 摘要，按 AlertSettings
 * 生成 AlertEvent 列表＋更新後嘅狀態（lastFiredMs / armedBalanceOk / bannedTokenIds），
 * 呼叫方（AlertWorker）負責將新狀態寫返 AlertStateStore。
 *
 * 規則：
 *  - BALANCE_LOW：餘額 ≤ 門檻觸發；觸發後 disarm，回復到門檻×1.1 以上先重新 arm（滯回，避免狂彈）
 *  - DAILY_SPEND：今日消費 ≥ 門檻觸發；每個當地日最多一次（另外尊重 cooldown）
 *  - SPIKE：今日消費速度外推全日（÷ elapsed fraction）≥ 過去日均 × spikeMultiplier 且外推 ≥ $1
 *  - TOKEN_BANNED：令牌列表出現 status==3 嘅新令牌（同上次觀察比）
 *  - 所有觸發都要 respect cooldown（同 type 共用 per-instance 冷卻）
 */
object AlertEngine {

    /** 輸入快照摘要：只帶引擎要嘅欄位（冇上次數據時傳 null） */
    data class SnapshotSummary(
        val balanceUsd: Double?,
        val todaySpendUsd: Double?,
        val monthSpendUsd: Double? = null,
        val errorCount: Int? = null,
        val requestCount: Int? = null,
    )

    data class Input(
        val instId: String,
        val instName: String,
        /** 上一次 snapshot 摘要（null = 首次觀察） */
        val prev: SnapshotSummary?,
        /** 今次 snapshot 摘要 */
        val current: SnapshotSummary,
        /** 最近 7 日每日消費 USD（舊→新；最後一項係今日，同 DashboardSnapshot.weekDays 一致） */
        val daily7dUsd: List<Double>,
        /** 今次令牌列表中 status==3 嘅 id 集 */
        val bannedTokenIds: Set<Long>,
        /** 上次觀察到嘅被封禁令牌 id 集 */
        val prevBannedTokenIds: Set<Long>,
        /** 各類警報上次觸發時間（AlertStateStore.lastFiredMs 原樣傳入） */
        val lastFiredMs: Map<AlertType, Long>,
        /** 餘額警報 arm 狀態（true = 可觸發；AlertStateStore.armedBalanceOk 原樣傳入） */
        val armedBalanceOk: Boolean,
        val settings: AlertSettings,
        val nowMs: Long,
        val zone: ZoneId = ZoneId.systemDefault(),
        /** Null when token endpoint fails; never interpret failure as empty list. */
        val expiringTokens: Map<Long, Long>? = null,
        val prevTokenExpiryPhases: Map<String, Int> = emptyMap(),
    )

    /** 引擎輸出：事件＋要寫返 AlertStateStore 嘅新狀態 */
    data class Result(
        val events: List<AlertEvent>,
        val lastFiredMs: Map<AlertType, Long>,
        val armedBalanceOk: Boolean,
        val bannedTokenIds: Set<Long>,
        val tokenExpiryPhases: Map<String, Int>,
    )

    /** cooldown 內（唔可以再響）＝上次觸發距今少於 cooldownMinutes */
    fun inCooldown(type: AlertType, lastFiredMs: Map<AlertType, Long>, cooldownMinutes: Int, nowMs: Long): Boolean {
        val cd = max(cooldownMinutes, 0) * 60_000L
        if (cd <= 0L) return false
        val last = lastFiredMs[type] ?: return false
        return nowMs - last < cd
    }

    /** 今日消費速度外推全日（elapsed fraction 加權）；elapsed < 15 分鐘返回 null（唔可靠） */
    fun projectedTodayUsd(todaySpendUsd: Double, nowMs: Long, zone: ZoneId): Double? =
        BurnForecast.todayRate(todaySpendUsd, nowMs, zone)

    /** 過去日均基線：daily7dUsd 扣走最後一項（今日）後取非零日平均；冇過去日返回 null（BurnForecast 同一條基線） */
    fun baselineDailyAvgUsd(daily7dUsd: List<Double>): Double? =
        BurnForecast.baselineDailyAvgUsd(daily7dUsd)

    fun evaluate(input: Input): Result {
        val events = ArrayList<AlertEvent>(2)
        val s = input.settings
        val fired = ArrayList<AlertType>(2)
        var newFired = input.lastFiredMs
        var balanceFired = false
        var expiryPhases = input.prevTokenExpiryPhases

        if (s.enabled) {
            val cooldownBlocked = { type: AlertType ->
                inCooldown(type, input.lastFiredMs, s.cooldownMinutes, input.nowMs)
            }

            /* ---- BALANCE_LOW：餘額 ≤ 門檻且 armed ---- */
            val threshold = s.balanceBelowUsd
            val balance = input.current.balanceUsd
            if (threshold != null && balance != null && balance.isFinite() &&
                input.armedBalanceOk && !cooldownBlocked(AlertType.BALANCE_LOW) &&
                balance <= threshold
            ) {
                balanceFired = true
                fired.add(AlertType.BALANCE_LOW)
                events.add(
                    AlertEvent(
                        type = AlertType.BALANCE_LOW,
                        title = "餘額過低",
                        body = "「${input.instName}」餘額 ${Fmt.usd(balance)} 已低於警報線 ${Fmt.usd(threshold)}",
                    )
                )
            }

            /* ---- DAILY_SPEND：今日消費 ≥ 門檻（每個當地日最多一次） ---- */
            val spendThreshold = s.dailySpendAboveUsd
            val todaySpend = input.current.todaySpendUsd
            if (spendThreshold != null && spendThreshold > 0 && todaySpend != null && todaySpend.isFinite() &&
                todaySpend >= spendThreshold &&
                !firedToday(input, AlertType.DAILY_SPEND) &&
                !cooldownBlocked(AlertType.DAILY_SPEND)
            ) {
                fired.add(AlertType.DAILY_SPEND)
                events.add(
                    AlertEvent(
                        type = AlertType.DAILY_SPEND,
                        title = "今日消費超標",
                        body = "「${input.instName}」今日消費 ${Fmt.usd(todaySpend)} 已達警報線 ${Fmt.usd(spendThreshold)}",
                    )
                )
            }

            /* ---- SPIKE：今日速度外推全日 ≥ 過去日均 × 倍數 且外推 ≥ $1 ---- */
            if (s.spikeMultiplier > 0 && todaySpend != null && todaySpend.isFinite() && todaySpend > 0 &&
                !cooldownBlocked(AlertType.SPIKE)
            ) {
                val projected = projectedTodayUsd(todaySpend, input.nowMs, input.zone)
                val baseline = baselineDailyAvgUsd(input.daily7dUsd)
                if (projected != null && baseline != null &&
                    projected >= 1.0 && projected >= baseline * s.spikeMultiplier
                ) {
                    fired.add(AlertType.SPIKE)
                    val times = if (baseline > 0) projected / baseline else s.spikeMultiplier
                    events.add(
                        AlertEvent(
                            type = AlertType.SPIKE,
                            title = "消費異常飆升",
                            body = "「${input.instName}」今日消費速度外推全日約 ${Fmt.usd(projected)}，" +
                                "係過去日均 ${Fmt.usd(baseline)} 嘅 ${Fmt.num(times)} 倍",
                        )
                    )
                }
            }

            /* ---- TOKEN_BANNED：出現新封禁令牌 ---- */
            if (s.notifyTokenBanned && !cooldownBlocked(AlertType.TOKEN_BANNED)) {
                val newlyBanned = input.bannedTokenIds - input.prevBannedTokenIds
                if (newlyBanned.isNotEmpty()) {
                    fired.add(AlertType.TOKEN_BANNED)
                    events.add(
                        AlertEvent(
                            type = AlertType.TOKEN_BANNED,
                            title = "令牌被封禁",
                            body = "「${input.instName}」有 ${newlyBanned.size} 張令牌被自動封禁：" +
                                formatIds(newlyBanned),
                        )
                    )
                }
            }
        }

        if (s.enabled) {
            val budget = s.monthlyBudgetUsd
            val spend = input.current.monthSpendUsd
            if (budget != null && budget.isFinite() && budget > 0 && spend != null && spend.isFinite() && spend >= 0) {
                val ratio = spend / budget
                for ((type, limit, key) in listOf(
                    Triple(AlertType.MONTHLY_BUDGET_90, 0.9, "alert.month90"),
                    Triple(AlertType.MONTHLY_BUDGET_100, 1.0, "alert.month100"),
                )) {
                    val last = input.lastFiredMs[type]
                    val sameMonth = last != null && Instant.ofEpochMilli(last).atZone(input.zone).let { d ->
                        val n = Instant.ofEpochMilli(input.nowMs).atZone(input.zone)
                        d.year == n.year && d.month == n.month
                    }
                    if (ratio >= limit && !sameMonth && !inCooldown(type, input.lastFiredMs, s.cooldownMinutes, input.nowMs)) {
                        fired.add(type)
                        events.add(AlertEvent(type, if (limit == 1.0) "月度預算已超支" else "月度預算警戒",
                            "「${input.instName}」本月消費 ${Fmt.usd(spend)} / ${Fmt.usd(budget)}（${(ratio * 100).toInt()}%）",
                            key, listOf(input.instName, Fmt.usd(spend), Fmt.usd(budget), (ratio * 100).toInt().toString())))
                    }
                }
            }
            val errors = input.current.errorCount
            val requests = input.current.requestCount
            if (s.notifyErrorRate && errors != null && requests != null && errors >= 3 && requests >= errors &&
                requests > 0 && errors.toDouble() / requests >= 0.1 &&
                !inCooldown(AlertType.ERROR_RATE, input.lastFiredMs, s.cooldownMinutes, input.nowMs)) {
                fired.add(AlertType.ERROR_RATE)
                val pct = Fmt.num(errors * 100.0 / requests)
                events.add(AlertEvent(AlertType.ERROR_RATE, "連續錯誤率過高",
                    "「${input.instName}」今日請求錯誤率 $pct%（$errors/$requests）",
                    "alert.errorRate", listOf(input.instName, pct, errors.toString(), requests.toString())))
            }
            if (input.expiringTokens != null) {
                val phases = mutableMapOf<String, Int>()
                for ((id, expirySec) in input.expiringTokens) {
                    if (expirySec <= 0) continue
                    val key = "$id:$expirySec"
                    val remaining = expirySec * 1000.0 - input.nowMs
                    val phase = when {
                        remaining <= 0 -> 3
                        remaining < 86_400_000 -> 2
                        remaining < 3 * 86_400_000 -> 1
                        else -> 0
                    }
                    val previous = input.prevTokenExpiryPhases[key] ?: 0
                    phases[key] = previous
                    if (!s.notifyTokenExpiry || phase == 0 || phase <= previous) continue
                    val type = when (phase) {
                        1 -> AlertType.TOKEN_EXPIRY_3D
                        2 -> AlertType.TOKEN_EXPIRY_1D
                        else -> AlertType.TOKEN_EXPIRED
                    }
                    if (!inCooldown(type, input.lastFiredMs, s.cooldownMinutes, input.nowMs)) {
                        fired.add(type)
                        phases[key] = phase
                        val label = "#$id"
                        events.add(AlertEvent(type, if (phase == 3) "令牌已過期" else "令牌即將過期",
                            "「${input.instName}」令牌 $label ${if (phase == 3) "已過期" else if (phase == 2) "不足 1 日後過期" else "不足 3 日後過期"}",
                            when (phase) { 1 -> "alert.expiry3"; 2 -> "alert.expiry1"; else -> "alert.expired" },
                            listOf(input.instName, label), notificationId = 1000 + (id % 100000).toInt() * 10 + phase))
                    }
                }
                expiryPhases = phases
            }
        }

        for (type in fired) {
            newFired = newFired + (type to input.nowMs)
        }

        /* ---- arm 狀態更新：觸發就 disarm；回復到門檻×1.1 以上重新 arm ---- */
        val threshold = s.balanceBelowUsd
        val balance = input.current.balanceUsd
        val newArmed = when {
            balanceFired -> false
            threshold != null && balance != null && balance.isFinite() &&
                balance >= threshold * 1.1 -> true
            else -> input.armedBalanceOk
        }

        return Result(
            events = events,
            lastFiredMs = newFired,
            armedBalanceOk = newArmed,
            bannedTokenIds = input.bannedTokenIds,
            tokenExpiryPhases = expiryPhases,
        )
    }

    /** DAILY_SPEND 上次觸發係咪同一個當地日 */
    private fun firedToday(input: Input, type: AlertType): Boolean {
        val last = input.lastFiredMs[type] ?: return false
        val z = input.zone
        val lastDay = Instant.ofEpochMilli(last).atZone(z).toLocalDate()
        val today = Instant.ofEpochMilli(input.nowMs).atZone(z).toLocalDate()
        return lastDay == today
    }

    /** "#12、#13、#14 等"（最多列 3 個） */
    private fun formatIds(ids: Set<Long>): String {
        val sorted = ids.sorted()
        val head = sorted.take(3).joinToString("、") { "#$it" }
        return if (sorted.size > 3) "$head 等" else head
    }
}
