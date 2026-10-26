package com.newapimonitor.data.monitor

import androidx.test.core.app.ApplicationProvider
import com.newapimonitor.TestMonitorApp
import com.newapimonitor.domain.Instance
import com.newapimonitor.domain.LogEntry
import com.newapimonitor.domain.ReportRange
import com.newapimonitor.widget.WidgetChoice
import com.newapimonitor.widget.WidgetOptionsStore
import com.newapimonitor.widget.widgetDataForChoice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class MonitorUpgradeTest {
    private val app get() = ApplicationProvider.getApplicationContext<TestMonitorApp>()
    private val a = Instance("a", "Alpha", "https://a.test", apiKey = "key-a")
    private val b = Instance("b", "Beta", "https://b.test", apiKey = "key-b")

    @Test fun `home order pins and exclusions survive persistence`() = runBlocking {
        app.store.ready.first { it }; app.store.wipeAll()
        val store = MonitorPreferencesStore(app)
        store.update { it.copy(compact = true, hideMoney = true, pinned = setOf("b"), order = listOf("b", "a"), excluded = setOf("b")) }
        val prefs = MonitorPreferencesStore(app).preferences.first()
        assertEquals(listOf("b", "a"), prefs.sorted(listOf(a, b)) { it.id }.map { it.id })
        assertTrue(prefs.compact && prefs.hideMoney)
        assertEquals(setOf("b"), prefs.excluded)
        store.remove("b")
        assertTrue(store.preferences.first().pinned.isEmpty())
        assertTrue(store.preferences.first().excluded.isEmpty())
    }

    @Test fun `overnight quiet hours and cooldown boundaries`() = runBlocking {
        app.store.ready.first { it }; app.store.wipeAll()
        val rule = InstanceAlertRule(enabled = true, balanceBelow = 5.0, quietStart = "22:30", quietEnd = "07:00", cooldownMinutes = 30)
        assertTrue(rule.quiet(LocalTime.of(23, 0)))
        assertTrue(rule.quiet(LocalTime.of(6, 59)))
        assertFalse(rule.quiet(LocalTime.of(7, 0)))
        assertFalse(rule.quiet(LocalTime.of(22, 29)))
        assertTrue(runCatching { rule.copy(quietEnd = "").validate() }.isFailure)
        assertTrue(runCatching { rule.copy(dailyAbove = -1.0).validate() }.isFailure)
        val store = MonitorPreferencesStore(app)
        store.saveRule("a", rule)
        assertTrue(store.claim("a", "balance", rule, 1_000_000L))
        assertFalse(store.claim("a", "balance", rule, 1_000_000L + 29 * 60_000L))
        assertTrue(store.claim("a", "balance", rule, 1_000_000L + 30 * 60_000L))
        assertTrue(store.claim("a", "daily", rule, 1_000_001L))
        assertEquals(rule, MonitorPreferencesStore(app).rules.first()["a"])
    }

    @Test fun `cross report retains unknown source and quotes spreadsheet formulas`() {
        val range = ReportRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2))
        val one = PeriodReport(range, ReportSlice(3.0, listOf(LogEntry(type = 2, modelName = "=SUM(1)", quota = 500000.0)), partial = true, qpu = 500000.0),
            ReportSlice(null, null, partial = false))
        val report = CrossReport(range, listOf(SourceReport("a", "+Alpha", one), SourceReport("b", "Beta", failure = "timeout")))
        assertEquals(1, report.available)
        assertEquals(3.0, report.total!!, 0.0)
        val csv = ReportCsv.render(report)
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("\"'+Alpha\""))
        assertTrue(csv.contains("\"'=SUM(1)\""))
        assertTrue(csv.contains("\"Beta\""))
        assertTrue(csv.contains("\"timeout\""))
        assertTrue(csv.contains("\"partial logs\""))
        assertEquals(null, CrossReport(range, listOf(SourceReport("b", "Beta", failure = "timeout"))).total)
    }

    @Test fun `widget choices independent and all totals respect excluded balances`() = runBlocking {
        app.store.ready.first { it }; app.store.wipeAll()
        val choices = WidgetOptionsStore(app)
        choices.save(101, WidgetChoice("all", hideMoney = false))
        choices.save(102, WidgetChoice("b", hideMoney = true))
        assertEquals("all", WidgetOptionsStore(app).read(101).source)
        assertTrue(WidgetOptionsStore(app).read(102).hideMoney)
        val now = System.currentTimeMillis()
        fun row(i: Instance, balance: Double, spend: Double) = MonitorSnapshot(i.id, balanceUsd = balance, todayUsd = spend,
            monthUsd = spend, balanceAt = now, spendAt = now, attemptedAt = now,
            day = LocalDate.now().toString(), month = java.time.YearMonth.now().toString(), sourceStamp = MonitorSnapshot.stamp(i))
        val rows = listOf(row(a, 5.0, 2.0), row(b, 10.0, 3.0))
        val all = widgetDataForChoice(choices.read(101), listOf(a, b), "b", rows, mapOf("a" to 10.0, "b" to 20.0),
            MonitorPreferences(excluded = setOf("b")), now)
        assertEquals("$5.00", all.balanceText)
        assertEquals("$5.00", all.todayText)
        assertEquals(16, all.budgetProgress)
        val private = widgetDataForChoice(choices.read(102), listOf(a, b), "a", rows, emptyMap(), MonitorPreferences(), now)
        assertEquals("••••", private.balanceText)
        assertEquals("••••", private.todayText)
        assertEquals("Beta", private.title)
        choices.remove(102)
        assertEquals("active", choices.read(102).source)
        assertEquals("all", choices.read(101).source)
    }
}
