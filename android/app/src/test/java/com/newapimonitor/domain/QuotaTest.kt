package com.newapimonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** quota→美元換算：優先用戶設定 → /api/status quota_per_unit → fallback 500000；usage 美分 ÷100 */
class QuotaTest {

    @Test
    fun `用戶設定覆蓋 status 值`() {
        assertEquals(500000.0, Quota.resolveQpu(instOverride = 500000.0, statusQpu = 600000.0), 1e-9)
    }

    @Test
    fun `無用戶設定時用 status 值`() {
        assertEquals(600000.0, Quota.resolveQpu(instOverride = null, statusQpu = 600000.0), 1e-9)
    }

    @Test
    fun `兩者皆缺時 fallback 500000`() {
        assertEquals(500000.0, Quota.resolveQpu(null, null), 1e-9)
    }

    @Test
    fun `非法值（0 負數 NaN）被跳過`() {
        assertEquals(600000.0, Quota.resolveQpu(0.0, 600000.0), 1e-9)
        assertEquals(600000.0, Quota.resolveQpu(-1.0, 600000.0), 1e-9)
        assertEquals(500000.0, Quota.resolveQpu(Double.NaN, 0.0), 1e-9)
    }

    @Test
    fun `toUsd 按 qpu 換算`() {
        assertEquals(1.0, Quota.toUsd(500000.0, 500000.0), 1e-12)
        assertEquals(2.0, Quota.toUsd(1000000.0, 500000.0), 1e-12)
        assertEquals(0.5, Quota.toUsd(300000.0, 600000.0), 1e-12)
    }

    @Test
    fun `toUsd qpu 無效時用 fallback`() {
        assertEquals(1.0, Quota.toUsd(500000.0, 0.0), 1e-12)
    }

    @Test
    fun `billing usage 換算為站點展示單位而非無條件美元`() {
        assertEquals(10.72, Quota.billingUsageHundredthsToAmount(1072.0), 1e-12)
        assertEquals(0.0, Quota.billingUsageHundredthsToAmount(0.0), 1e-12)
    }
}
