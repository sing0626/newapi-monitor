package com.newapimonitor.domain

import java.time.Instant
import java.time.ZoneId

/**
 * 燒錢預測（純函數）：用兩個日均燒錢估計推「餘額仲可以燒幾多日」——
 *  ①「今日速度」：今日消費 ÷ 當日 elapsed fraction 外推全日（elapsed < 15 分鐘時唔用，唔可靠）
 *  ②「7日平均」：過去日（唔含今日）日均；非零日 < 3 個時唔用（數據不足）
 * 兩個都可得 → 取較保守（細）嗰個 daysLeft estimate（錢燒完得快嗰邊）；只得一個就用嗰個；冇 → null。
 *
 * daily7dUsd 舊→新、最後一項係今日（同 DashboardSnapshot.weekDays 一致）。
 */
object BurnForecast {

    const val BASIS_TODAY = "今日速度"
    const val BASIS_AVG = "7日平均"

    /** 今日外推最少要過咗 15 分鐘先當日 */
    const val MIN_ELAPSED_MS = 15 * 60_000L

    /** 日均基線最少要 3 個非零日先算可靠 */
    const val MIN_NONZERO_DAYS = 3

    data class Forecast(
        /** 餘額仲夠燒幾多日；估計唔到 = null */
        val daysLeft: Double?,
        /** "今日速度" / "7日平均"；daysLeft = null 時都係 null */
        val basis: String?,
        /** 過去日（唔含今日）日均 USD；冇任何非零過去日 = null */
        val dailyAvg7d: Double?,
    )

    fun forecast(
        balanceUsd: Double?,
        todaySpendUsd: Double?,
        nowMs: Long,
        daily7dUsd: List<Double>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Forecast {
        val rawAvg = baselineDailyAvgUsd(daily7dUsd)
        if (balanceUsd == null || !balanceUsd.isFinite() || balanceUsd < 0) {
            return Forecast(null, null, rawAvg)
        }
        val usableAvg = usableBaseline(daily7dUsd)

        val daysByToday: Double? = todayRate(todaySpendUsd, nowMs, zone)?.let { rateFor(balanceUsd, it) }
        val daysByAvg: Double? = usableAvg?.let { rateFor(balanceUsd, it) }

        val (daysLeft, basis) = when {
            daysByToday != null && daysByAvg != null ->
                if (daysByToday <= daysByAvg) daysByToday to BASIS_TODAY else daysByAvg to BASIS_AVG
            daysByToday != null -> daysByToday to BASIS_TODAY
            daysByAvg != null -> daysByAvg to BASIS_AVG
            else -> null to null
        }
        return Forecast(daysLeft, basis, rawAvg)
    }

    /** balance ÷ daily rate → 日數；餘額燒清 = 0 日 */
    private fun rateFor(balanceUsd: Double, rate: Double): Double {
        if (balanceUsd <= 0.0) return 0.0
        return balanceUsd / rate
    }

    /** 今日速度外推全日（同 AlertEngine 同一套邏輯：elapsed fraction 加權，<15 分鐘唔用） */
    internal fun todayRate(todaySpendUsd: Double?, nowMs: Long, zone: ZoneId): Double? {
        if (todaySpendUsd == null || !todaySpendUsd.isFinite() || todaySpendUsd <= 0.0) return null
        val todayStartMs = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val elapsedMs = nowMs - todayStartMs
        if (elapsedMs < MIN_ELAPSED_MS) return null
        val frac = elapsedMs.toDouble() / 86_400_000.0
        if (frac <= 0.0) return null
        val rate = todaySpendUsd / frac
        return if (rate.isFinite() && rate > 0.0) rate else null
    }

    /**
     * 過去日（唔含最後一項＝今日）日均：只計非零日（零＝嗰日冇消費，唔溝稀基線）；
     * 冇任何非零過去日返回 null。AlertEngine SPIKE 規則用同一條基線。
     */
    fun baselineDailyAvgUsd(daily7dUsd: List<Double>): Double? {
        val past = if (daily7dUsd.size >= 2) daily7dUsd.dropLast(1) else emptyList()
        val nonzero = past.filter { it.isFinite() && it > 0.0 }
        if (nonzero.isEmpty()) return null
        val avg = nonzero.sumOf { it } / nonzero.size
        return if (avg.isFinite() && avg > 0.0) avg else null
    }

    /** 可靠基線：非零日 ≥ 3 個先畀（數據不足 → 只用今日外推可得部分或 null） */
    private fun usableBaseline(daily7dUsd: List<Double>): Double? {
        val past = if (daily7dUsd.size >= 2) daily7dUsd.dropLast(1) else emptyList()
        val nonzero = past.filter { it.isFinite() && it > 0.0 }
        if (nonzero.size < MIN_NONZERO_DAYS) return null
        return baselineDailyAvgUsd(daily7dUsd)
    }
}
