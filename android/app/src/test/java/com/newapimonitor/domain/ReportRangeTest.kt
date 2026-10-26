package com.newapimonitor.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ReportRangeTest {
    @Test fun `month to date compares the same dates in the previous month and clamps short months`() {
        val october=ReportRange(LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-18"))
        assertEquals(ReportRange(LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-18")),october.previousMonth())
        val march=ReportRange(LocalDate.parse("2024-03-01"),LocalDate.parse("2024-03-31"))
        assertEquals(LocalDate.parse("2024-02-29"),march.previousMonth().end)
    }
    @Test fun `equal day comparison includes year and leap day boundaries`() {
        val range = ReportRange(LocalDate.parse("2024-02-28"), LocalDate.parse("2024-03-01"))
        assertEquals(3L, range.days)
        assertEquals(ReportRange(LocalDate.parse("2024-02-25"), LocalDate.parse("2024-02-27")), range.previous())
        assertEquals(ReportRange(LocalDate.parse("2023-12-25"), LocalDate.parse("2023-12-31")), ReportRange.lastSeven(LocalDate.parse("2024-01-07")).previous())
    }
    @Test fun `local midnight includes Hong Kong early morning and handles DST`() {
        val day = LocalDate.parse("2026-03-08")
        val range = ReportRange(day, day)
        val zone = ZoneId.of("America/New_York")
        assertEquals(23 * 3600L, range.endSeconds(zone) - range.startSeconds(zone) + 1)
        assertEquals(day, java.time.Instant.ofEpochSecond(range.startSeconds(ZoneId.of("Asia/Hong_Kong")) + 3600).atZone(ZoneId.of("Asia/Hong_Kong")).toLocalDate())
    }
    @Test fun `invalid future reversed and oversized windows are rejected`() {
        val today = LocalDate.parse("2026-10-18")
        for (range in listOf(ReportRange(today, today.plusDays(1)), ReportRange(today, today.minusDays(1)), ReportRange(today.minusDays(366), today))) {
            assertTrue(runCatching { range.validate(today) }.isFailure)
        }
        assertEquals(today, ReportRange.month(today, today).end)
    }
    @Test fun `cost grouping does not count errors and zero baseline does not invent a percent`() {
        val rows = listOf(LogEntry(type=2, modelName="m", tokenName="t", quota=500000.0), LogEntry(type=5, modelName="m", quota=900000.0))
        assertEquals(listOf(CostGroup("m",1.0,1)), CostBreakdown.group(rows,500000.0))
        assertEquals("t", CostBreakdown.group(rows,500000.0,true).single().name)
        assertNull(CostBreakdown.change(10.0,0.0)); assertNull(CostBreakdown.change(null,10.0))
        assertEquals(50.0,CostBreakdown.change(15.0,10.0)!!,0.0)
    }
}
