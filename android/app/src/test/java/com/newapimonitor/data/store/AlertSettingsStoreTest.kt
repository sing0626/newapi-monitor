package com.newapimonitor.data.store

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.newapimonitor.domain.AlertSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 警報設定持久化（全域一份）：預設值、update roundtrip、防禦式清洗 */
@RunWith(RobolectricTestRunner::class)
class AlertSettingsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `預設設定 - 關閉與合理預設`() = runBlocking {
        val store = AlertSettingsStore(context)
        // 先歸零（DataStore 喺同 sandbox 跨測試共享，其他測試可能寫過）
        store.update { AlertSettings() }
        val s = AlertSettingsStore(context).current()
        assertFalse(s.enabled)
        assertNull(s.balanceBelowUsd)
        assertNull(s.dailySpendAboveUsd)
        assertNull(s.monthlyBudgetUsd)
        assertEquals(3.0, s.spikeMultiplier, 1e-9)
        assertEquals(60, s.cooldownMinutes)
        assertTrue(s.notifyTokenBanned)
        assertNull(s.monthlyBudgetUsd)
        assertFalse(s.notifyErrorRate)
        assertFalse(s.notifyTokenExpiry)
        assertFalse(s.persistentStatus)
    }

    @Test
    fun `update roundtrip - 新實例讀到同一份`() = runBlocking {
        val store = AlertSettingsStore(context)
        store.update {
            it.copy(
                enabled = true,
                balanceBelowUsd = 12.34,
                dailySpendAboveUsd = 56.78,
                spikeMultiplier = 2.5,
                cooldownMinutes = 30,
                notifyTokenBanned = false,
                monthlyBudgetUsd = 120.0,
                notifyErrorRate = true,
                notifyTokenExpiry = true,
                persistentStatus = true,
            )
        }
        val s2 = AlertSettingsStore(context).current()
        assertEquals(true, s2.enabled)
        assertEquals(12.34, s2.balanceBelowUsd!!, 1e-9)
        assertEquals(56.78, s2.dailySpendAboveUsd!!, 1e-9)
        assertEquals(2.5, s2.spikeMultiplier, 1e-9)
        assertEquals(30, s2.cooldownMinutes)
        assertFalse(s2.notifyTokenBanned)
        assertEquals(120.0, s2.monthlyBudgetUsd!!, 1e-9)
        assertTrue(s2.notifyErrorRate)
        assertTrue(s2.notifyTokenExpiry)
        assertTrue(s2.persistentStatus)
    }

    @Test
    fun `sanitize - 荒謬值收返合理範圍`() {
        val store = AlertSettingsStore(context)
        val s = store.sanitize(
            AlertSettings(
                enabled = true,
                balanceBelowUsd = -5.0,
                dailySpendAboveUsd = -1.0,
                spikeMultiplier = Double.NEGATIVE_INFINITY,
                cooldownMinutes = -10,
                notifyTokenBanned = true,
                monthlyBudgetUsd = Double.NaN,
            )
        )
        assertNull(s.balanceBelowUsd)
        assertNull(s.dailySpendAboveUsd)
        assertNull(s.monthlyBudgetUsd)
        assertEquals(3.0, s.spikeMultiplier, 1e-9)
        assertEquals(1, s.cooldownMinutes)
    }

    @Test
    fun `cooldownMinutes 上限 24 小時`() {
        val store = AlertSettingsStore(context)
        assertEquals(24 * 60, store.sanitize(AlertSettings(cooldownMinutes = 999_999)).cooldownMinutes)
    }
}
