package com.newapimonitor.data.api

import com.newapimonitor.domain.AuthStyle
import com.newapimonitor.domain.ErrorKind
import com.newapimonitor.domain.ChannelInfo
import com.newapimonitor.domain.PingResult
import com.newapimonitor.domain.TokenInfo
import com.newapimonitor.util.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * NewAPI client（移植自網頁版 js/api.js 行為規格）：
 *  - 管理 API（/api/ 開頭端點）用 Access Token；計費 API（/v1/dashboard/billing/ 開頭端點）用 API Key Bearer sk-…
 *  - 收到 401 自動改用另一種 header 格式（有／無 Bearer 前綴）重試一次，並記住成功格式
 *    （每種憑證獨立記憶，避免並行請求互相覆蓋；只喺未探測到格式時記入）
 *  - 攞到 user id 後，管理 API 一併送 New-Api-User header（兼容新版站點）
 *  - 回應一律拆 {success, message, data} 信封（openai 形狀端點直接返回）
 */
class NewApiClient(
    baseUrl: String,
    private val accessToken: String,
    private val apiKey: String,
    initialAccessStyle: AuthStyle? = null,
    initialKeyStyle: AuthStyle? = null,
    private val quotaPerUnit: Double? = null,
    private val http: OkHttpClient = DEFAULT_HTTP,
) {
    private val base = baseUrl.trim().trimEnd('/')
    private val accessStyle = AtomicReference<AuthStyle?>(initialAccessStyle)
    private val keyStyle = AtomicReference<AuthStyle?>(initialKeyStyle)
    private val userId = AtomicReference<Long?>(null)

    fun currentAccessStyle(): AuthStyle? = accessStyle.get()
    fun currentKeyStyle(): AuthStyle? = keyStyle.get()
    fun setUserId(id: Long?) = userId.set(id)

    /* ---------------- 公開端點（對應 api.js） ---------------- */

    /** GET /api/status — 無認證公開端點 */
    suspend fun fetchStatus(): JsonObject {
        val data = getJson("/api/status", AuthKind.NONE, openai = false)
        return data as? JsonObject ?: JsonObject(emptyMap())
    }

    /** GET /api/notice — 公開端點獲取站點公告 */
    suspend fun fetchNotice(): JsonElement = getJson("/api/notice", AuthKind.NONE, openai = false)

    /** GET /api/user/self — quota / used_quota 單位係 quota */
    suspend fun fetchUserSelf(): JsonElement = getJson("/api/user/self", AuthKind.ACCESS, openai = false)

    suspend fun fetchPricing(): JsonElement = getJson("/api/pricing", AuthKind.ACCESS, openai = false)

    suspend fun fetchSelfGroups(): JsonElement = getJson("/api/user/self/groups", AuthKind.ACCESS, openai = false)

    /**
     * GET /api/user/?p=&size=(&username=) — 使用者列表（管理 API，要 admin；401/403 各自擲清楚錯誤）。
     * 唯讀監察用；呢個 App 唔會代管理員改其他使用者。
     */
    suspend fun fetchUsers(p: Int = 1, size: Int = 100, keyword: String = ""): JsonElement {
        val q = StringBuilder("/api/user/?p=")
            .append(p.coerceAtLeast(1))
            .append("&size=").append(size.coerceIn(1, 100))
        if (keyword.isNotBlank()) q.append("&username=").append(urlEncode(keyword))
        return getJson(q.toString(), AuthKind.ACCESS, openai = false)
    }

    /** GET /api/log/?p=&page_size= — 全站日誌（管理 API，要 admin）；唯讀。 */
    suspend fun fetchAllLogs(p: Int = 1, pageSize: Int = 100, startTs: Long? = null, endTs: Long? = null): JsonElement {
        val q = StringBuilder("/api/log/?p=")
            .append(p.coerceAtLeast(1))
            .append("&page_size=").append(pageSize.coerceIn(1, 100))
        startTs?.let { q.append("&start_timestamp=").append(it) }
        endTs?.let { q.append("&end_timestamp=").append(it) }
        return getJson(q.toString(), AuthKind.ACCESS, openai = false)
    }

    /**
     * GET /api/token/?p=1&size=100 — 令牌列表（管理 API，Access Token）。
     * data 慣例係裸數組：{id, name, key, status, used_quota, remain_quota, unlimited_quota, expired_time, created_time}
     * keyword 非空時走伺服器端搜尋（新版站點支援；舊版可能忽略 → 客戶端仲有本地過濾兜底）。
     */
    suspend fun fetchTokens(p: Int = 1, size: Int = 100, keyword: String = ""): JsonElement {
        val q = StringBuilder("/api/token/?p=")
            .append(p.coerceAtLeast(1))
            .append("&size=").append(size.coerceIn(1, 100))
        if (keyword.isNotBlank()) q.append("&keyword=").append(urlEncode(keyword))
        return getJson(q.toString(), AuthKind.ACCESS, openai = false)
    }

    /** Detail must be fetched immediately before every edit. A list row is never an update payload. */
    suspend fun fetchTokenDetail(id: Long): JsonObject {
        require(id > 0)
        return getJson("/api/token/$id", AuthKind.ACCESS, openai = false) as? JsonObject
            ?: throw ApiException(ErrorKind.PARSE, "令牌詳情不完整，已取消修改")
    }

    /** Explicit one-shot reveal. Never retry a sensitive POST on 401 and never cache the returned secret. */
    suspend fun revealTokenKey(id: Long): String {
        require(id > 0)
        val data = requestJson("/api/token/$id/key", AuthKind.ACCESS, openai = false,
            method = "POST", body = "{}", retry401 = false)
        val key = (data as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: ((data as? JsonObject)?.get("key") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (!isSafeTokenKey(key)) throw ApiException(ErrorKind.PARSE, "無法安全顯示令牌 Key")
        return key!!
    }

    /** Overlay only requested fields onto the complete original JSON, preserving all unknown/restriction fields. */
    suspend fun updateToken(id: Long, changes: Map<String, JsonElement>): JsonElement {
        require(id > 0)
        require(changes.isNotEmpty() && changes.keys.all { it in TOKEN_EDIT_FIELDS })
        val original = fetchTokenDetail(id)
        val key = (original["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val required = listOf("id", "name", "status", "used_quota", "remain_quota",
            "unlimited_quota", "expired_time", "created_time", "group", "model_limits_enabled",
            "model_limits", "allow_ips")
        if (original.longOf("id") != id || !isSafeTokenKey(key) ||
            required.any { original[it] == null || original[it] == JsonNull }) {
            throw ApiException(ErrorKind.PARSE, "令牌詳情不完整或 Key 已遮蔽，已取消修改")
        }
        val body = JsonObject(original.toMutableMap().apply { putAll(changes) }).toString()
        return requestJson("/api/token/", AuthKind.ACCESS, openai = false, method = "PUT", body = body)
    }

    private fun isSafeTokenKey(value: String?): Boolean = value != null && value.isNotBlank() &&
        value.none { it.isWhitespace() || it == '*' || it == '•' || it == '…' || it == '×' } &&
        !value.contains("xxxx", ignoreCase = true) && !value.contains("mask", ignoreCase = true)


    /**
     * POST /api/token/ — 新增令牌。
     * body {"name":..,"remain_quota":..,"unlimited_quota":..,"expired_time":-1,
     *       "model_limits_enabled":false,"model_limits":"","allow_ips":"","group":""}
     */
    suspend fun createToken(
        name: String,
        remainQuota: Long,
        unlimited: Boolean,
        expiredTime: Long = -1L,
        group: String = "",
        modelLimitsEnabled: Boolean = false,
        modelLimits: String = "",
        allowIps: String = "",
    ): JsonElement {
        val body = JsonObject(
            mapOf(
                "name" to JsonPrimitive(name),
                "remain_quota" to JsonPrimitive(remainQuota),
                "unlimited_quota" to JsonPrimitive(unlimited),
                "expired_time" to JsonPrimitive(expiredTime),
                "model_limits_enabled" to JsonPrimitive(modelLimitsEnabled),
                "model_limits" to JsonPrimitive(modelLimits),
                "allow_ips" to JsonPrimitive(allowIps),
                "group" to JsonPrimitive(group),
            )
        ).toString()
        return requestJson("/api/token/", AuthKind.ACCESS, openai = false, method = "POST", body = body)
    }

    /** DELETE /api/token/{id} — 刪除令牌 */
    suspend fun deleteToken(id: Long): JsonElement =
        requestJson("/api/token/$id", AuthKind.ACCESS, openai = false, method = "DELETE", body = null)

    /**
     * GET /api/channel/?p=1&size=100 — 渠道列表（管理 API，要 admin 權限；403 會擲 AUTH／HTTP 錯誤）。
     * rows 含 id/name/status(1啟用/2手動停/3自動封)/type/response_time。
     */
    suspend fun fetchChannels(p: Int = 1, size: Int = 100): JsonElement {
        val q = StringBuilder("/api/channel/?p=")
            .append(p.coerceAtLeast(1))
            .append("&size=").append(size.coerceIn(1, 100))
        return getJson(q.toString(), AuthKind.ACCESS, openai = false)
    }

    /** POST /api/channel/{id}/status — 專用狀態端點；PUT /api/channel/ 係配置編輯，唔可以送縮水渠道覆蓋配置。 */
    suspend fun updateChannelStatus(channel: ChannelInfo, newStatus: Int): JsonElement {
        require(channel.id > 0) { "渠道 ID 必須大於 0" }
        require(newStatus == 1 || newStatus == 2) { "只能手動啟用或停用渠道" }
        return requestJson(
            "/api/channel/${channel.id}/status",
            AuthKind.ACCESS,
            openai = false,
            method = "POST",
            body = JsonObject(mapOf("status" to JsonPrimitive(newStatus))).toString(),
        )
    }

    /** GET /api/channel/test/{id} — 測試單個渠道連通與延遲 */
    suspend fun testChannel(id: Long): JsonElement =
        getJson("/api/channel/test/$id", AuthKind.ACCESS, openai = false)

    /** DELETE /api/channel/{id} — 刪除渠道 */
    suspend fun deleteChannel(id: Long): JsonElement =
        requestJson("/api/channel/$id", AuthKind.ACCESS, openai = false, method = "DELETE", body = null)

    /**
     * GET /api/data/self?start_timestamp=&end_timestamp=&default_time=day — 個人數據看板。
     * data[] 欄位大致 {created_at, model_name, count, quota, token_used}；quota 單位同日誌。
     */
    suspend fun fetchDataSelf(startTs: Long, endTs: Long): JsonElement {
        val q = StringBuilder("/api/data/self?start_timestamp=")
            .append(startTs)
            .append("&end_timestamp=").append(endTs)
            .append("&default_time=day")
        return getJson(q.toString(), AuthKind.ACCESS, openai = false)
    }

    /** GET /api/user/models — 可用模型列表（[]string） */
    /** Read-only OpenAI catalog uses this source's API key, never its management token. */
    suspend fun fetchKeyModels(): List<String> {
        val response = getJson("/v1/models", AuthKind.KEY, openai = true).asJsonObjectOrEmpty()
        val rows = response["data"] as? JsonArray ?: return emptyList()
        return rows.mapNotNull { it.asJsonObjectOrEmpty().str("id") }.filter(String::isNotBlank).distinct().sorted()
    }

    suspend fun fetchUserModels(): List<String> {
        val d = getJson("/api/user/models", AuthKind.ACCESS, openai = false)
        return (d as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?: emptyList()
    }

    /**
     * GET /api/log/self — 分頁參數係 p（唔係 page）+ page_size（1..100），
     * 另帶 start_timestamp / end_timestamp / model_name / token_name / type。
     */
    suspend fun fetchLogs(
        p: Int,
        pageSize: Int,
        startTs: Long? = null,
        endTs: Long? = null,
        modelName: String? = null,
        tokenName: String? = null,
        type: Int? = null,
    ): JsonElement {
        val q = StringBuilder("/api/log/self?p=")
            .append(p.coerceAtLeast(1))
            .append("&page_size=").append(pageSize.coerceIn(1, 100))
        startTs?.let { q.append("&start_timestamp=").append(it) }
        endTs?.let { q.append("&end_timestamp=").append(it) }
        modelName?.takeIf { it.isNotEmpty() }?.let { q.append("&model_name=").append(urlEncode(it)) }
        tokenName?.takeIf { it.isNotEmpty() }?.let { q.append("&token_name=").append(urlEncode(it)) }
        type?.let { q.append("&type=").append(it) }
        return getJson(q.toString(), AuthKind.ACCESS, openai = false)
    }

    data class LogWindow(val rows: List<JsonObject>, val truncated: Boolean)

    /**
     * 自適應分頁抓日誌窗口：由 startTs 到 endTs，最多 maxPages 頁 × pageSize 行。
     * rows 按時間降序；truncated=true 表示撞到 maxPages 封頂。
     */
    suspend fun fetchLogWindow(
        startTs: Long?,
        endTs: Long?,
        modelName: String? = null,
        maxPages: Int = 2,
        pageSize: Int = 100,
        tokenName: String? = null,
        type: Int? = null,
    ): LogWindow {
        val max = maxPages.coerceAtLeast(1)
        val size = pageSize.coerceIn(1, 100)
        val all = ArrayList<JsonObject>()
        var truncated = false
        var p = 1
        while (true) {
            val data = fetchLogs(p = p, pageSize = size, startTs = startTs, endTs = endTs, modelName = modelName, tokenName = tokenName, type = type)
            val arr = ResponseParsing.pickLogItems(data)
            all.addAll(arr)
            if (arr.size < size) {
                truncated = false
                break
            }
            if (p >= max) {
                truncated = true
                break
            }
            p += 1
        }
        all.sortByDescending { (it["created_at"] as? JsonPrimitive)?.longOrNull ?: 0L }
        return LogWindow(all, truncated)
    }

    /** GET /v1/dashboard/billing/subscription — sk- key；hard_limit_usd 已係美元 */
    suspend fun fetchSubscription(): JsonObject {
        val j = getJson("/v1/dashboard/billing/subscription", AuthKind.KEY, openai = true)
        return j as? JsonObject ?: JsonObject(emptyMap())
    }

    /** GET /v1/dashboard/billing/usage — total_usage 單位係美分（÷100 = 美元） */
    suspend fun fetchUsage(): JsonObject {
        val j = getJson("/v1/dashboard/billing/usage", AuthKind.KEY, openai = true)
        return j as? JsonObject ?: JsonObject(emptyMap())
    }

    /** GET /api/log/self/stat — 個人實時流量與 RPM/TPM 統計 */
    suspend fun fetchLogSelfStat(): JsonElement = getJson("/api/log/self/stat", AuthKind.ACCESS, openai = false)

    /**
     * GET /api/data/flow/self — 個人多維度流量統計（tokens, models, groups, users）。
     * 上游 new-api 強制要求時間區間不超過 30 天（2592000 秒），否則返回 400 Bad Request。
     */
    suspend fun fetchDataFlowSelf(startTs: Long, endTs: Long): JsonElement {
        val maxDuration = 2592000L // 30 days
        val adjustedStart = if (endTs - startTs > maxDuration) endTs - maxDuration else startTs
        val q = "/api/data/flow/self?start_timestamp=$adjustedStart&end_timestamp=$endTs"
        return getJson(q, AuthKind.ACCESS, openai = false)
    }

    /** GET /api/user/topup/info — 充值方式及最低充值限制 */
    suspend fun fetchTopUpInfo(): JsonElement = getJson("/api/user/topup/info", AuthKind.ACCESS, openai = false)

    /** GET /api/user/topup/self — 個人充值歷史記錄 */
    suspend fun fetchUserTopUps(): JsonElement = getJson("/api/user/topup/self", AuthKind.ACCESS, openai = false)

    /** GET /api/subscription/self — 個人訂閱/套餐資訊 */
    suspend fun fetchSubscriptionSelf(): JsonElement = getJson("/api/subscription/self", AuthKind.ACCESS, openai = false)

    /* ---------------- 認證 + 請求核心 ---------------- */

    private enum class AuthKind { NONE, ACCESS, KEY }

    private suspend fun getJson(path: String, kind: AuthKind, openai: Boolean): JsonElement =
        requestJson(path, kind, openai, method = "GET", body = null)

    /** GET / PUT 共用核心：401 fallback + 信封拆解邏輯完全一致 */
    private suspend fun requestJson(
        path: String,
        kind: AuthKind,
        openai: Boolean,
        method: String,
        body: String?,
        retry401: Boolean = true,
    ): JsonElement =
        withContext(Dispatchers.IO) {
            val value = when (kind) {
                AuthKind.ACCESS -> accessToken
                AuthKind.KEY -> apiKey
                AuthKind.NONE -> ""
            }
            if (kind != AuthKind.NONE && value.isBlank()) {
                throw ApiException(
                    ErrorKind.AUTH,
                    "缺少" + if (kind == AuthKind.ACCESS) "Access Token" else "API Key",
                    401,
                )
            }
            val styleRef = when (kind) {
                AuthKind.ACCESS -> accessStyle
                AuthKind.KEY -> keyStyle
                AuthKind.NONE -> null
            }
            val first = styleRef?.get()
            val order = if (first == AuthStyle.RAW) {
                listOf(AuthStyle.RAW, AuthStyle.BEARER)
            } else {
                listOf(AuthStyle.BEARER, AuthStyle.RAW)
            }
            val attempts: List<AuthStyle?> = if (kind == AuthKind.NONE) listOf(null)
                else if (!retry401) order.take(1) else order
            var lastAuthError: ApiException? = null
            for (style in attempts) {
                try {
                    return@withContext attempt(path, kind, value, style, openai, method, body)
                } catch (e: ApiException) {
                    // 401 → 換另一種 header 格式重試一次；兩次都 401 時擲出第一次嘅錯誤（js 同款）
                    if (e.kind == ErrorKind.AUTH && e.status == 401 && kind != AuthKind.NONE) {
                        if (lastAuthError == null) lastAuthError = e
                        continue
                    }
                    throw e
                }
            }
            throw lastAuthError ?: ApiException(ErrorKind.AUTH, "認證被拒（401）", 401)
        }

    private suspend fun attempt(
        path: String,
        kind: AuthKind,
        value: String,
        style: AuthStyle?,
        openai: Boolean,
        method: String,
        body: String?,
    ): JsonElement {
        val builder = Request.Builder().url(base + path)
        val jsonBody = (body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType())
        when (method.uppercase()) {
            "PUT" -> builder.put(jsonBody)
            "POST" -> builder.post(jsonBody)
            // 刪除通常冇 body；上游要 body 時照送空 JSON object 都兼容
            "DELETE" -> if (body != null) builder.delete(jsonBody) else builder.delete()
            else -> builder.get()
        }
        when {
            kind == AuthKind.NONE -> Unit
            style == AuthStyle.BEARER -> builder.header("Authorization", "Bearer $value")
            else -> builder.header("Authorization", value)
        }
        val uid = userId.get()
        if (kind == AuthKind.ACCESS && uid != null) builder.header("New-Api-User", uid.toString())

        val call = try {
            http.newCall(builder.build())
        } catch (e: IllegalArgumentException) {
            throw ApiException(ErrorKind.NETWORK, "Base URL 格式錯誤：$base$path")
        }
        val resp = try {
            call.await()
        } catch (e: IOException) {
            // CancellationException 唔係 IOException，會喺 await 入面自然穿透
            throw ApiException(ErrorKind.NETWORK, "網絡請求失敗（站點不可達、網絡中斷或超時）")
        }

        resp.use { r ->
            if (r.code == 401) {
                // 記住嘅格式失效 → 清空等下次重新探測
                val styleRef = when (kind) {
                    AuthKind.ACCESS -> accessStyle
                    AuthKind.KEY -> keyStyle
                    AuthKind.NONE -> null
                }
                if (styleRef != null && style != null && styleRef.get() == style) styleRef.set(null)
                val msg = runCatching {
                    ((Json.parseToJsonElement(r.body?.string() ?: "") as? JsonObject)
                        ?.get("message") as? JsonPrimitive)
                        ?.takeIf { it.isString }?.content
                }.getOrNull()
                throw ApiException(
                    ErrorKind.AUTH,
                    if (msg != null) "認證被拒（401）：$msg" else "認證被拒（401）",
                    401,
                )
            }
            val styleRef = when (kind) {
                AuthKind.ACCESS -> accessStyle
                AuthKind.KEY -> keyStyle
                AuthKind.NONE -> null
            }
            // 只喺未探測到格式時記住，避免並行請求互相覆蓋
            if (styleRef != null && style != null) styleRef.compareAndSet(null, style)
            if (r.code == 403 && path.startsWith("/api/channel/")) {
                throw ApiException(ErrorKind.AUTH, "管理員權限不足（403）：需要渠道管理權限", 403)
            }
            if (!r.isSuccessful) throw ApiException(ErrorKind.HTTP, "伺服器回應 HTTP ${r.code}", r.code)

            val bodyStr = r.body?.string() ?: ""
            // 嚴格 JSON 檢查：kotlinx parseToJsonElement 對非 JSON 文本（HTML 錯誤頁等）會 lenient 解析，
            // 呢度先驗證首個非空白字符係 JSON value 起始符，行為對齊 js res.json()（唔係 JSON → PARSE）
            val trimmed = bodyStr.trimStart()
            if (trimmed.isEmpty() || trimmed.first() !in JSON_VALUE_START_CHARS) {
                throw ApiException(ErrorKind.PARSE, "回應唔係有效 JSON")
            }
            val element = try {
                Json.parseToJsonElement(bodyStr)
            } catch (e: Exception) {
                throw ApiException(ErrorKind.PARSE, "回應唔係有效 JSON")
            }
            if (openai) return element
            val obj = element as? JsonObject ?: return element
            val success = obj["success"] as? JsonPrimitive
            val successFalse = success != null && !success.isString && success.booleanOrNull == false
            if (successFalse) {
                val m = (obj["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                throw ApiException(ErrorKind.API, m ?: "API 返回 success=false")
            }
            return obj["data"] ?: JsonNull
        }
    }

    /**
     * POST /v1/chat/completions — 模型測速探針（Ping / TTFT 延遲測量）
     * 送 1-token 簡單對話測量客戶端真實 RTT 延遲。
     */
    suspend fun pingModel(modelName: String): PingResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val payload = JsonObject(
            mapOf(
                "model" to JsonPrimitive(modelName),
                "messages" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "role" to JsonPrimitive("user"),
                                "content" to JsonPrimitive("hi"),
                            )
                        )
                    )
                ),
                "max_tokens" to JsonPrimitive(1),
                "stream" to JsonPrimitive(false),
            )
        ).toString()
        try {
            requestJson("/v1/chat/completions", AuthKind.KEY, openai = true, method = "POST", body = payload)
            val ms = System.currentTimeMillis() - t0
            PingResult(ok = true, latencyMs = ms, model = modelName, status = 200, message = "完成 (${ms}ms)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            val ms = System.currentTimeMillis() - t0
            PingResult(
                ok = false,
                latencyMs = ms,
                model = modelName,
                status = e.status ?: 0,
                message = e.message ?: "請求失敗",
            )
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - t0
            PingResult(
                ok = false,
                latencyMs = ms,
                model = modelName,
                status = 0,
                message = e.message ?: "未知錯誤",
            )
        }
    }

    /* ---------------- 連接測試（設定頁「測試連接」）：逐個憑證實測，回結構化結果 ---------------- */

    data class TestLine(val t: String, val s: String) // t: ok | err | info
    data class TestResult(val ok: Boolean, val lines: List<TestLine>)

    suspend fun testConnection(): TestResult {
        val out = ArrayList<TestLine>()
        var ok = false
        val hasTok = accessToken.isNotBlank()
        val hasKey = apiKey.isNotBlank()

        if (!hasTok && !hasKey) {
            out.add(
                TestLine("err", "未填任何令牌：至少要 Access Token（管理 API）或 API Key（sk-…，計費 API）其中之一")
            )
            return TestResult(false, out)
        }

        var status: JsonObject? = null
        if (hasTok) {
            status = runCatching { fetchStatus() }.getOrNull()
            if (status != null) {
                val name = status.str("system_name") ?: "?"
                val ver = status.str("version")
                out.add(TestLine("ok", "站點在線 · $name" + (ver?.let { " · $it" } ?: "")))
            } else {
                out.add(TestLine("info", "提示：/api/status 暫時不可達（唔影響後續測試）"))
            }
        }

        if (hasTok) {
            val qpu = quotaPerUnit?.takeIf { it > 0 }
                ?: status?.doubleOf("quota_per_unit")?.takeIf { it > 0 }
                ?: 500000.0
            try {
                val u = fetchUserSelf().asJsonObjectOrEmpty()
                u.longOf("id")?.let { setUserId(it) }
                val uname = u.str("display_name") ?: u.str("username") ?: ("#" + (u.longOf("id") ?: 0L))
                out.add(TestLine("ok", "Access Token 有效 ✓ 用戶：$uname"))
                out.add(
                    TestLine(
                        "info",
                        "剩餘 ${Fmt.usd((u.doubleOf("quota") ?: 0.0) / qpu, 4)}" +
                            " · 已用 ${Fmt.usd((u.doubleOf("used_quota") ?: 0.0) / qpu, 4)}" +
                            " · QuotaPerUnit ${Fmt.num(qpu)}"
                    )
                )
                ok = true
            } catch (e: ApiException) {
                out.add(TestLine("err", "Access Token 測試失敗：${e.message}"))
                if (e.kind == ErrorKind.NETWORK) {
                    out.add(TestLine("info", "提示：請確認 Base URL 可達、裝置有網絡連接"))
                }
                if (e.kind == ErrorKind.AUTH) {
                    out.add(TestLine("info", "提示：Access Token 喺 NewAPI 控制台 → 個人設置 → 生成系統訪問令牌"))
                }
            }
        }

        if (hasKey) {
            try {
                val sub = fetchSubscription()
                val use = fetchUsage()
                out.add(
                    TestLine(
                        "ok",
                        "API Key 有效 ✓ 總額 ${Fmt.usd(sub.doubleOf("hard_limit_usd") ?: 0.0, 4)}" +
                            " · 已用 ${Fmt.usd((use.doubleOf("total_usage") ?: 0.0) / 100.0, 4)}"
                    )
                )
                ok = true
            } catch (e: ApiException) {
                out.add(TestLine("err", "API Key 測試失敗：${e.message}"))
                if (e.kind == ErrorKind.NETWORK) {
                    out.add(TestLine("info", "提示：請確認 Base URL 同 key 正確"))
                }
            }
        }

        out.add(
            TestLine(
                "info",
                "認證格式：Access → ${styleLabel(currentAccessStyle())}；Key → ${styleLabel(currentKeyStyle())}"
            )
        )
        return TestResult(ok, out)
    }

    private fun styleLabel(style: AuthStyle?): String =
        if (style == AuthStyle.RAW) "單值（無 Bearer）" else "Bearer 前綴"

    companion object {
        val DEFAULT_HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        /** JSON value 可以開始嘅字符（object/array/string/number/true/false/null） */
        private const val JSON_VALUE_START_CHARS = "{\"[-0123456789tfn"
        private val TOKEN_EDIT_FIELDS = setOf("name", "status", "remain_quota", "unlimited_quota",
            "expired_time", "group", "model_limits_enabled", "model_limits", "allow_ips")

        private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
