package com.newapimonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/**
 * 燒錢預測：今日速度外推 vs 7 日日均，取保守（細 daysLeft）嗰邊；
 * elapsed<15 分鐘唔用今日外推；非零日 <3 個唔用日均；可重現（時間／時區傳入）。
 */
class BurnForecastTest {

    private val zone = ZoneId.of("Asia/Hong_Kong")

    // 2026-09-26 12:00 HKT → elapsed fraction 0.5
    private val nowMs = java.time.Instant.parse("2026-09-26T04:00:00Z").toEpochMilli()

    /** 當地 00:10 HKT（elapsed 10 分鐘 < 15 分鐘） */
    private val earlyMs = java.time.Instant.parse("2026-09-25T16:10:00Z").toEpochMilli()

    @Test
    fun `今日速度更快時用今日速度做基準`() {
        // balance 100：今日消費 10（外推全日 20 → 5 日）；過去日均 10 → 10 日
        // 保守 = 細 daysLeft = 5 → basis 今日速度
        val f = BurnForecast.forecast(
            balanceUsd = 100.0,
            todaySpendUsd = 10.0,
            nowMs = nowMs,
            daily7dUsd = listOf(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0),
            zone = zone,
        )
        assertEquals(5.0, f.daysLeft!!, 1e-9)
        assertEquals(BurnForecast.BASIS_TODAY, f.basis)
        assertEquals(10.0, f.dailyAvg7d!!, 1e-9)
    }

    @Test
    fun `日均更快時用 7日平均做基準`() {
        // 今日消費 2（外推 4 → 25 日）；日均 10 → 10 日 → 保守 = 10 → basis 7日平均
        val f = BurnForecast.forecast(
            balanceUsd = 100.0,
            todaySpendUsd = 2.0,
            nowMs = nowMs,
            daily7dUsd = listOf(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 2.0),
            zone = zone,
        )
        assertEquals(10.0, f.daysLeft!!, 1e-9)
        assertEquals(BurnForecast.BASIS_AVG, f.basis)
    }

    @Test
    fun `elapsed 少於 15 分鐘 - 只用日均`() {
        val f = BurnForecast.forecast(
            balanceUsd = 100.0,
            todaySpendUsd = 500.0, // 即使今日已經燒好多，都唔可靠
            nowMs = earlyMs,
            daily7dUsd = listOf(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 0.0),
            zone = zone,
        )
        assertEquals(10.0, f.daysLeft!!, 1e-9)
        assertEquals(BurnForecast.BASIS_AVG, f.basis)
    }

    @Test
    fun `非零日少過 3 個 - 只用今日外推可得部分`() {
        // 過去 6 日只有 1 日有消費 → 日均唔可靠 → 只用今日（外推 20 → 5 日）
        val f = BurnForecast.forecast(
            balanceUsd = 100.0,
            todaySpendUsd = 10.0,
            nowMs = nowMs,
            daily7dUsd = listOf(0.0, 0.0, 0.0, 0.0, 30.0, 0.0, 10.0),
            zone = zone,
        )
        assertEquals(5.0, f.daysLeft!!, 1e-9)
        assertEquals(BurnForecast.BASIS_TODAY, f.basis)
        // dailyAvg7d 仍然報可得值（非零日均值 30）
        assertEquals(30.0, f.dailyAvg7d!!, 1e-9)
    }

    @Test
    fun `兩邊都唔可得 - 返回 null`() {
        // elapsed < 15 分鐘 + 過去零日 → 完全無法估計
        val f = BurnForecast.forecast(
            balanceUsd = 100.0,
            todaySpendUsd = 5.0,
            nowMs = earlyMs,
            daily7dUsd = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 5.0),
            zone = zone,
        )
        assertNull(f.daysLeft)
        assertNull(f.basis)
    }

    @Test
    fun `餘額 null - 無法預測`() {
        val f = BurnForecast.forecast(
            balanceUsd = null,
            todaySpendUsd = 10.0,
            nowMs = nowMs,
            daily7dUsd = listOf(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0),
            zone = zone,
        )
        assertNull(f.daysLeft)
        assertNull(f.basis)
    }

    @Test
    fun `餘額 0 - 即刻燒清`() {
        val f = BurnForecast.forecast(
            balanceUsd = 0.0,
            todaySpendUsd = 10.0,
            nowMs = nowMs,
            daily7dUsd = listOf(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0),
            zone = zone,
        )
        assertEquals(0.0, f.daysLeft!!, 1e-9)
    }

    @Test
    fun `今日零消費時只用日均`() {
        val f = BurnForecast.forecast(
            balanceUsd = 50.0,
            todaySpendUsd = 0.0,
            nowMs = nowMs,
            daily7dUsd = listOf(5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 0.0),
            zone = zone,
        )
        assertEquals(10.0, f.daysLeft!!, 1e-9)
        assertEquals(BurnForecast.BASIS_AVG, f.basis)
    }

    @Test
    fun `7日列表得 3 個非零日 - 剛好夠用日均`() {
        val f = BurnForecast.forecast(
            balanceUsd = 90.0,
            todaySpendUsd = null,
            nowMs = nowMs,
            daily7dUsd = listOf(4.0, 0.0, 5.0, 0.0, 3.0, 0.0, 0.0),
            zone = zone,
        )
        // 非零日 = 3 → 可用；均值 = 12/3 = 4 → 90/4 = 22.5 日
        assertEquals(22.5, f.daysLeft!!, 1e-9)
        assertEquals(BurnForecast.BASIS_AVG, f.basis)
        assertEquals(4.0, f.dailyAvg7d!!, 1e-9)
    }

    @Test
    fun `今日外推係唯一可得時即使用今日`() {
        // 冇過去數據（得今日一格）
        val f = BurnForecast.forecast(
            balanceUsd = 100.0,
            todaySpendUsd = 8.0,
            nowMs = nowMs,
            daily7dUsd = listOf(8.0),
            zone = zone,
        )
        assertEquals(6.25, f.daysLeft!!, 1e-9) // 8 / 0.5 = 16 全日 → 100/16 = 6.25
        assertEquals(BurnForecast.BASIS_TODAY, f.basis)
    }
}
