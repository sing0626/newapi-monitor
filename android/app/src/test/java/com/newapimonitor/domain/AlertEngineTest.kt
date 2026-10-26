package com.newapimonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.newapimonitor.ui.theme.alertMessage
import java.time.ZoneId

/**
 * 警報引擎（純函數）：餘額門檻＋滯回 rearm、每日消費、spike 外推、令牌封禁、
 * cooldown 同狀態輸出。時間／時區固定傳入，完全可重現。
 */
class AlertEngineTest {

    private val zone = ZoneId.of("Asia/Hong_Kong")

    // 2026-09-26 12:00 HKT → 今日已過 12 小時（elapsed fraction 0.5）
    private val nowMs = java.time.Instant.parse("2026-09-26T04:00:00Z").toEpochMilli()

    private val settings = AlertSettings(
        enabled = true,
        balanceBelowUsd = 10.0,
        dailySpendAboveUsd = 5.0,
        spikeMultiplier = 3.0,
        cooldownMinutes = 60,
        notifyTokenBanned = true,
    )

    private fun input(
        balanceUsd: Double? = 100.0,
        todaySpendUsd: Double? = 1.0,
        daily7dUsd: List<Double> = listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, todaySpendUsd ?: 0.0),
        banned: Set<Long> = emptySet(),
        prevBanned: Set<Long> = emptySet(),
        lastFiredMs: Map<AlertType, Long> = emptyMap(),
        armed: Boolean = true,
        settings: AlertSettings = this.settings,
        prevBalanceUsd: Double? = null,
        nowMs: Long = this.nowMs,
    ) = AlertEngine.Input(
        instId = "i1",
        instName = "測試站",
        prev = prevBalanceUsd?.let { AlertEngine.SnapshotSummary(it, null) },
        current = AlertEngine.SnapshotSummary(balanceUsd, todaySpendUsd),
        daily7dUsd = daily7dUsd,
        bannedTokenIds = banned,
        prevBannedTokenIds = prevBanned,
        lastFiredMs = lastFiredMs,
        armedBalanceOk = armed,
        settings = settings,
        nowMs = nowMs,
        zone = zone,
    )

    /* ---------------- BALANCE_LOW ---------------- */

    @Test
    fun `餘額低於門檻觸發 - 觸發後 disarm`() {
        val r = AlertEngine.evaluate(input(balanceUsd = 8.0, armed = true))
        assertEquals(1, r.events.size)
        assertEquals(AlertType.BALANCE_LOW, r.events[0].type)
        assertTrue(r.events[0].title.isNotBlank() && r.events[0].body.contains("測試站"))
        assertFalse(r.armedBalanceOk)
        assertEquals(nowMs, r.lastFiredMs[AlertType.BALANCE_LOW])
    }

    @Test
    fun `disarm 中唔會重觸 - 回復到門檻 1_1 倍以上先重新 arm`() {
        // armed=false + 餘額仲低 → 唔觸發、保持 disarm
        var r = AlertEngine.evaluate(input(balanceUsd = 9.0, armed = false))
        assertTrue(r.events.isEmpty())
        assertFalse(r.armedBalanceOk)
        // 餘額 10.5（> 門檻但 < 門檻×1.1）→ 仍然唔 arm
        r = AlertEngine.evaluate(input(balanceUsd = 10.5, armed = false))
        assertTrue(r.events.isEmpty())
        assertFalse(r.armedBalanceOk)
        // 餘額 11.5（≥ 門檻×1.1）→ 重新 arm
        r = AlertEngine.evaluate(input(balanceUsd = 11.5, armed = false))
        assertTrue(r.events.isEmpty())
        assertTrue(r.armedBalanceOk)
    }

    @Test
    fun `餘額警報尊重 cooldown`() {
        val lastFired = mapOf(AlertType.BALANCE_LOW to nowMs - 30 * 60_000L) // 30 分鐘前
        val r = AlertEngine.evaluate(input(balanceUsd = 5.0, lastFiredMs = lastFired))
        assertTrue(r.events.isEmpty())
    }

    @Test
    fun `首次觀察就已經低過門檻都會觸發`() {
        val r = AlertEngine.evaluate(input(balanceUsd = 3.0, prevBalanceUsd = null))
        assertEquals(1, r.events.size)
        assertEquals(AlertType.BALANCE_LOW, r.events[0].type)
    }

    /* ---------------- DAILY_SPEND ---------------- */

    @Test
    fun `今日消費超標觸發 - 同一個當地日只響一次`() {
        // 日均基線 50（好高）→ SPIKE 唔會同時響，專注驗 DAILY_SPEND
        val daily = { spend: Double -> listOf(50.0, 50.0, 50.0, 50.0, 50.0, 50.0, spend) }
        val r = AlertEngine.evaluate(input(todaySpendUsd = 6.0, daily7dUsd = daily(6.0)))
        assertEquals(1, r.events.size)
        assertEquals(AlertType.DAILY_SPEND, r.events[0].type)

        // 同日再評估（消費仲係超標）→ 唔重觸
        val r2 = AlertEngine.evaluate(
            input(todaySpendUsd = 7.0, daily7dUsd = daily(7.0), lastFiredMs = r.lastFiredMs)
        )
        assertTrue(r2.events.isEmpty())
    }

    @Test
    fun `消費警報過一日之後可以再響`() {
        val yesterday = nowMs - 24 * 3_600_000L
        val daily = listOf(50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 6.0) // 基線高 → 只有 DAILY_SPEND 會響
        val r = AlertEngine.evaluate(
            input(todaySpendUsd = 6.0, daily7dUsd = daily, lastFiredMs = mapOf(AlertType.DAILY_SPEND to yesterday))
        )
        assertEquals(1, r.events.size)
        assertEquals(AlertType.DAILY_SPEND, r.events[0].type)
    }

    /* ---------------- SPIKE ---------------- */

    @Test
    fun `今日速度外推超過日均倍數觸發`() {
        // now 12:00 HKT（fraction 0.5），今日消費 10 → 外推 20；日均基線 = 3.5 → 3.5×3 = 10.5 ≤ 20 ✓
        val daily = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 10.0)
        val r = AlertEngine.evaluate(input(todaySpendUsd = 10.0, daily7dUsd = daily))
        assertTrue(r.events.any { it.type == AlertType.SPIKE })
    }

    @Test
    fun `外推未到日均倍數唔觸發`() {
        val daily = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 10.0) // 基線 3.5
        val r = AlertEngine.evaluate(input(todaySpendUsd = 1.0, daily7dUsd = daily)) // 外推 2 < 10.5
        assertTrue(r.events.none { it.type == AlertType.SPIKE })
    }

    @Test
    fun `elapsed 少於 15 分鐘唔做外推`() {
        // 當地 00:05 HKT（elapsed 5 分鐘）
        val earlyMs = java.time.Instant.parse("2026-09-25T16:05:00Z").toEpochMilli()
        val r = AlertEngine.evaluate(
            input(todaySpendUsd = 100.0, daily7dUsd = listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 100.0), nowMs = earlyMs)
        )
        assertTrue(r.events.none { it.type == AlertType.SPIKE })
    }

    @Test
    fun `spikeMultiplier 0 = 關閉`() {
        val s = settings.copy(spikeMultiplier = 0.0)
        val r = AlertEngine.evaluate(input(todaySpendUsd = 50.0, settings = s))
        assertTrue(r.events.none { it.type == AlertType.SPIKE })
    }

    @Test
    fun `外推少於 1 美元唔觸發`() {
        // 基線好細（0.01），外推 0.1 ≥ 0.03 倍數滿足但 < $1 → 唔觸發
        val daily = listOf(0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.1)
        val r = AlertEngine.evaluate(input(todaySpendUsd = 0.05, daily7dUsd = daily))
        assertTrue(r.events.none { it.type == AlertType.SPIKE })
    }

    /* ---------------- TOKEN_BANNED ---------------- */

    @Test
    fun `新封禁令牌觸發 - 再評估同一批唔重觸`() {
        val r = AlertEngine.evaluate(input(banned = setOf(7L, 9L)))
        assertEquals(1, r.events.size)
        assertEquals(AlertType.TOKEN_BANNED, r.events[0].type)
        assertEquals(setOf(7L, 9L), r.bannedTokenIds)

        val r2 = AlertEngine.evaluate(input(banned = setOf(7L, 9L), prevBanned = r.bannedTokenIds))
        assertTrue(r2.events.none { it.type == AlertType.TOKEN_BANNED })
    }

    @Test
    fun `只報新增嘅封禁令牌`() {
        val r = AlertEngine.evaluate(input(banned = setOf(7L, 9L), prevBanned = setOf(7L)))
        assertEquals(1, r.events.size)
        assertTrue(r.events[0].body.contains("#9"))
        assertFalse(r.events[0].body.contains("#7"))
    }

    @Test
    fun `封禁通知尊重 cooldown`() {
        val lastFired = mapOf(AlertType.TOKEN_BANNED to nowMs - 10 * 60_000L)
        val r = AlertEngine.evaluate(input(banned = setOf(7L), lastFiredMs = lastFired))
        assertTrue(r.events.isEmpty())
        // 狀態照記，冷卻過後唔會重響舊嗰批
        assertEquals(setOf(7L), r.bannedTokenIds)
    }

    @Test fun `monthly budget fires once per threshold per local month and ignores missing data`() {
        val s = settings.copy(balanceBelowUsd = null, dailySpendAboveUsd = null, spikeMultiplier = 0.0,
            notifyTokenBanned = false, monthlyBudgetUsd = 100.0)
        fun check(amount: Double?, fired: Map<AlertType, Long> = emptyMap(), time: Long = nowMs) =
            AlertEngine.evaluate(input(settings = s, lastFiredMs = fired, nowMs = time).copy(
                current = AlertEngine.SnapshotSummary(100.0, null, amount)))
        assertTrue(check(null).events.isEmpty())
        assertTrue(check(89.99).events.isEmpty())
        val ninety = check(90.0)
        assertEquals(listOf(AlertType.MONTHLY_BUDGET_90), ninety.events.map { it.type })
        val full = check(100.0, ninety.lastFiredMs)
        assertEquals(listOf(AlertType.MONTHLY_BUDGET_100), full.events.map { it.type })
        assertTrue(check(120.0, full.lastFiredMs).events.isEmpty())
        assertEquals(2, check(120.0, full.lastFiredMs, nowMs + 32L * 86_400_000).events.size)
    }

    @Test fun `error rate requires three errors and ten percent and honors cooldown`() {
        val s = settings.copy(balanceBelowUsd = null, dailySpendAboveUsd = null, spikeMultiplier = 0.0,
            notifyErrorRate = true)
        fun check(errors: Int?, requests: Int?, fired: Map<AlertType, Long> = emptyMap()) =
            AlertEngine.evaluate(input(settings = s, lastFiredMs = fired).copy(
                current = AlertEngine.SnapshotSummary(100.0, null, errorCount = errors, requestCount = requests)))
        assertTrue(check(null, null).events.isEmpty())
        assertTrue(check(2, 10).events.isEmpty())
        assertTrue(check(3, 31).events.isEmpty())
        val r = check(3, 30)
        assertEquals(AlertType.ERROR_RATE, r.events.single().type)
        assertTrue(check(4, 30, r.lastFiredMs).events.isEmpty())
    }

    @Test fun `token expiry phases advance once per token expiry and failures preserve state`() {
        val s = settings.copy(balanceBelowUsd = null, dailySpendAboveUsd = null, spikeMultiplier = 0.0,
            notifyTokenExpiry = true)
        val expiry = (nowMs + 2L * 86_400_000) / 1000
        fun check(time: Long, phases: Map<String, Int> = emptyMap(), tokens: Map<Long, Long>? = mapOf(7L to expiry)) =
            AlertEngine.evaluate(input(settings = s, nowMs = time).copy(
                expiringTokens = tokens, prevTokenExpiryPhases = phases))
        val first = check(nowMs)
        assertEquals(AlertType.TOKEN_EXPIRY_3D, first.events.single().type)
        assertTrue(check(nowMs, first.tokenExpiryPhases).events.isEmpty())
        val failure = check(nowMs + 86_400_000, first.tokenExpiryPhases, null)
        assertEquals(first.tokenExpiryPhases, failure.tokenExpiryPhases)
        val day = check(nowMs + 86_400_000 + 1000, first.tokenExpiryPhases)
        assertEquals(AlertType.TOKEN_EXPIRY_1D, day.events.single().type)
        val expired = check(expiry * 1000, day.tokenExpiryPhases)
        assertEquals(AlertType.TOKEN_EXPIRED, expired.events.single().type)
        assertTrue(check(expiry * 1000 + 1000, expired.tokenExpiryPhases).events.isEmpty())
    }

    @Test fun `new alert bodies use localized interpolation in four languages`() {
        val args = listOf("Example", "#7")
        assertTrue(alertMessage("alert.expiry1", args, "yue").contains("不足 1 日"))
        assertTrue(alertMessage("alert.expiry1", args, "zh-TW").contains("權杖"))
        assertTrue(alertMessage("alert.expiry1", args, "zh-CN").contains("不足 1 天"))
        assertTrue(alertMessage("alert.expiry1", args, "en").contains("under 1 day"))
        for (lang in listOf("yue", "zh-TW", "zh-CN", "en")) {
            for (key in listOf("alert.month90", "alert.month100", "alert.errorRate", "alert.expiry3", "alert.expiry1", "alert.expired")) {
                val values = if (key.startsWith("alert.month")) listOf("Example", "USD 90", "USD 100", "90")
                    else if (key == "alert.errorRate") listOf("Example", "10", "3", "30") else args
                val text = alertMessage(key, values, lang)
                assertTrue(text.contains("Example"))
                assertFalse(text.contains("%s"))
            }
        }
    }

    /* ---------------- 總開關／狀態 ---------------- */

    @Test
    fun `settings 關閉時零事件`() {
        val r = AlertEngine.evaluate(input(balanceUsd = 1.0, todaySpendUsd = 99.0, settings = settings.copy(enabled = false)))
        assertTrue(r.events.isEmpty())
        assertTrue(r.lastFiredMs.isEmpty())
    }

    @Test
    fun `lastFiredMs 只更新有觸發嘅類型`() {
        val last = mapOf(AlertType.BALANCE_LOW to nowMs - 9 * 3_600_000L) // 好耐之前，可再觸發
        val r = AlertEngine.evaluate(input(balanceUsd = 8.0, todaySpendUsd = 1.0, lastFiredMs = last))
        assertEquals(1, r.events.size)
        assertEquals(nowMs, r.lastFiredMs[AlertType.BALANCE_LOW])
        assertEquals(null, r.lastFiredMs[AlertType.DAILY_SPEND])
    }

    @Test
    fun `cooldownMinutes 0 表示唔設冷卻`() {
        val s = settings.copy(cooldownMinutes = 0)
        val lastFired = mapOf(AlertType.BALANCE_LOW to nowMs - 1000L)
        val r = AlertEngine.evaluate(input(balanceUsd = 5.0, lastFiredMs = lastFired, settings = s))
        assertEquals(1, r.events.size)
    }
}
