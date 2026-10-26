package com.newapimonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/**
 * 7 日消費聚日（儀表板統計卡）：按指定時區「當地日」聚合、固定 7 槽位零填充、
 * 窗口外數據唔計、raw quota ÷ qpu 換算 USD；時間／時區固定傳入，完全可重現。
 */
class SpendStatsTest {

    private val zone = ZoneId.of("Asia/Hong_Kong")
    private val qpu = 500000.0

    // 2026-09-26 12:00 HKT = 2026-09-26 04:00 UTC
    private val nowMs = java.time.Instant.parse("2026-09-26T04:00:00Z").toEpochMilli()

    /** 某當地日 YYYY-MM-DD 中午 12 點（HKT）嘅 epoch 秒 */
    private fun noonSec(date: String): Long =
        java.time.LocalDate.parse(date).atTime(12, 0).atZone(zone).toEpochSecond()

    private fun dayStart(date: String): Long =
        java.time.LocalDate.parse(date).atStartOfDay(zone).toEpochSecond()

    @Test
    fun `同日多點聚合到同一槽位 - 輸出舊到新 7 格`() {
        val points = listOf(
            SpendStats.Point(noonSec("2026-09-26"), 250000.0), // 0.5 USD
            SpendStats.Point(noonSec("2026-09-26"), 125000.0), // 0.25 USD
            SpendStats.Point(noonSec("2026-09-25"), 500000.0), // 1.0 USD
            SpendStats.Point(noonSec("2026-09-20"), 375000.0), // 0.75 USD
        )
        val days = SpendStats.dailyFromPoints(points, days = 7, qpu = qpu, nowMs = nowMs, zone = zone)
        assertEquals(7, days.size)
        // 舊→新：09-20 .. 09-26
        assertEquals(dayStart("2026-09-20"), days[0].dayStartSec)
        assertEquals(dayStart("2026-09-26"), days[6].dayStartSec)
        assertEquals(0.75, days[0].usd, 1e-9)
        // 09-25（index 5）與 09-26（index 6）
        assertEquals(1.0, days[5].usd, 1e-9)
        assertEquals(0.75, days[6].usd, 1e-9)
        // 中間缺日補 0
        assertEquals(0.0, days[1].usd, 1e-9)
        assertEquals(0.0, days[4].usd, 1e-9)
    }

    @Test
    fun `窗口外嘅點唔計 - 唔會污染 7 日窗口`() {
        val points = listOf(
            SpendStats.Point(noonSec("2026-09-19"), 999999.0), // 恰好窗口前一日（7 日窗口由 09-20 起）
            SpendStats.Point(noonSec("2026-09-26"), 250000.0),
        )
        val days = SpendStats.dailyFromPoints(points, days = 7, qpu = qpu, nowMs = nowMs, zone = zone)
        assertEquals(0.0, days[0].usd, 1e-9)
        assertEquals(0.5, days[6].usd, 1e-9)
    }

    @Test
    fun `時區換算 - UTC 深夜落在 HKT 當地日`() {
        // 2026-09-25 18:00 UTC = 2026-09-26 02:00 HKT → 應聚落 09-26
        val points = listOf(
            SpendStats.Point(java.time.Instant.parse("2026-09-25T18:00:00Z").epochSecond, 250000.0),
        )
        val days = SpendStats.dailyFromPoints(points, days = 7, qpu = qpu, nowMs = nowMs, zone = zone)
        assertEquals(0.5, days[6].usd, 1e-9)
        assertEquals(dayStart("2026-09-26"), days[6].dayStartSec)
    }

    @Test
    fun `dailyFromLogs 用日誌形狀聚日 - quota 缺失行跳過`() {
        val rows = listOf(
            LogEntry(type = 2, createdAt = noonSec("2026-09-24"), modelName = "gpt-4o", quota = 1000.0),
            LogEntry(type = 2, createdAt = noonSec("2026-09-24"), modelName = "gpt-4o", quota = 500.0),
            LogEntry(type = 2, createdAt = noonSec("2026-09-23"), modelName = "gpt-4o", quota = null), // 跳過
            LogEntry(type = 1, createdAt = noonSec("2026-09-22"), modelName = "topup", quota = 250000.0), // 過濾歸上層，呢度照計（fallback 先行 filterConsume）
        )
        val days = SpendStats.dailyFromLogs(rows, days = 7, qpu = qpu, nowMs = nowMs, zone = zone)
        assertEquals(1500.0 / qpu, days[4].usd, 1e-9) // 09-24: 1500 raw
        assertEquals(0.0, days[3].usd, 1e-9)          // 09-23: quota 缺失唔計
        assertEquals(0.5, days[2].usd, 1e-9)          // 09-22: 250000 raw
    }

    @Test
    fun `qpu 非法時用預設值兜底`() {
        val points = listOf(SpendStats.Point(noonSec("2026-09-26"), Quota.DEFAULT_QPU))
        val days = SpendStats.dailyFromPoints(points, days = 7, qpu = 0.0, nowMs = nowMs, zone = zone)
        assertEquals(1.0, days[6].usd, 1e-9)
    }

    @Test
    fun `空輸入仍然返回 7 個零槽位`() {
        val days = SpendStats.dailyFromPoints(emptyList(), days = 7, qpu = qpu, nowMs = nowMs, zone = zone)
        assertEquals(7, days.size)
        days.forEach { assertEquals(0.0, it.usd, 1e-9) }
    }
}
