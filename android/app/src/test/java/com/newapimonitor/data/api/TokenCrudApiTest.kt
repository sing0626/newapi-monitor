package com.newapimonitor.data.api

import com.newapimonitor.domain.ChannelInfo
import com.newapimonitor.domain.ErrorKind
import com.newapimonitor.domain.TokenInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 渠道列表＋令牌 CRUD（MockWebServer）：
 *  - GET /api/channel/?p=1&size=100 防禦式解析（id/name/status/type/response_time）
 *  - POST /api/token/ 新增、DELETE /api/token/{id} 刪除、PUT 全 token object 切換狀態
 *  - 401 fallback 與信封拆解沿用現有核心
 */
class TokenCrudApiTest {

    private lateinit var server: MockWebServer
    private lateinit var client: NewApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = NewApiClient(
            baseUrl = server.url("/").toString(),
            accessToken = "tok",
            apiKey = "",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun ok(body: String): MockResponse =
        MockResponse().setResponseCode(200).setBody(body)

    private fun bodyJson(raw: String): JsonObject =
        Json.parseToJsonElement(raw) as JsonObject

    private fun JsonPrimitive.longVal(): Long = longOrNull ?: error("expected long: $content")
    private fun JsonPrimitive.boolVal(): Boolean = booleanOrNull ?: error("expected boolean: $content")

    /* ---------------- 渠道 ---------------- */

    @Test
    fun `fetchChannels 打 p=1 size=100 並解析欄位`() = runBlocking {
        server.enqueue(
            ok(
                """{"success":true,"data":[{"id":1,"name":"openai-primary","status":1,"type":1,"response_time":123}, """.trimIndent() +
                    """{"id":2,"name":"封禁站","status":3,"type":14,"response_time":4500.0}]}"""
            )
        )
        val data = client.fetchChannels(p = 1, size = 100)
        val channels = ResponseParsing.pickChannelItems(data).mapNotNull { ResponseParsing.parseChannel(it) }
        assertEquals(2, channels.size)
        assertEquals(
            ChannelInfo(id = 1, name = "openai-primary", status = 1, type = 1, responseTime = 123),
            channels[0],
        )
        assertEquals(3, channels[1].status)
        assertEquals(4500, channels[1].responseTime)
        assertEquals("自動封禁", channels[1].statusLabel)
        val req = server.takeRequest()
        val path = req.path ?: ""
        assertTrue(path.startsWith("/api/channel/?"))
        assertTrue(path.contains("p=1") && path.contains("size=100"))
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertEquals("GET", req.method)
    }

    @Test
    fun `渠道解析防禦式 - id 缺失跳過 response_time 缺失為 null`() {
        // 解析器收 data 元素（client getJson 已拆 {success,message,data} 信封），呢度照實際呼叫形狀拆好先傳入
        val data = (Json.parseToJsonElement(
            """{"success":true,"data":[
               {"id":1,"name":"a","status":"1"},
               {"id":2,"name":"b"},
               {"name":"no-id"}
               ]}"""
        ) as JsonObject)["data"]!!
        val channels = ResponseParsing.pickChannelItems(data).mapNotNull { ResponseParsing.parseChannel(it) }
        assertEquals(2, channels.size)
        // status 係字串形數字 → 當 0（同 quota 語義一致，唔當數值）
        assertEquals(0, channels[0].status)
        assertNull(channels[1].responseTime)
        assertEquals("b", channels[1].name)
    }

    @Test
    fun `pickChannelItems 容忍 items 與 records 容器`() {
    fun arr(container: String): JsonElement =
        ((Json.parseToJsonElement(
            """{"success":true,"data":{"$container":[{"id":1,"name":"x"}]}}"""
        ) as JsonObject)["data"])!!
        assertEquals(1, ResponseParsing.pickChannelItems(arr("items")).size)
        assertEquals(1, ResponseParsing.pickChannelItems(arr("records")).size)
        assertEquals(1, ResponseParsing.pickChannelItems(arr("channels")).size)
    }

    @Test
    fun `渠道狀態使用專用 POST 端點而非覆寫配置`() = runBlocking {
        server.enqueue(ok("""{"success":true,"data":true}"""))
        val channel = ChannelInfo(id = 7, name = "primary", status = 1, type = 14, priority = 9, weight = 80)
        val result = client.updateChannelStatus(channel, 2)
        assertEquals(true, result.jsonPrimitive.booleanOrNull)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/channel/7/status", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertEquals(setOf("status"), bodyJson(req.body.readUtf8()).keys)
    }

    @Test
    fun `渠道狀態拒絕自動封禁與無效 id 而不發請求`() = runBlocking {
        for ((channel, status) in listOf(ChannelInfo(id = 7) to 3, ChannelInfo(id = 0) to 2)) {
            try {
                client.updateChannelStatus(channel, status)
                throw AssertionError("應拒絕無效狀態或 id")
            } catch (_: IllegalArgumentException) {
                // 只容許手動 1/2；無效 id 不可觸發管理 API。
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `渠道優先級權重可解析但不會混入狀態請求`() {
        val row = bodyJson("""{"id":7,"name":"x","priority":-10,"weight":0,"status":1}""")
        val channel = ResponseParsing.parseChannel(row)!!
        assertEquals(-10L, channel.priority)
        assertEquals(0L, channel.weight)
        val sparse = ResponseParsing.parseChannel(bodyJson("""{"id":8,"priority":"5","weight":null}"""))!!
        assertNull(sparse.priority)
        assertNull(sparse.weight)
    }

    @Test
    fun `渠道管理 403 明確顯示權限不足且不重試`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
        try {
            client.fetchChannels()
            throw AssertionError("應拋出管理權限錯誤")
        } catch (e: ApiException) {
            assertEquals(ErrorKind.AUTH, e.kind)
            assertEquals(403, e.status)
            assertTrue(e.message.contains("管理員權限"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `舊站點無專用狀態路由時不嘗試危險的縮水 PUT`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        try {
            client.updateChannelStatus(ChannelInfo(id = 7), 2)
            throw AssertionError("應向上傳播端點不存在錯誤")
        } catch (e: ApiException) {
            assertEquals(404, e.status)
        }
        assertEquals(1, server.requestCount)
        assertEquals("POST", server.takeRequest().method)
    }

    @Test
    fun `渠道狀態更新 403 權限不足不回退到 PUT`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
        try {
            client.updateChannelStatus(ChannelInfo(id = 7), 2)
            throw AssertionError("應拋出管理權限錯誤")
        } catch (e: ApiException) {
            assertEquals(ErrorKind.AUTH, e.kind)
            assertEquals(403, e.status)
        }
        assertEquals(1, server.requestCount)
        assertEquals("POST", server.takeRequest().method)
    }

    @Test
    fun `渠道狀態 401 改用 raw header 重試並保留請求 body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"unauthorized"}"""))
        server.enqueue(ok("""{"success":true,"data":true}"""))
        client.updateChannelStatus(ChannelInfo(id = 7), 1)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("Bearer tok", first.getHeader("Authorization"))
        assertEquals("tok", second.getHeader("Authorization"))
        assertEquals("/api/channel/7/status", second.path)
        assertEquals(first.body.readUtf8(), second.body.readUtf8())
    }

    @Test
    fun `pingModel 取消時保留取消訊號而非回報測速失敗`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pingClient = NewApiClient(server.url("/").toString(), accessToken = "", apiKey = "sk-key")
        try {
            withTimeout(500) { pingClient.pingModel("model") }
            fail("取消必須向上傳播")
        } catch (_: CancellationException) {
            assertEquals("POST", server.takeRequest(2, TimeUnit.SECONDS)?.method)
        }
    }

    /* ---------------- 令牌 CRUD ---------------- */

    @Test
    fun `createToken 用 POST 並送齊欄位 JSON body`() = runBlocking {
        server.enqueue(ok("""{"success":true,"message":"","data":null}"""))
        client.createToken(name = "我的令牌", remainQuota = 500000L, unlimited = false, expiredTime = -1L)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/token/", req.path)
        assertEquals("application/json; charset=utf-8", req.getHeader("Content-Type"))
        val body = bodyJson(req.body.readUtf8())
        assertEquals("我的令牌", body["name"]?.jsonPrimitive?.content)
        assertEquals(500000L, body["remain_quota"]?.jsonPrimitive?.longVal())
        assertEquals(false, body["unlimited_quota"]?.jsonPrimitive?.boolVal())
        assertEquals(-1L, body["expired_time"]?.jsonPrimitive?.longVal())
        assertEquals(false, body["model_limits_enabled"]?.jsonPrimitive?.boolVal())
        assertEquals("", body["model_limits"]?.jsonPrimitive?.content)
        assertEquals("", body["allow_ips"]?.jsonPrimitive?.content)
        assertEquals("", body["group"]?.jsonPrimitive?.content)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
    }

    @Test
    fun `deleteToken 用 DELETE 打 token id 路徑`() = runBlocking {
        server.enqueue(ok("""{"success":true,"message":"","data":null}"""))
        client.deleteToken(id = 42L)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/token/42", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
    }

    @Test
    fun `full detail overlay preserves restrictions and unknown fields`() = runBlocking {
        server.enqueue(ok("""{"success":true,"data":{"id":9,"name":"prod-app","key":"sk-realSecret","status":1,"used_quota":123,"remain_quota":456,"unlimited_quota":true,"expired_time":-1,"created_time":777,"group":"premium","model_limits_enabled":true,"model_limits":"gpt-4","allow_ips":"127.0.0.1","future_field":{"x":7}}}"""))
        server.enqueue(ok("""{"success":true,"data":null}"""))
        client.updateToken(9L, mapOf("status" to JsonPrimitive(2)))
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals("/api/token/9", get.path)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        val body = bodyJson(put.body.readUtf8())
        assertEquals("sk-realSecret", body["key"]?.jsonPrimitive?.content)
        assertEquals(2L, body["status"]?.jsonPrimitive?.longVal())
        assertEquals("premium", body["group"]?.jsonPrimitive?.content)
        assertEquals("gpt-4", body["model_limits"]?.jsonPrimitive?.content)
        assertEquals("127.0.0.1", body["allow_ips"]?.jsonPrimitive?.content)
        assertEquals(bodyJson("""{"x":7}"""), body["future_field"])
    }

    @Test
    fun `masked detail or missing restrictions never PUT`() = runBlocking {
        server.enqueue(ok("""{"success":true,"data":{"id":9,"key":"sk-****"}}"""))
        try {
            client.updateToken(9L, mapOf("name" to JsonPrimitive("other")))
            fail("expected refusal")
        } catch (e: ApiException) { assertEquals(ErrorKind.PARSE, e.kind) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `reveal only uses explicit POST and does not retry 401`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"denied"}"""))
        try {
            client.revealTokenKey(9)
            fail("expected denial")
        } catch (e: ApiException) { assertEquals(401, e.status) }
        assertEquals(1, server.requestCount)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/token/9/key", req.path)
    }

    @Test
    fun `reveal rejects masked key and propagates 403 404`() = runBlocking {
        server.enqueue(ok("""{"success":true,"data":{"key":"sk-****"}}"""))
        try { client.revealTokenKey(9); fail("masked key") }
        catch (e: ApiException) { assertEquals(ErrorKind.PARSE, e.kind) }
        for (code in listOf(403, 404)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("forbidden"))
            try { client.revealTokenKey(9); fail("denied") }
            catch (e: ApiException) { assertEquals(code, e.status) }
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `deleteToken success=false 信封抛 API 錯誤`() = runBlocking {
        server.enqueue(ok("""{"success":false,"message":"令牌不存在"}"""))
        try {
            client.deleteToken(id = 1L)
            throw AssertionError("應該抛 ApiException")
        } catch (e: ApiException) {
            assertEquals("令牌不存在", e.message)
        }
    }
}
