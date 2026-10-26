package com.newapimonitor.domain

import java.time.Instant
import java.time.ZoneId

/**
 * 時段熱力圖（純函數）：raw 日誌行 → 24 格每小時 USD 消費（裝置時區 hour-of-day）＋最大值。
 * 內部先過 LogMath.filterConsume（type=2 帶 model_name + quota），充值／系統／錯誤行唔會入數。
 * java.time 直接用（minSdk 26 原生支援，同 SpendStats/Fmt 同一套）。
 */
object Heatmap {

    data class Result(
        /** 24 格（0..23 裝置時區小時）每小時 USD 消費 */
        val byHour: DoubleArray,
        /** 全日最大格值（全 0 = 0.0） */
        val max: Double,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Result && byHour.contentEquals(other.byHour) && max == other.max

        override fun hashCode(): Int = byHour.contentHashCode() * 31 + max.hashCode()
    }

    fun compute(
        rows: List<LogEntry>,
        qpu: Double,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Result {
        val safeQpu = if (qpu.isFinite() && qpu > 0) qpu else Quota.DEFAULT_QPU
        val byHour = DoubleArray(24)
        for (r in LogMath.filterConsume(rows)) {
            val q = r.quota ?: continue
            val hour = Instant.ofEpochSecond(r.createdAt).atZone(zone).hour
            byHour[hour] += q / safeQpu
        }
        var max = 0.0
        for (v in byHour) if (v > max) max = v
        return Result(byHour, max)
    }
}
