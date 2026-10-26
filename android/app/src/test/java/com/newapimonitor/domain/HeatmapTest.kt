package com.newapimonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/**
 * 時段熱力圖：24 格每小時 USD（裝置時區 hour-of-day）、max、type 過濾、qpu 換算。
 */
class HeatmapTest {

    private val zone = ZoneId.of("Asia/Hong_Kong")
    private val qpu = 500000.0

    /** 某當地日 YYYY-MM-DD HH:mm（HKT）嘅 epoch 秒 */
    private fun sec(date: String, hh: Int, mm: Int): Long =
        java.time.LocalDate.parse(date).atTime(hh, mm).atZone(zone).toEpochSecond()

    @Test
    fun `按小時分桶並換算 USD`() {
        val rows = listOf(
            LogEntry(type = 2, createdAt = sec("2026-09-26", 12, 0), modelName = "gpt-4o", quota = 500000.0), // $1.0 → hour 12
            LogEntry(type = 2, createdAt = sec("2026-09-26", 12, 30), modelName = "gpt-4o", quota = 250000.0), // $0.5 → hour 12
            LogEntry(type = 2, createdAt = sec("2026-09-26", 23, 59), modelName = "gpt-4o", quota = 250000.0), // $0.5 → hour 23
        )
        val r = Heatmap.compute(rows, qpu, zone)
        assertEquals(24, r.byHour.size)
        assertEquals(1.5, r.byHour[12], 1e-9)
        assertEquals(0.5, r.byHour[23], 1e-9)
        assertEquals(0.0, r.byHour[0], 1e-9)
        assertEquals(1.5, r.max, 1e-9)
    }

    @Test
    fun `時區換算 - UTC 深夜落在 HKT 當地小時`() {
        // 2026-09-25 17:30 UTC = 2026-09-26 01:30 HKT → hour 1
        val rows = listOf(
            LogEntry(
                type = 2,
                createdAt = java.time.Instant.parse("2026-09-25T17:30:00Z").epochSecond,
                modelName = "gpt-4o",
                quota = 500000.0,
            ),
        )
        val r = Heatmap.compute(rows, qpu, zone)
        assertEquals(1.0, r.byHour[1], 1e-9)
    }

    @Test
    fun `非消費行唔入數 - 充值與錯誤行`() {
        val rows = listOf(
            LogEntry(type = 1, createdAt = sec("2026-09-26", 9, 0), modelName = "topup", quota = 500000.0),
            LogEntry(type = 5, createdAt = sec("2026-09-26", 9, 10), modelName = "gpt-4o", quota = 0.0),
            LogEntry(type = null, createdAt = sec("2026-09-26", 9, 20), modelName = null, quota = 999.0),
        )
        val r = Heatmap.compute(rows, qpu, zone)
        assertEquals(0.0, r.byHour[9], 1e-9)
        assertEquals(0.0, r.max, 1e-9)
    }

    @Test
    fun `qpu 非法時用預設值兜底`() {
        val rows = listOf(
            LogEntry(type = 2, createdAt = sec("2026-09-26", 8, 0), modelName = "gpt-4o", quota = Quota.DEFAULT_QPU),
        )
        val r = Heatmap.compute(rows, 0.0, zone)
        assertEquals(1.0, r.byHour[8], 1e-9)
    }

    @Test
    fun `空輸入全零 max 為零`() {
        val r = Heatmap.compute(emptyList(), qpu, zone)
        assertEquals(24, r.byHour.size)
        r.byHour.forEach { assertEquals(0.0, it, 1e-9) }
        assertEquals(0.0, r.max, 1e-9)
    }
}
