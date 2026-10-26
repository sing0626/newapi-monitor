package com.newapimonitor.data.api

import com.newapimonitor.domain.ErrorKind
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 自適應分頁邊界（js fetchLogWindow）：
 *  - 頁短過 pageSize → 完（truncated=false）
 *  - 撞 maxPages 封頂 → truncated=true
 *  - p / page_size / start_timestamp / end_timestamp / model_name 參數正確
 */
class LogWindowPaginationTest {

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

    private fun logJson(i: Int, ts: Long): String =
        """{"id":$i,"type":2,"created_at":$ts,"model_name":"gpt-4o","token_name":"t",""" +
            """"prompt_tokens":10,"completion_tokens":5,"quota":100,"use_time":1.2,"is_stream":true}"""

    private fun pageJson(count: Int, baseTs: Long): String {
        val items = (0 until count).joinToString(",") { logJson(it, baseTs - it) }
        return """{"success":true,"data":{"items":[$items]}}"""
    }

    private fun ok(body: String): MockResponse =
        MockResponse().setResponseCode(200).setBody(body)

    @Test
    fun `頁短過 pageSize 即完 - truncated false`() = runBlocking {
        // 默認 maxPages=2, pageSize=100：第 1 頁 100 行（續抓），第 2 頁 40 行（< 100 → 完）
        server.enqueue(ok(pageJson(100, 10_000)))
        server.enqueue(ok(pageJson(40, 5_000)))
        val w = client.fetchLogWindow(startTs = 0L, endTs = 20_000L, maxPages = 2)
        assertEquals(140, w.rows.size)
        assertFalse(w.truncated)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `撞 maxPages 封頂 - truncated true`() = runBlocking {
        server.enqueue(ok(pageJson(100, 10_000)))
        val w = client.fetchLogWindow(startTs = 0L, endTs = 20_000L, maxPages = 1)
        assertEquals(100, w.rows.size)
        assertTrue(w.truncated)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `首頁即短過 pageSize - 只抓一頁`() = runBlocking {
        server.enqueue(ok(pageJson(3, 10_000)))
        val w = client.fetchLogWindow(startTs = 0L, endTs = 20_000L, maxPages = 2)
        assertEquals(3, w.rows.size)
        assertFalse(w.truncated)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `rows 按時間降序`() = runBlocking {
        server.enqueue(ok(pageJson(3, 10_000)))
        val w = client.fetchLogWindow(startTs = 0L, endTs = 20_000L)
        assertEquals(10_000L, w.rows[0]["created_at"]?.toString()?.toLong())
        assertEquals(9_998L, w.rows[2]["created_at"]?.toString()?.toLong())
    }

    @Test
    fun `分頁參數用 p 而非 page - 帶 page_size 與時間窗口`() = runBlocking {
        server.enqueue(ok(pageJson(1, 10_000)))
        client.fetchLogWindow(startTs = 1000L, endTs = 2000L, modelName = "gpt-4o")
        val req = server.takeRequest()
        val path = req.path ?: ""
        assertTrue(path.startsWith("/api/log/self?"))
        assertTrue(path.contains("p=1"))
        assertTrue(path.contains("page_size=100"))
        assertTrue(path.contains("start_timestamp=1000"))
        assertTrue(path.contains("end_timestamp=2000"))
        assertTrue(path.contains("model_name=gpt-4o"))
        assertFalse(path.contains("page=1&"))
        assertEquals("Bearer tok", req.getHeader("Authorization"))
    }

    @Test
    fun `type=2 參數可帶上（只顯示消費記錄）`() = runBlocking {
        server.enqueue(ok("""{"success":true,"data":{"items":[]}}"""))
        client.fetchLogs(p = 1, pageSize = 20, startTs = 0L, endTs = 1L, type = 2)
        val path = server.takeRequest().path ?: ""
        assertTrue(path.contains("type=2"))
    }

    @Test
    fun `success=false 信封抛 API 錯誤`() = runBlocking {
        server.enqueue(ok("""{"success":false,"message":"無權限"}"""))
        try {
            client.fetchLogs(p = 1, pageSize = 20)
            throw AssertionError("應該抛 ApiException")
        } catch (e: ApiException) {
            assertEquals(ErrorKind.API, e.kind)
            assertEquals("無權限", e.message)
        }
    }
}
