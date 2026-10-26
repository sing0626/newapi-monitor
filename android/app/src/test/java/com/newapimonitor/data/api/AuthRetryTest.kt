package com.newapimonitor.data.api

import com.newapimonitor.domain.AuthStyle
import com.newapimonitor.domain.ErrorKind
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 認證行為（js _authFetch）：
 *  - 401 → 自動換 header 格式（Bearer ↔ 單值）重試一次，並記住成功格式
 *  - 已記住格式失效 → 清空重新探測
 *  - 拿到 user id 後管理 API 帶 New-Api-User header
 *  - billing 端點用 API Key
 */
class AuthRetryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun ok() = MockResponse()
        .setResponseCode(200)
        .setBody("""{"success":true,"data":{"id":7,"username":"u","quota":100}}""")

    private fun unauthorized() = MockResponse()
        .setResponseCode(401)
        .setBody("""{"success":false,"message":"無權限"}""")

    @Test
    fun `bearer 401 後改用單值重試並記住`() = runBlocking {
        val client = NewApiClient(server.url("/").toString(), accessToken = "tok", apiKey = "")
        server.enqueue(unauthorized())
        server.enqueue(ok())

        val u = client.fetchUserSelf().asJsonObjectOrEmpty()
        assertEquals(7L, u.longOf("id"))
        assertEquals(AuthStyle.RAW, client.currentAccessStyle())

        val r1 = server.takeRequest()
        assertEquals("Bearer tok", r1.getHeader("Authorization"))
        val r2 = server.takeRequest()
        assertEquals("tok", r2.getHeader("Authorization"))
        assertEquals("認證被拒（401）後重試應該用另一種格式", "tok", r2.getHeader("Authorization"))
    }

    @Test
    fun `初始 raw 格式優先使用`() = runBlocking {
        val client = NewApiClient(server.url("/").toString(), accessToken = "tok", apiKey = "", initialAccessStyle = AuthStyle.RAW)
        server.enqueue(ok())
        client.fetchUserSelf()
        assertEquals("tok", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `成功格式會記住 - 之後請求直接用`() = runBlocking {
        val client = NewApiClient(server.url("/").toString(), accessToken = "tok", apiKey = "")
        server.enqueue(unauthorized())
        server.enqueue(ok())
        server.enqueue(ok())
        client.fetchUserSelf()
        client.fetchUserSelf() // 第三個請求應直接用 raw，唔再 401
        server.takeRequest()
        server.takeRequest()
        assertEquals("tok", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `兩種格式都 401 時抛認證錯誤`() = runBlocking {
        val client = NewApiClient(server.url("/").toString(), accessToken = "tok", apiKey = "")
        server.enqueue(unauthorized())
        server.enqueue(unauthorized())
        try {
            client.fetchUserSelf()
            throw AssertionError("應該抛 ApiException(AUTH)")
        } catch (e: ApiException) {
            assertEquals(ErrorKind.AUTH, e.kind)
            assertEquals(401, e.status)
            // 拋出嘅係第一次嘅錯誤訊息（js 同款）
            assertEquals("認證被拒（401）：無權限", e.message)
        }
    }

    @Test
    fun `拿到 user id 後帶 New-Api-User header`() = runBlocking {
        val client = NewApiClient(server.url("/").toString(), accessToken = "tok", apiKey = "")
        client.setUserId(7)
        server.enqueue(ok())
        client.fetchUserSelf()
        assertEquals("7", server.takeRequest().getHeader("New-Api-User"))
    }

    @Test
    fun `billing 端點用 API Key Bearer`() = runBlocking {
        val client = NewApiClient(
            server.url("/").toString(),
            accessToken = "",
            apiKey = "sk-abc",
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"object":"billing_subscription","hard_limit_usd":70}"""))
        val sub = client.fetchSubscription()
        assertEquals(70.0, sub.doubleOf("hard_limit_usd")!!, 1e-9)
        val req = server.takeRequest()
        assertEquals("Bearer sk-abc", req.getHeader("Authorization"))
        assertEquals("/v1/dashboard/billing/subscription", req.path)
    }

    @Test
    fun `缺少憑證直接抛 AUTH 錯誤`() = runBlocking {
        val client = NewApiClient(server.url("/").toString(), accessToken = "", apiKey = "")
        try {
            client.fetchUserSelf()
            throw AssertionError("應該抛 ApiException(AUTH)")
        } catch (e: ApiException) {
            assertEquals(ErrorKind.AUTH, e.kind)
        }
    }

    @Test
    fun `openai 形狀端點不拆信封`() = runBlocking {
        val client = NewApiClient(server.url("/").toString(), accessToken = "", apiKey = "sk-abc")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"object":"list","total_usage":1072}"""))
        val usage = client.fetchUsage()
        assertEquals(1072.0, usage.doubleOf("total_usage")!!, 1e-9)
    }

    @Test
    fun `非 JSON 回應抛 PARSE 錯誤`() = runBlocking {
        val client = NewApiClient(server.url("/").toString(), accessToken = "tok", apiKey = "")
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>gateway</html>"))
        try {
            client.fetchUserSelf()
            throw AssertionError("應該抛 ApiException(PARSE)")
        } catch (e: ApiException) {
            assertEquals(ErrorKind.PARSE, e.kind)
        }
    }

    @Test
    fun `HTTP 500 抛 HTTP 錯誤`() = runBlocking {
        val client = NewApiClient(server.url("/").toString(), accessToken = "tok", apiKey = "")
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        try {
            client.fetchUserSelf()
            throw AssertionError("應該抛 ApiException(HTTP)")
        } catch (e: ApiException) {
            assertEquals(ErrorKind.HTTP, e.kind)
            assertEquals(500, e.status)
        }
    }

    @Test
    fun `401 重試只對 auth 錯誤生效 - 其他錯誤即刻抛`() = runBlocking {
        val client = NewApiClient(server.url("/").toString(), accessToken = "tok", apiKey = "")
        server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))
        try {
            client.fetchUserSelf()
            throw AssertionError("應該抛 ApiException(HTTP)")
        } catch (e: ApiException) {
            assertEquals(ErrorKind.HTTP, e.kind)
            assertEquals(1, server.requestCount) // 唔應該重試
        }
    }
}
