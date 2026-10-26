package com.newapimonitor.domain

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 消費日曆（GitHub-style 月度熱力格，純 JVM 可測）：
 *  - dailyTotals()：usage points（/api/data/self 行或 demo 日誌點）→ 按當地日聚合成每日 USD
 *    （DST 安全：每點先換算 LocalDate 再取當地 0 點，唔會似固定 86400 步進咁跨 DST 漂移）
 *  - build()：年月 + 每日 USD → 週格矩陣（Mon→Sun 七欄，第一行前補 null）
 *    ＋每格消費檔位 tier（0=冇消費；1..4 = 非零日值嘅四分位，UI 映射 Accent1 alpha 0.25/0.45/0.7/0.95）
 * 時區同「現在」都由呼叫方傳入（同 SpendStats/Heatmap 同一套純函數慣例）。
 */
object SpendCalendar {

    /** 一日消費（當地日 0 點 epoch 秒 + 該日 USD 總額） */
    data class DailyUsd(val dayStartSec: Long, val usd: Double)

    /** tier → 顏色 alpha（0 = 冇消費，UI 畫 BarTrackColor 軌道色） */
    val TIER_ALPHAS = floatArrayOf(0f, 0.25f, 0.45f, 0.7f, 0.95f)

    /** 週頭（Mon→Sun），同儀表板 WEEKDAY_LABELS 同一套字 */
    val WEEKDAY_HEADER = listOf("一", "二", "三", "四", "五", "六", "日")

    /** 一格日曆 */
    data class Cell(
        val dayStartSec: Long,
        val day: Int,
        val usd: Double,
        val tier: Int,
        val isToday: Boolean,
        val isFuture: Boolean,
    )

    /** 一個月嘅日曆格：weeks 每行固定 7 格（Mon→Sun），月外空位係 null */
    data class Grid(
        val year: Int,
        val month: Int,
        val weeks: List<List<Cell?>>,
        val totalUsd: Double,
        val spendDays: Int,
    )

    /**
     * usage points → 每日 USD（當地日聚合；quota ÷ qpu 換算，qpu 非法用預設）。
     * 只保留 usd > 0 嘅日子（稀疏輸出：空月 → 空列表；UI 靠呢個判斷「本月暫無消費數據」）。
     */
    fun dailyTotals(
        points: List<SpendStats.Point>,
        qpu: Double,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<DailyUsd> {
        val safeQpu = if (qpu.isFinite() && qpu > 0) qpu else Quota.DEFAULT_QPU
        val acc = HashMap<LocalDate, Double>()
        for (p in points) {
            val d = Instant.ofEpochSecond(p.tsSec).atZone(zone).toLocalDate()
            acc[d] = (acc[d] ?: 0.0) + p.quota
        }
        return acc.entries.asSequence()
            .filter { it.value > 0.0 }
            .sortedBy { it.key }
            .map { (d, v) -> DailyUsd(d.atStartOfDay(zone).toEpochSecond(), v / safeQpu) }
            .toList()
    }

    /**
     * 月曆格：year/month + 當月每日 USD → 週行（Mon→Sun）。
     * dailyUsd 入面月外嘅日子一律唔計；nowMs 決定今日／未來格。
     */
    fun build(
        year: Int,
        month: Int,
        dailyUsd: List<DailyUsd>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Grid {
        val ym = YearMonth.of(year, month)
        val first = ym.atDay(1)
        val len = ym.lengthOfMonth()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

        val usdByDay = HashMap<Int, Double>()
        for (d in dailyUsd) {
            val date = Instant.ofEpochSecond(d.dayStartSec).atZone(zone).toLocalDate()
            if (date.year == ym.year && date.monthValue == ym.monthValue) {
                usdByDay[date.dayOfMonth] = (usdByDay[date.dayOfMonth] ?: 0.0) + d.usd
            }
        }

        // tier 門檻：非零日值嘅 25/50/75 百分位（nearest-floor 取樣，純函數可測）
        val nonzero = usdByDay.values.filter { it > 0.0 }.sorted()
        val q1 = percentile(nonzero, 0.25)
        val q2 = percentile(nonzero, 0.50)
        val q3 = percentile(nonzero, 0.75)

        val cells = (1..len).map { day ->
            val date = first.plusDays((day - 1).toLong())
            val usd = usdByDay[day] ?: 0.0
            Cell(
                dayStartSec = date.atStartOfDay(zone).toEpochSecond(),
                day = day,
                usd = usd,
                tier = tierFor(usd, q1, q2, q3),
                isToday = date == today,
                isFuture = date.isAfter(today),
            )
        }

        // 星期二開月 → 週一 1 格 null 起頭；尾行唔夠 7 格補 null
        val leading = first.dayOfWeek.value - 1 // 1=Mon..7=Sun → 0..6 前置空位
        val padded: List<Cell?> = List(leading) { null } + cells
        val weeks = padded.chunked(7).map { row -> List(7) { i -> row.getOrNull(i) } }
        return Grid(
            year = ym.year,
            month = ym.monthValue,
            weeks = weeks,
            totalUsd = usdByDay.values.sum(),
            spendDays = usdByDay.count { it.value > 0.0 },
        )
    }

    private fun percentile(sortedAsc: List<Double>, p: Double): Double {
        if (sortedAsc.isEmpty()) return 0.0
        val idx = Math.floor((sortedAsc.size - 1) * p).toInt().coerceIn(0, sortedAsc.size - 1)
        return sortedAsc[idx]
    }

    /** 檔位：0=冇消費；其後按四分位門檻（<= q1 → 1、<= q2 → 2、<= q3 → 3、其餘 → 4） */
    private fun tierFor(v: Double, q1: Double, q2: Double, q3: Double): Int = when {
        v <= 0.0 -> 0
        v <= q1 -> 1
        v <= q2 -> 2
        v <= q3 -> 3
        else -> 4
    }
}
