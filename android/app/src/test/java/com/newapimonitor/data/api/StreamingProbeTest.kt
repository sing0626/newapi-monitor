package com.newapimonitor.data.api

import com.newapimonitor.domain.Instance
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class StreamingProbeTest {
    private val output="""{"choices":[{"delta":{"content":"hi"}}]}"""
    @Test fun `first nonempty output and provider usage define metrics not chunk count`() {
        val metrics=StreamAccumulator()
        metrics.data("""{"choices":[{"delta":{"role":"assistant","content":""}}]}""",10)
        assertNull(metrics.firstAt)
        metrics.data(output,100); metrics.data(output,200)
        metrics.data("""{"choices":[],"usage":{"completion_tokens":21}}""",1000)
        metrics.data("[DONE]",1100)
        assertEquals(100L,metrics.firstAt); assertEquals(20.0,metrics.rate(1100)!!,0.0); assertTrue(metrics.done)
    }
    @Test fun `missing usage is not guessed from text or chunks`() {
        val metrics=StreamAccumulator(); metrics.data(output,50); metrics.data("[DONE]",100)
        assertNull(metrics.rate(100)); assertNull(metrics.tokens)
    }
    @Test fun `SSE probe is one user initiated streaming request and does not persist generated text`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: $output\n\ndata: {\"usage\":{\"completion_tokens\":12},\"choices\":[]}\n\ndata: [DONE]\n\n"))
            val result=StreamingProbe().run(Instance(id="a",baseUrl=server.url("/").toString(),apiKey="sk-local-test"),"model")
            assertTrue(result.ok); assertEquals(12L,result.completionTokens)
            val request=server.takeRequest()
            assertEquals("/v1/chat/completions",request.path); assertEquals("Bearer sk-local-test",request.getHeader("Authorization"))
            val body=request.body.readUtf8(); assertTrue(body.contains("\"max_tokens\":64")); assertTrue(body.contains("\"stream\":true"))
            assertFalse(result.toString().contains("hi")); assertEquals(1,server.requestCount)
        }
    }
    @Test fun `HTTP failure never retries a paid request or exposes upstream error text`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("secret upstream content"))
            val result=StreamingProbe().run(Instance(id="a",baseUrl=server.url("/").toString(),apiKey="sk-test"),"model")
            assertFalse(result.ok); assertEquals("HTTP 429",result.failure); assertEquals(1,server.requestCount)
        }
    }
    @Test fun `cancellation disconnects a response body already being read`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: $output\n\n")
                .removeHeader("Content-Length").setSocketPolicy(SocketPolicy.KEEP_OPEN))
            val task=launch { StreamingProbe().run(Instance(id="a",baseUrl=server.url("/").toString(),apiKey="sk-test"),"model") }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2,TimeUnit.SECONDS)) }
            withTimeout(1500) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
        }
    }
}
