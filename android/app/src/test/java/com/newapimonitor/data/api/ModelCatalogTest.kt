package com.newapimonitor.data.api

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun `catalog uses only selected API key and performs one GET`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"object":"list","data":[{"id":"z-model"},{"id":"a-model"},{"id":"a-model"}]}"""))
            val client = NewApiClient(server.url("/").toString(), accessToken = "access-secret", apiKey = "key-secret")
            assertEquals(listOf("a-model", "z-model"), client.fetchKeyModels())
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/models", request.path)
            assertEquals("Bearer key-secret", request.getHeader("Authorization"))
            assertEquals(1, server.requestCount)
        }
    }
}
