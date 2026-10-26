package com.newapimonitor.data.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** new-api JSON 解析：容器形狀防禦、日誌欄位寬鬆解析、total 計數 */
class LogJsonParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    /* ---------- pickLogItems：items / records / logs / 裸數組 ---------- */

    @Test
    fun `裸數組容器`() {
        val data = json.parseToJsonElement("""[{"model_name":"gpt-4o","quota":1}]""")
        assertEquals(1, ResponseParsing.pickLogItems(data).size)
    }

    @Test
    fun `items 容器`() {
        val data = json.parseToJsonElement("""{"items":[{"model_name":"a"},{"model_name":"b"}]}""")
        assertEquals(2, ResponseParsing.pickLogItems(data).size)
    }

    @Test
    fun `records 容器`() {
        val data = json.parseToJsonElement("""{"records":[{"model_name":"a"}]}""")
        assertEquals(1, ResponseParsing.pickLogItems(data).size)
    }

    @Test
    fun `logs 容器`() {
        val data = json.parseToJsonElement("""{"logs":[{"model_name":"a"}]}""")
        assertEquals(1, ResponseParsing.pickLogItems(data).size)
    }

    @Test
    fun `未知形狀返回空`() {
        assertTrue(ResponseParsing.pickLogItems(null).isEmpty())
        assertTrue(ResponseParsing.pickLogItems(json.parseToJsonElement("""{"foo":1}""")).isEmpty())
    }

    /* ---------- parseLogEntry：寬鬆欄位解析 ---------- */

    @Test
    fun `完整記錄解析`() {
        val row = json.parseToJsonElement(
            """{"id":1,"type":2,"created_at":1700000000,"model_name":"gpt-4o","token_name":"ide",
                "prompt_tokens":100,"completion_tokens":50,"quota":2500,"use_time":1.5,
                "is_stream":true,"username":"u"}"""
        ) as kotlinx.serialization.json.JsonObject
        val e = ResponseParsing.parseLogEntry(row)
        assertEquals(2, e.type)
        assertEquals(1700000000L, e.createdAt)
        assertEquals("gpt-4o", e.modelName)
        assertEquals("ide", e.tokenName)
        assertEquals(100L, e.promptTokens)
        assertEquals(50L, e.completionTokens)
        assertEquals(2500.0, e.quota!!, 1e-9)
        assertEquals(1.5, e.useTime, 1e-9)
        assertTrue(e.isStream)
        assertEquals("u", e.username)
    }

    @Test
    fun `缺欄位寬鬆解析唔會炸`() {
        val row = json.parseToJsonElement("""{"model_name":"m"}""") as kotlinx.serialization.json.JsonObject
        val e = ResponseParsing.parseLogEntry(row)
        assertNull(e.type)
        assertEquals(0L, e.createdAt)
        assertNull(e.quota)
        assertFalse(e.isStream)
    }

    @Test
    fun `is_stream 數字 1 當 true`() {
        val row = json.parseToJsonElement("""{"model_name":"m","is_stream":1}""") as kotlinx.serialization.json.JsonObject
        assertTrue(ResponseParsing.parseLogEntry(row).isStream)
    }

    @Test
    fun `quota 係字串時當缺失`() {
        val row = json.parseToJsonElement("""{"model_name":"m","quota":"100"}""") as kotlinx.serialization.json.JsonObject
        assertNull(ResponseParsing.parseLogEntry(row).quota)
    }

    /* ---------- parseLogTotal ---------- */

    @Test
    fun `total 直讀`() {
        val data = json.parseToJsonElement("""{"items":[],"total":123}""")
        assertEquals(123L, ResponseParsing.parseLogTotal(data))
    }

    @Test
    fun `page_info total`() {
        val data = json.parseToJsonElement("""{"items":[],"page_info":{"total":456}}""")
        assertEquals(456L, ResponseParsing.parseLogTotal(data))
    }

    @Test
    fun `total 0 或缺失返回 null`() {
        assertNull(ResponseParsing.parseLogTotal(json.parseToJsonElement("""{"items":[],"total":0}""")))
        assertNull(ResponseParsing.parseLogTotal(json.parseToJsonElement("""{"items":[]}""")))
        assertNull(ResponseParsing.parseLogTotal(null))
    }

    /* ---------- JsonObject 取值工具 ---------- */

    @Test
    fun `str long double bool 取值`() {
        val o = json.parseToJsonElement(
            """{"s":"abc","n":42,"d":1.5,"b":true,"sn":"42","bn":"true"}"""
        ) as kotlinx.serialization.json.JsonObject
        assertEquals("abc", o.str("s"))
        assertEquals(42L, o.longOf("n"))
        assertEquals(1.5, o.doubleOf("d")!!, 1e-9)
        assertTrue(o.boolOf("b"))
        // 字串形數字唔當數值（js typeof 嚴格語義）
        assertNull(o.longOf("sn"))
        assertFalse(o.boolOf("bn"))
        assertNull(o.str("missing"))
    }
}
