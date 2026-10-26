package com.newapimonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 消費日曆：月格形狀（週行 ×7、2026-09 由星期二開月）、總和／消費日數、
 * tier 四分位邊界、今日／未來旗、dailyTotals 當地日聚合。
 */
class SpendCalendarTest {

    private val zone = ZoneId.of("Asia/Hong_Kong")

    /** 某當地日 0 點（HKT）嘅 epoch 秒 */
    private fun sec(date: String): Long = LocalDate.parse(date).atStartOfDay(zone).toEpochSecond()

    /** 2026-09-15 12:00 HKT（月中「現在」） */
    private val nowMs: Long = ZonedDateTime.of(2026, 9, 15, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `2026年9月格形 - 週行x7 - 星期二開月`() {
        val g = SpendCalendar.build(
            2026, 9,
            listOf(SpendCalendar.DailyUsd(sec("2026-09-10"), 1.0)),
            nowMs, zone,
        )
        // 2026-09-01 係星期二 → 週一 1 格空位；1 + 30 日 = 31 格 → 5 週行
        assertEquals(5, g.weeks.size)
        g.weeks.forEach { assertEquals(7, it.size) }
        assertNull(g.weeks[0][0])
        val first = g.weeks[0][1]!!
        assertEquals(1, first.day)
        assertEquals(sec("2026-09-01"), first.dayStartSec)
        // 第一行：一空、二 1 日（day 1 落喺 index 1）… 日 2..6 佔週三..週日（day == index）
        for (i in 2..6) assertEquals(i, g.weeks[0][i]!!.day)
        // 每行開頭：7 / 14 / 21 / 28
        assertEquals(7, g.weeks[1][0]!!.day)
        assertEquals(14, g.weeks[2][0]!!.day)
        assertEquals(21, g.weeks[3][0]!!.day)
        assertEquals(28, g.weeks[4][0]!!.day)
        // 30 日喺第 5 行週三（index 2），之後補 null
        assertEquals(30, g.weeks[4][2]!!.day)
        assertNull(g.weeks[4][3])
        assertNull(g.weeks[4][6])
    }

    @Test
    fun `2026年2月平年 - 週日開月`() {
        // 2026-02-01 係星期日 → 6 格前置空位；6 + 28 = 34 格 → 5 週行
        val g = SpendCalendar.build(2026, 2, emptyList(), nowMs, zone)
        assertEquals(5, g.weeks.size)
        assertNull(g.weeks[0][5])
        assertEquals(1, g.weeks[0][6]!!.day)
        assertEquals(22, g.weeks[3][6]!!.day)
        assertEquals(28, g.weeks[4][5]!!.day)
        assertNull(g.weeks[4][6])
        assertEquals(0.0, g.totalUsd, 1e-9)
        assertEquals(0, g.spendDays)
    }

    @Test
    fun `總和同消費日數`() {
        val g = SpendCalendar.build(
            2026, 9,
            listOf(
                SpendCalendar.DailyUsd(sec("2026-09-02"), 1.25),
                SpendCalendar.DailyUsd(sec("2026-09-09"), 2.0),
                SpendCalendar.DailyUsd(sec("2026-09-20"), 0.5),
            ),
            nowMs, zone,
        )
        assertEquals(3.75, g.totalUsd, 1e-9)
        assertEquals(3, g.spendDays)
    }

    @Test
    fun `月外日唔入格`() {
        val g = SpendCalendar.build(
            2026, 9,
            listOf(
                SpendCalendar.DailyUsd(sec("2026-08-31"), 99.0),
                SpendCalendar.DailyUsd(sec("2026-10-01"), 88.0),
                SpendCalendar.DailyUsd(sec("2026-09-05"), 5.0),
            ),
            nowMs, zone,
        )
        assertEquals(5.0, g.totalUsd, 1e-9)
        assertEquals(1, g.spendDays)
    }

    @Test
    fun `tier 邊界 - 非零日四分位`() {
        // 9 月 10..13 日 = $1/$2/$3/$4（sorted [1,2,3,4] → q1=1 q2=2 q3=3 → tier 1/2/3/4）
        val daily = listOf(
            SpendCalendar.DailyUsd(sec("2026-09-10"), 1.0),
            SpendCalendar.DailyUsd(sec("2026-09-11"), 2.0),
            SpendCalendar.DailyUsd(sec("2026-09-12"), 3.0),
            SpendCalendar.DailyUsd(sec("2026-09-13"), 4.0),
        )
        val g = SpendCalendar.build(2026, 9, daily, nowMs, zone)
        assertEquals(1, g.weeks[1][3]!!.tier) // 9月10日（週四）→ tier 1（<= q1）
        assertEquals(2, g.weeks[1][4]!!.tier) // 9月11日 → tier 2（<= q2）
        assertEquals(3, g.weeks[1][5]!!.tier) // 9月12日 → tier 3（<= q3）
        assertEquals(4, g.weeks[1][6]!!.tier) // 9月13日 → tier 4（> q3）
        assertEquals(0, g.weeks[0][1]!!.tier) // 冇消費日 → tier 0
        assertEquals(5, SpendCalendar.TIER_ALPHAS.size)
        assertEquals(0.25f, SpendCalendar.TIER_ALPHAS[1])
        assertEquals(0.45f, SpendCalendar.TIER_ALPHAS[2])
        assertEquals(0.7f, SpendCalendar.TIER_ALPHAS[3])
        assertEquals(0.95f, SpendCalendar.TIER_ALPHAS[4])
    }

    @Test
    fun `tier - 單一消費日層級 1 - 相同金額全層級 1`() {
        val single = SpendCalendar.build(
            2026, 9, listOf(SpendCalendar.DailyUsd(sec("2026-09-08"), 7.0)), nowMs, zone,
        )
        assertEquals(1, single.weeks[1][1]!!.tier)

        val equal = SpendCalendar.build(
            2026, 9,
            (1..4).map { SpendCalendar.DailyUsd(sec("2026-09-0$it"), 3.0) },
            nowMs, zone,
        )
        // 消費嗰四格（週二..週五 = index 1..4）全部同金額 → 全 tier 1；其餘零消費日 tier 0
        for (i in 1..4) assertEquals(1, equal.weeks[0][i]!!.tier)
        assertEquals(0, equal.weeks[0][5]!!.tier)
    }

    @Test
    fun `今日旗 - 15 日係今日 - 16 日係未來`() {
        val daily = listOf(
            SpendCalendar.DailyUsd(sec("2026-09-14"), 1.0),
            SpendCalendar.DailyUsd(sec("2026-09-15"), 2.0),
        )
        val g = SpendCalendar.build(2026, 9, daily, nowMs, zone)
        val d14 = g.weeks[2][0]!! // 週一
        val d15 = g.weeks[2][1]!!
        val d16 = g.weeks[2][2]!!
        assertFalse(d14.isToday); assertFalse(d14.isFuture)
        assertTrue(d15.isToday); assertFalse(d15.isFuture)
        assertFalse(d16.isToday); assertTrue(d16.isFuture)
        // 月初（過去）同 30 日（未來）都唔係今日
        assertFalse(g.weeks[0][1]!!.isToday)
        assertTrue(g.weeks[4][2]!!.isFuture)
    }

    @Test
    fun `dailyTotals - 同日多點聚合加 qpu 換算`() {
        val points = listOf(
            SpendStats.Point(sec("2026-09-14") + 3600, 500000.0),   // 09-14 01:00 HKT，$1
            SpendStats.Point(sec("2026-09-14") + 84600, 250000.0),  // 09-14 23:30 HKT，$0.5
            SpendStats.Point(sec("2026-09-15") + 60, 500000.0),     // 09-15，$1
        )
        val out = SpendCalendar.dailyTotals(points, qpu = 500000.0, zone = zone)
        assertEquals(2, out.size)
        assertEquals(sec("2026-09-14"), out[0].dayStartSec)
        assertEquals(1.5, out[0].usd, 1e-9)
        assertEquals(sec("2026-09-15"), out[1].dayStartSec)
        assertEquals(1.0, out[1].usd, 1e-9)
    }

    @Test
    fun `dailyTotals - UTC 深夜歸入 HKT 當地日 - 零消費點唔出`() {
        // 2026-09-13 18:00 UTC = 09-14 02:00 HKT
        val points = listOf(
            SpendStats.Point(java.time.Instant.parse("2026-09-13T18:00:00Z").epochSecond, 500000.0),
            SpendStats.Point(sec("2026-09-16"), 0.0),
        )
        val out = SpendCalendar.dailyTotals(points, qpu = 500000.0, zone = zone)
        assertEquals(1, out.size)
        assertEquals(sec("2026-09-14"), out[0].dayStartSec)
    }

    @Test
    fun `dailyTotals - qpu 非法用預設兜底`() {
        val out = SpendCalendar.dailyTotals(
            listOf(SpendStats.Point(sec("2026-09-14"), Quota.DEFAULT_QPU)),
            qpu = 0.0, zone = zone,
        )
        assertEquals(1, out.size)
        assertEquals(1.0, out[0].usd, 1e-9)
    }
}
