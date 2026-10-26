package com.newapimonitor.data.api

import com.newapimonitor.domain.Instance
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class StreamProbeResult(
    val sourceId: String, val model: String, val ok: Boolean,
    val firstTokenMs: Long? = null, val totalMs: Long = 0,
    val completionTokens: Long? = null, val tokensPerSecond: Double? = null,
    val failure: String? = null,
)

/** Records timings and provider token usage only; generated text is discarded. */
internal class StreamAccumulator {
    var firstAt: Long? = null; private set
    var tokens: Long? = null; private set
    var done = false; private set
    fun data(value: String, elapsedMs: Long) {
        if (value == "[DONE]") { done = true; return }
        val root = Json.parseToJsonElement(value) as? JsonObject ?: return
        require(root["error"] == null) { "Stream error" }
        val usage = root["usage"] as? JsonObject
        val count = (usage?.get("completion_tokens") as? JsonPrimitive)?.longOrNull
        if (count != null && count >= 0) tokens = count
        val choices = root["choices"] as? JsonArray ?: return
        if (firstAt == null && choices.any { choice ->
            val delta = (choice as? JsonObject)?.get("delta") as? JsonObject
            listOf("content", "reasoning_content").any { field ->
                (delta?.get(field) as? JsonPrimitive)?.contentOrNull?.isNotEmpty() == true
            }
        }) firstAt = elapsedMs
    }
    fun rate(endMs: Long): Double? {
        val first = firstAt ?: return null
        val count = tokens?.takeIf { it > 1 } ?: return null
        return if (endMs > first) (count - 1) * 1000.0 / (endMs - first) else null
    }
}

class StreamingProbe(private val http: OkHttpClient = HTTP) {
    suspend fun run(instance: Instance, model: String): StreamProbeResult = suspendCancellableCoroutine { cont ->
        require(instance.apiKey.isNotBlank() && model.isNotBlank() && model.length <= 150)
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        val body = buildJsonObject {
            put("model", model); put("max_tokens", 64); put("stream", true)
            putJsonObject("stream_options") { put("include_usage", true) }
            putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", "Count from 1 to 10.") } }
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(instance.baseUrl.trimEnd('/') + "/v1/chat/completions")
            .header("Authorization", if (instance.authStyleKey == com.newapimonitor.domain.AuthStyle.RAW) instance.apiKey else "Bearer ${instance.apiKey}")
            .header("Accept", "text/event-stream").post(body).build()
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(StreamProbeResult(instance.id, model, false, totalMs = elapsed(), failure = "連線失敗或逾時"))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { r ->
                        if (!r.isSuccessful) return@use StreamProbeResult(instance.id, model, false, totalMs = elapsed(), failure = "HTTP ${r.code}")
                        require(r.header("Content-Type").orEmpty().contains("text/event-stream", ignoreCase = true))
                        val source = r.body?.source() ?: error("No body")
                        val metrics = StreamAccumulator()
                        var size = 0L
                        val event = StringBuilder()
                        fun dispatch() { if (event.isNotEmpty()) { metrics.data(event.toString().trimEnd('\n'), elapsed()); event.setLength(0) } }
                        while (!source.exhausted() && !metrics.done && cont.isActive) {
                            val line = source.readUtf8LineStrict(256 * 1024).removeSuffix("\r")
                            size += line.length
                            require(size < 2 * 1024 * 1024)
                            if (line.isEmpty()) dispatch()
                            else if (line.startsWith("data:")) event.append(line.removePrefix("data:").removePrefix(" ")).append('\n')
                        }
                        dispatch()
                        val end = elapsed()
                        StreamProbeResult(instance.id, model, metrics.done && metrics.firstAt != null,
                            metrics.firstAt, end, metrics.tokens, metrics.rate(end),
                            if (!metrics.done || metrics.firstAt == null) "串流未完整結束／未收到輸出" else null)
                    }
                } catch (_: Exception) { StreamProbeResult(instance.id, model, false, totalMs = elapsed(), failure = "串流格式不支援或連線中斷") }
                if (cont.isActive) cont.resume(result)
            }
        })
    }
    companion object {
        private val HTTP = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    }
}
