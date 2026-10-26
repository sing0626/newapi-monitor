package com.newapimonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 實例清洗（store.js sanitizeInstance 移植） */
class InstanceSanitizerTest {

    @Test
    fun `baseUrl 去尾斜杠同截長`() {
        val s = InstanceSanitizer.sanitize(Instance(id = "i1", baseUrl = "https://a.example.com///"))
        assertEquals("https://a.example.com", s!!.baseUrl)
    }

    @Test
    fun `表單輸入自動補 https 前綴`() {
        assertEquals("https://a.example.com", InstanceSanitizer.normalizeBaseUrlInput("a.example.com"))
        assertEquals("http://1.2.3.4:3000", InstanceSanitizer.normalizeBaseUrlInput("http://1.2.3.4:3000"))
        assertEquals("", InstanceSanitizer.normalizeBaseUrlInput("  "))
    }

    @Test
    fun `空名補未命名實例`() {
        val s = InstanceSanitizer.sanitize(Instance(id = "i1", name = "   "))
        assertEquals("未命名實例", s!!.name)
    }

    @Test
    fun `無 id 拒絕`() {
        assertNull(InstanceSanitizer.sanitize(Instance(id = "")))
        assertNull(InstanceSanitizer.sanitize(null))
    }

    @Test
    fun `quotaPerUnit 非法值歸 null`() {
        assertNull(InstanceSanitizer.sanitize(Instance(id = "i", quotaPerUnit = -5.0))!!.quotaPerUnit)
        assertNull(InstanceSanitizer.sanitize(Instance(id = "i", quotaPerUnit = 0.0))!!.quotaPerUnit)
        assertEquals(600000.0, InstanceSanitizer.sanitize(Instance(id = "i", quotaPerUnit = 600000.0))!!.quotaPerUnit!!, 1e-9)
    }

    @Test
    fun `只有 raw 格式會持久化`() {
        val s = InstanceSanitizer.sanitize(Instance(id = "i", authStyle = AuthStyle.BEARER, authStyleKey = AuthStyle.RAW))
        assertNull(s!!.authStyle)
        assertEquals(AuthStyle.RAW, s.authStyleKey)
    }

    @Test
    fun `token 前後空白被清理`() {
        val s = InstanceSanitizer.sanitize(Instance(id = "i", accessToken = "  abc  ", apiKey = " sk-1 "))
        assertEquals("abc", s!!.accessToken)
        assertEquals("sk-1", s.apiKey)
    }
}
