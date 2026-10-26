package com.newapimonitor.data.backup

import com.newapimonitor.data.store.Appearance
import com.newapimonitor.data.monitor.InstanceAlertRule
import com.newapimonitor.data.monitor.MonitorPreferences
import com.newapimonitor.data.monitor.ProbeSuite
import com.newapimonitor.domain.AlertSettings
import com.newapimonitor.domain.AuthStyle
import com.newapimonitor.domain.Instance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本機備份（BackupManager）：roundtrip、垃圾輸入拒絕、空白 baseUrl 跳過、未知欄位容忍 */
class BackupManagerTest {

    @Test
    fun `home choices probe suites and individual alert rule roundtrip without credentials`() {
        val source = Instance(id = "one", name = "Main", baseUrl = "https://example.test", accessToken = "secret", apiKey = "sk-secret")
        val options = MonitorPreferences(compact = true, hideMoney = true, pinned = setOf("one"), order = listOf("one"),
            excluded = setOf("one"), favorites = setOf("model-a"), suites = listOf(ProbeSuite("Common", listOf("model-a"))))
        val rule = InstanceAlertRule(enabled = true, balanceBelow = 2.0, quietStart = "22:00", quietEnd = "07:00")
        val payload = BackupManager.toJson(listOf(source), null, null, homeOptions = options, alertRules = mapOf("one" to rule))
        assertTrue(!payload.contains("sk-secret") && !payload.contains("secret"))
        val parsed = BackupManager.parse(payload)!!
        assertEquals(options.compact, parsed.homeOptions?.compact)
        assertEquals(options.favorites, parsed.homeOptions?.favorites)
        assertEquals(options.suites, parsed.homeOptions?.suites)
        assertEquals(true, parsed.instanceOptions.single().pinned)
        assertEquals(false, parsed.instanceOptions.single().balanceIncluded)
        assertEquals(rule, parsed.instanceOptions.single().alertRule)
    }

    @Test
    fun `roundtrip - instances appearance alertSettings 全保留`() {
        val instances = listOf(
            Instance(
                id = "i1",
                name = "Main",
                baseUrl = "https://a.example.com/",
                accessToken = "tok-1",
                apiKey = "sk-1",
                quotaPerUnit = 500000.0,
                authStyle = AuthStyle.RAW,
                authStyleKey = AuthStyle.RAW,
                createdAt = 123L,
            ),
            Instance(id = "i2", name = "Second", baseUrl = "https://b.example.com", createdAt = 456L),
        )
        val appearance = Appearance(language = "zh-TW", mode = "light", accent = "blue")
        val alerts = AlertSettings(
            enabled = true,
            balanceBelowUsd = 5.0,
            dailySpendAboveUsd = 10.0,
            spikeMultiplier = 2.0,
            cooldownMinutes = 30,
            notifyTokenBanned = false,
            monthlyBudgetUsd = 100.0,
            notifyErrorRate = true,
            notifyTokenExpiry = true,
        )

        val payload = BackupManager.toJson(instances, appearance, alerts, exportedAtMs = 1700000000000L, includeCredentials = true)
        val parsed = BackupManager.parse(payload)!!

        assertEquals(BackupManager.VERSION, parsed.version)
        assertEquals(1700000000000L, parsed.exportedAt)
        assertEquals(2, parsed.instances.size)

        // 實例欄位全保留；草稿 id 空、createdAt 0（交 store 落地時先派）
        val d0 = parsed.instances[0]
        assertEquals("", d0.id)
        assertEquals("Main", d0.name)
        assertEquals("https://a.example.com", d0.baseUrl) // sanitizeBaseUrl 去尾斜杠
        assertEquals("tok-1", d0.accessToken)
        assertEquals("sk-1", d0.apiKey)
        assertEquals(500000.0, d0.quotaPerUnit!!, 1e-9)
        assertEquals(AuthStyle.RAW, d0.authStyle)
        assertEquals(AuthStyle.RAW, d0.authStyleKey)
        assertEquals(0L, d0.createdAt)

        val d1 = parsed.instances[1]
        assertEquals("Second", d1.name)
        assertEquals("https://b.example.com", d1.baseUrl)
        assertEquals("", d1.accessToken)
        assertNull(d1.quotaPerUnit)
        assertNull(d1.authStyle)
        assertNull(d1.authStyleKey)

        assertEquals("zh-TW", parsed.appearance?.language)
        assertEquals("light", parsed.appearance?.mode)
        assertEquals("blue", parsed.appearance?.accent)

        assertEquals(alerts, parsed.alertSettings)

        // 檔案形狀：version 1 + 只有 RAW 先寫 "raw"
        assertTrue(payload.contains("\"version\":1"))
        assertTrue(payload.contains("\"authStyle\":\"raw\""))
    }

    @Test
    fun `parse - 垃圾輸入拒絕返回 null`() {
        assertNull(BackupManager.parse("this is not json"))
        assertNull(BackupManager.parse(""))
        assertNull(BackupManager.parse("[1,2,3]"))
        assertNull(BackupManager.parse("{\"instances\":\"not-an-array\"}"))
        assertNull(BackupManager.parse("<html>404</html>"))
    }

    @Test
    fun `parse - baseUrl 空白或缺失嘅實例條目跳過`() {
        val src = """
            {
              "version": 1,
              "exportedAt": 1,
              "instances": [
                {"name": "blank url", "baseUrl": "   "},
                {"name": "ok", "baseUrl": "https://ok.example.com"},
                {"name": "missing url"}
              ]
            }
        """.trimIndent()
        val parsed = BackupManager.parse(src)!!
        assertEquals(1, parsed.instances.size)
        assertEquals("ok", parsed.instances[0].name)
        assertEquals("https://ok.example.com", parsed.instances[0].baseUrl)
    }

    @Test
    fun `parse - 未知欄位容忍`() {
        val src = """
            {
              "version": 1,
              "exportedAt": 9,
              "futureTopLevel": {"x": 1},
              "instances": [
                {"name": "x", "baseUrl": "https://x.example.com", "futureField": "y", "createdAt": 123}
              ],
              "appearance": {"language": "en", "theme": "??"},
              "alertSettings": {"enabled": true, "newThing": 7}
            }
        """.trimIndent()
        val parsed = BackupManager.parse(src)!!
        assertEquals(1, parsed.instances.size)
        assertEquals("https://x.example.com", parsed.instances[0].baseUrl)
        // 備份檔唔帶 id — 匯入永遠當草稿，唔會撞現有實例 id
        assertEquals("", parsed.instances[0].id)
        assertEquals("en", parsed.appearance?.language)
        assertNull(parsed.appearance?.mode)
        assertTrue(parsed.alertSettings!!.enabled)
    }

    @Test
    fun `parse - 檔案缺 appearance alertSettings 返回 null 區塊`() {
        val src = """{"version":1,"exportedAt":2,"instances":[]}"""
        val parsed = BackupManager.parse(src)!!
        assertEquals(0, parsed.instances.size)
        assertNull(parsed.appearance)
        assertNull(parsed.alertSettings)
    }
}
