package com.newapimonitor.domain

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Inclusive calendar dates. Epoch windows use local midnights, including DST changes. */
data class ReportRange(val start: LocalDate, val end: LocalDate) {
    val days: Long get() = ChronoUnit.DAYS.between(start, end) + 1
    fun validate(today: LocalDate = LocalDate.now()): ReportRange = apply {
        require(!end.isBefore(start) && days <= 366 && !end.isAfter(today)) { "請選擇有效日期範圍（最多 366 日，不能超過今日）" }
    }
    fun previous(): ReportRange = ReportRange(start.minusDays(days), start.minusDays(1))
    fun previousMonth(): ReportRange = ReportRange(start.minusMonths(1), end.minusMonths(1))
    fun startSeconds(zone: ZoneId = ZoneId.systemDefault()): Long = start.atStartOfDay(zone).toEpochSecond()
    fun endSeconds(zone: ZoneId = ZoneId.systemDefault()): Long = end.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1
    companion object {
        fun lastSeven(today: LocalDate = LocalDate.now()) = ReportRange(today.minusDays(6), today)
        fun month(date: LocalDate, today: LocalDate = LocalDate.now()) =
            ReportRange(date.withDayOfMonth(1), minOf(date.withDayOfMonth(date.lengthOfMonth()), today))
    }
}

data class CostGroup(val name: String, val usd: Double, val requests: Int)

object CostBreakdown {
    fun group(rows: List<LogEntry>, qpu: Double, token: Boolean = false): List<CostGroup> =
        LogMath.filterConsume(rows).groupBy { if (token) it.tokenName.orEmpty() else it.modelName.orEmpty() }
            .map { (name, logs) -> CostGroup(name, logs.sumOf { it.quota ?: 0.0 } / qpu, logs.size) }
            .sortedByDescending { it.usd }
    fun change(current: Double?, previous: Double?): Double? =
        if (current != null && previous != null && previous > 0) (current - previous) / previous * 100 else null
}
