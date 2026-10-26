package com.newapimonitor.domain

import java.time.Instant
import java.time.ZoneId

/**
 * 每日消費聚合（純 JVM 可測，時區／「現在」都由呼叫方傳入）：
 *  - /api/data/self 已按日回行（同一日多個 model 行）→ dailyFromPoints 聚一聚
 *  - fallback：今月窗口日誌 → dailyFromLogs（LogEntry.createdAt 秒 + quota）
 * 兩個入口都食「raw quota」（同 /api/log/self 同一單位），統一 ÷ qpu 換算 USD。
 * 輸出固定 days 個槽位（舊→新），缺日補 0，令 bar chart 永遠有 7 格。
 */
object SpendStats {

    /** 一個 (unix 秒, raw quota) 點：/api/data/self 行經 ResponseParsing 拆出 */
    data class Point(val tsSec: Long, val quota: Double)

    fun dailyFromPoints(
        points: List<Point>,
        days: Int,
        qpu: Double,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<DailySpend> = daily(points.map { Pair(it.tsSec, it.quota) }, days, qpu, nowMs, zone)

    fun dailyFromLogs(
        rows: List<LogEntry>,
        days: Int,
        qpu: Double,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<DailySpend> = daily(
        rows.mapNotNull { r -> r.quota?.let { Pair(r.createdAt, it) } },
        days, qpu, nowMs, zone,
    )

    /**
     * 按裝置時區「當地日」聚日：dayStartSec = 當地 0 點嘅 epoch 秒。
     * 輸出 = 最近 days 日（含今日）舊→新；window 外嘅點一律唔計。
     */
    private fun daily(
        pairs: List<Pair<Long, Double>>,
        days: Int,
        qpu: Double,
        nowMs: Long,
        zone: ZoneId,
    ): List<DailySpend> {
        val n = days.coerceAtLeast(1)
        val safeQpu = if (qpu.isFinite() && qpu > 0) qpu else Quota.DEFAULT_QPU
        val todayStart = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toEpochSecond()
        val firstStart = todayStart - (n - 1) * 86400L
        val acc = HashMap<Long, Double>()
        for ((ts, quota) in pairs) {
            if (ts < firstStart || ts > todayStart + 86400L - 1) continue
            val d = Instant.ofEpochSecond(ts).atZone(zone).toLocalDate()
                .atStartOfDay(zone).toEpochSecond()
            acc[d] = (acc[d] ?: 0.0) + quota
        }
        return (0 until n).map { i ->
            val d = firstStart + i * 86400L
            DailySpend(d, (acc[d] ?: 0.0) / safeQpu)
        }
    }
}
