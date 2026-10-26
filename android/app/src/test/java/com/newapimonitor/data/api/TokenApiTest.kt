package com.newapimonitor.data.api

import com.newapimonitor.domain.ErrorKind
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 令牌端點行為（MockWebServer）：
 *  - GET /api/token/?p=1&size=100 帶 Access Token Bearer header
 *  - PUT /api/token/ body {"id":..,"status":..}（啟用/停用切換）
 *  - success=false 信封照樣擲 API 錯誤
 */
class TokenApiTest {

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

    @Test
    fun `fetchTokens 打 p=1 size=100 並帶 Bearer`() = runBlocking {
        server.enqueue(ok("""{"success":true,"data":[{"id":1,"name":"a","status":1}]}"""))
        val page = client.fetchTokens(p = 1, size = 100)
        val tokens = ResponseParsing.pickTokenItems(page).mapNotNull { ResponseParsing.parseToken(it) }
        assertEquals(1, tokens.size)
        assertEquals("a", tokens[0].name)
        val req = server.takeRequest()
        val path = req.path ?: ""
        assertTrue(path.startsWith("/api/token/?"))
        assertTrue(path.contains("p=1"))
        assertTrue(path.contains("size=100"))
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertEquals("GET", req.method)
    }

    @Test
    fun `status update refuses incomplete detail without PUT`() = runBlocking {
        server.enqueue(ok("""{"success":true,"data":{"id":42,"name":"a","key":"sk-****"}}"""))
        try {
            client.updateToken(42L, mapOf("status" to kotlinx.serialization.json.JsonPrimitive(2)))
            throw AssertionError("Expected refusal")
        } catch (e: ApiException) {
            assertEquals(ErrorKind.PARSE, e.kind)
        }
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `fetchDataSelf 帶看板窗口參數`() = runBlocking {
        server.enqueue(ok("""{"success":true,"data":[{"created_at":1700000000,"model_name":"gpt-4o","count":3,"quota":1500,"token_used":900}]}"""))
        val d = client.fetchDataSelf(startTs = 1699900000L, endTs = 1700000000L)
        val points = ResponseParsing.pickLogItems(d)
            .mapNotNull { ResponseParsing.parseUsagePoint(it) }
        assertEquals(1, points.size)
        assertEquals(1700000000L, points[0].tsSec)
        assertEquals(1500.0, points[0].quota, 1e-9)
        val path = server.takeRequest().path ?: ""
        assertTrue(path.startsWith("/api/data/self?"))
        assertTrue(path.contains("start_timestamp=1699900000"))
        assertTrue(path.contains("end_timestamp=1700000000"))
        assertTrue(path.contains("default_time=day"))
    }

    @Test
    fun `fetchDataSelf quota 係字串時嗰行丟棄唔會炸`() = runBlocking {
        server.enqueue(ok("""{"success":true,"data":[{"created_at":1700000000,"quota":"1500"}]}"""))
        val d = client.fetchDataSelf(startTs = 0L, endTs = 1L)
        assertTrue(ResponseParsing.pickLogItems(d).mapNotNull { ResponseParsing.parseUsagePoint(it) }.isEmpty())
    }
}
