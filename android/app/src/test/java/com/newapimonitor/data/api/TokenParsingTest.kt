package com.newapimonitor.data.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** /api/token/ 回應解析：容器形狀防禦、欄位寬鬆解析、缺 id 唔炸 */
class TokenParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun obj(s: String) = json.parseToJsonElement(s) as kotlinx.serialization.json.JsonObject

    /* ---------- pickTokenItems：裸數組 / items / records / 未知 ---------- */

    @Test
    fun `裸數組容器`() {
        val data = json.parseToJsonElement("""[{"id":1,"name":"a"},{"id":2,"name":"b"}]""")
        assertEquals(2, ResponseParsing.pickTokenItems(data).size)
    }

    @Test
    fun `items 與 records 容器`() {
        assertEquals(1, ResponseParsing.pickTokenItems(json.parseToJsonElement("""{"items":[{"id":1}]}""")).size)
        assertEquals(1, ResponseParsing.pickTokenItems(json.parseToJsonElement("""{"records":[{"id":1}]}""")).size)
    }

    @Test
    fun `未知形狀與 null 返回空`() {
        assertTrue(ResponseParsing.pickTokenItems(null).isEmpty())
        assertTrue(ResponseParsing.pickTokenItems(json.parseToJsonElement("""{"foo":1}""")).isEmpty())
        assertTrue(ResponseParsing.pickTokenItems(json.parseToJsonElement("""{}""")).isEmpty())
    }

    /* ---------- parseToken：寬鬆欄位解析 ---------- */

    @Test
    fun `完整令牌解析`() {
        val t = ResponseParsing.parseToken(
            obj(
                """{"id":42,"user_id":1,"name":"ide-chat","key":"abc123","status":1,
                    "used_quota":250000,"remain_quota":4750000,"unlimited_quota":false,
                    "expired_time":-1,"created_time":1700000000,"accessed_time":1700000500}"""
            )
        )!!
        assertEquals(42L, t.id)
        assertEquals("ide-chat", t.name)
        assertEquals("", t.key) // List rows are never a source of revealable secret; always empty until explicit reveal
        assertEquals(1, t.status)
        assertEquals(250000.0, t.usedQuota, 1e-9)
        assertEquals(4750000.0, t.remainQuota, 1e-9)
        assertEquals(false, t.unlimitedQuota)
        assertEquals(-1L, t.expiredTime)
        assertEquals(1700000000L, t.createdTime)
    }

    @Test
    fun `缺欄位寬鬆解析唔會炸`() {
        val t = ResponseParsing.parseToken(obj("""{"id":7,"name":"x"}"""))!!
        assertEquals("", t.key)
        assertEquals(0, t.status)
        assertEquals(0.0, t.usedQuota, 1e-9)
        assertEquals(false, t.unlimitedQuota)
        assertEquals(-1L, t.expiredTime) // 上游慣例：唔帶 expired_time 當永不過期
        assertEquals(0L, t.createdTime)
    }

    @Test
    fun `id 缺失返回 null - 無 id 冇得切換狀態`() {
        assertNull(ResponseParsing.parseToken(obj("""{"name":"no-id"}""")))
    }

    @Test
    fun `字串形數字唔當數值 - 同日誌 quota 語義一致`() {
        val t = ResponseParsing.parseToken(obj("""{"id":1,"status":"1","used_quota":"100"}"""))!!
        assertEquals(0, t.status)
        assertEquals(0.0, t.usedQuota, 1e-9)
    }

    @Test
    fun `unlimited_quota 數字 1 當 true`() {
        val t = ResponseParsing.parseToken(obj("""{"id":1,"unlimited_quota":1}"""))!!
        assertTrue(t.unlimitedQuota)
        assertNull(t.remainUsd(500000.0)) // 無限制 → 剩餘 USD 唔計數
        assertEquals(0.0, t.usedUsd(500000.0), 1e-9)
    }

    /* ---------- USD 換算（同儀表板同一個 ÷qpu） ---------- */

    @Test
    fun `quota 換算 USD`() {
        val t = ResponseParsing.parseToken(obj("""{"id":1,"used_quota":250000,"remain_quota":500000}"""))!!
        assertEquals(0.5, t.usedUsd(500000.0), 1e-9)
        assertEquals(1.0, t.remainUsd(500000.0)!!, 1e-9)
    }
}
