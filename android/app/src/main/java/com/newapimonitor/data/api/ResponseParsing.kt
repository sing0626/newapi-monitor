package com.newapimonitor.data.api

import com.newapimonitor.domain.ChannelInfo
import com.newapimonitor.domain.AdminUserInfo
import com.newapimonitor.domain.DataFlowSelf
import com.newapimonitor.domain.FlowItem
import com.newapimonitor.domain.LogEntry
import com.newapimonitor.domain.LogSelfStat
import com.newapimonitor.domain.SpendStats
import com.newapimonitor.domain.TokenInfo
import com.newapimonitor.domain.TopUpInfo
import com.newapimonitor.domain.TopUpRecord
import com.newapimonitor.domain.UserSubscription
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * /api/log/self 容器形狀防禦式解析（js pickLogItems / parseLogEntry / parseLogTotal）：
 * items / records / logs / 裸數組 都要食得落。
 */
object ResponseParsing {

    fun pickLogItems(data: JsonElement?): List<JsonObject> {
        val e = data ?: return emptyList()
        if (e is JsonArray) return e.mapNotNull { it as? JsonObject }
        if (e is JsonObject) {
            for (key in listOf("items", "records", "logs")) {
                val arr = e[key] as? JsonArray ?: continue
                return arr.mapNotNull { it as? JsonObject }
            }
        }
        return emptyList()
    }

    /** total 計數（new-api 有 10000 上限）：data.total 或 data.page_info.total，>0 先算數 */
    fun parseLogTotal(data: JsonElement?): Long? {
        val obj = data as? JsonObject ?: return null
        val direct = obj.longOf("total")
        if (direct != null && direct > 0) return direct
        val pageInfo = obj["page_info"] as? JsonObject ?: return null
        val t = pageInfo.longOf("total")
        return if (t != null && t > 0) t else null
    }

    /**
     * 寬鬆解析一行日誌。type / model_name / quota 嘅過濾唔喺度做（交俾 LogMath.filterConsume），
     * 保持同 js 一致：解析歸解析、過濾歸過濾。
     */
    fun parseLogEntry(o: JsonObject): LogEntry = LogEntry(
        type = o.intOf("type"),
        createdAt = o.longOf("created_at") ?: 0L,
        modelName = o.str("model_name"),
        tokenName = o.str("token_name"),
        promptTokens = o.longOf("prompt_tokens") ?: 0L,
        completionTokens = o.longOf("completion_tokens") ?: 0L,
        quota = (o["quota"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull,
        useTime = o.doubleOf("use_time") ?: 0.0,
        isStream = o.boolOf("is_stream"),
        username = o.str("username"),
    )

    /* ---------- /api/user/ 全站使用者（admin 唯讀） ---------- */

    /** data 形狀防禦：裸數組 / items / records；欄位全寬鬆（缺 → 0/空，唔會炸） */
    fun pickUserItems(data: JsonElement?): List<JsonObject> {
        val e = data ?: return emptyList()
        if (e is JsonArray) return e.mapNotNull { it as? JsonObject }
        if (e is JsonObject) {
            for (key in listOf("items", "records", "users")) {
                val arr = e[key] as? JsonArray ?: continue
                return arr.mapNotNull { it as? JsonObject }
            }
        }
        return emptyList()
    }

    fun parseAdminUser(o: JsonObject): AdminUserInfo = AdminUserInfo(
        id = o.longOf("id") ?: 0L,
        username = o.str("username") ?: "",
        displayName = o.str("display_name") ?: "",
        group = o.str("group") ?: "",
        role = o.intOf("role") ?: 0,
        status = o.intOf("status") ?: 0,
        quota = o.doubleOf("quota") ?: 0.0,
        usedQuota = o.doubleOf("used_quota") ?: 0.0,
        requestCount = o.longOf("request_count") ?: 0L,
    )

    /* ---------- /api/token/ 令牌列表 ---------- */

    /** data 形狀防禦：裸數組（new-api 慣例）/ items / records 都食得落 */
    fun pickTokenItems(data: JsonElement?): List<JsonObject> {
        val e = data ?: return emptyList()
        if (e is JsonArray) return e.mapNotNull { it as? JsonObject }
        if (e is JsonObject) {
            for (key in listOf("items", "records")) {
                val arr = e[key] as? JsonArray ?: continue
                return arr.mapNotNull { it as? JsonObject }
            }
        }
        return emptyList()
    }

    /**
     * 寬鬆解析一張令牌：任何欄位缺失都唔會炸；id 缺失返回 null（無 id 冇得切換狀態）。
     * 列表可能含遮蔽 key，永遠唔將佢帶入模型；status 係數字（1/2/3）。
     */
    fun parseToken(o: JsonObject): TokenInfo? {
        val id = o.longOf("id") ?: return null
        return TokenInfo(
            id = id,
            name = o.str("name") ?: ("#" + id),
            key = "", // list/detail are never a source of revealable secret
            status = o.intOf("status") ?: 0,
            usedQuota = o.doubleOf("used_quota") ?: 0.0,
            remainQuota = o.doubleOf("remain_quota") ?: 0.0,
            unlimitedQuota = o.boolOf("unlimited_quota"),
            expiredTime = o.longOf("expired_time") ?: -1L,
            createdTime = o.longOf("created_time") ?: 0L,
            group = o.str("group") ?: "",
            modelLimitsEnabled = o.boolOf("model_limits_enabled"),
            modelLimits = o.str("model_limits") ?: "",
            allowIps = o.str("allow_ips") ?: "",
        )
    }

    /* ---------- /api/channel/ 渠道列表 ---------- */

    /** 渠道列表 data 形狀防禦：裸數組（new-api 慣例）/ items / records / channels 都食得落 */
    fun pickChannelItems(data: JsonElement?): List<JsonObject> {
        val e = data ?: return emptyList()
        if (e is JsonArray) return e.mapNotNull { it as? JsonObject }
        if (e is JsonObject) {
            for (key in listOf("items", "records", "channels")) {
                val arr = e[key] as? JsonArray ?: continue
                return arr.mapNotNull { it as? JsonObject }
            }
        }
        return emptyList()
    }

    /**
     * 寬鬆解析一條渠道：id/name/status(1啟用/2手動停/3自動封)/type/response_time(ms)。
     * id 缺失返回 null（冇 id 冇用）；response_time 可能係浮點/字串，防禦式收數。
     */
    fun parseChannel(o: JsonObject): ChannelInfo? {
        val id = o.longOf("id") ?: return null
        return ChannelInfo(
            id = id,
            name = o.str("name") ?: ("#" + id),
            status = o.intOf("status") ?: 0,
            type = o.intOf("type") ?: 0,
            responseTime = (o["response_time"] as? JsonPrimitive)?.takeIf { !it.isString }
                ?.let { p -> p.doubleOrNull?.toInt() },
            priority = o.longOf("priority"),
            weight = o.longOf("weight"),
        )
    }

    /* ---------- /api/data/self 數據看板 ---------- */

    /**
     * 寬鬆解析一行看板數據：created_at（unix 秒）+ quota（單位同日誌，要 ÷ qpu）。
     * created_at 缺失或者 quota 缺失 → null（嗰行冇用）。
     */
    fun parseUsagePoint(o: JsonObject): SpendStats.Point? {
        val ts = o.longOf("created_at") ?: return null
        val quota = (o["quota"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
        return SpendStats.Point(ts, quota)
    }

    /* ---------- /api/notice 站點公告 ---------- */

    fun parseNotice(raw: JsonElement?): String {
        if (raw == null) return ""
        val root = (raw as? JsonObject)?.get("data") ?: raw
        return when (root) {
            is JsonPrimitive -> if (root.isString) root.content else ""
            is JsonObject -> root.str("notice") ?: root.str("content") ?: ""
            else -> ""
        }
    }

    /* ---------- /api/log/self/stat 個人實時流量統計 ---------- */

    fun parseLogSelfStat(raw: JsonElement?): LogSelfStat {
        val obj = ((raw as? JsonObject)?.get("data") as? JsonObject) ?: (raw as? JsonObject) ?: JsonObject(emptyMap())
        return LogSelfStat(
            quota = obj.doubleOf("quota") ?: 0.0,
            tokenUsed = obj.longOf("token_used") ?: obj.longOf("token") ?: 0L,
            rpm = obj.longOf("rpm") ?: 0L,
            tpm = obj.longOf("tpm") ?: 0L,
        )
    }

    /* ---------- /api/data/flow/self 多維度流量統計 ---------- */

    private fun parseFlowList(element: JsonElement?): List<FlowItem> {
        val arr = element as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = obj.str("name") ?: obj.str("key") ?: obj.str("model_name") ?: obj.str("username") ?: return@mapNotNull null
            val value = obj.doubleOf("value") ?: obj.doubleOf("quota") ?: obj.doubleOf("count") ?: 0.0
            FlowItem(name, value)
        }
    }

    fun parseDataFlowSelf(raw: JsonElement?): DataFlowSelf {
        val obj = ((raw as? JsonObject)?.get("data") as? JsonObject) ?: (raw as? JsonObject) ?: JsonObject(emptyMap())
        return DataFlowSelf(
            users = parseFlowList(obj["users"]),
            models = parseFlowList(obj["models"]),
            tokens = parseFlowList(obj["tokens"]),
            groups = parseFlowList(obj["groups"]),
        )
    }

    /* ---------- /api/user/topup 充值與訂閱 ---------- */

    fun parseTopUpInfo(raw: JsonElement?): TopUpInfo {
        val obj = ((raw as? JsonObject)?.get("data") as? JsonObject) ?: (raw as? JsonObject) ?: JsonObject(emptyMap())
        val min = obj.doubleOf("min_topup") ?: obj.doubleOf("min_top_up")
        val online = obj.boolOf("enable_online_topup") ?: obj.boolOf("online_topup")
        val exchange = obj.boolOf("enable_exchange_topup") ?: obj.boolOf("exchange_topup")
        val methods = (obj["pay_methods"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
        } ?: emptyList()
        return TopUpInfo(min, online, exchange, methods)
    }

    fun parseTopUpRecords(raw: JsonElement?): List<TopUpRecord> {
        val data = (raw as? JsonObject)?.get("data") ?: raw
        val arr = when (data) {
            is JsonArray -> data
            is JsonObject -> (data["items"] ?: data["records"] ?: data["topups"]) as? JsonArray
            else -> null
        } ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.longOf("id") ?: return@mapNotNull null
            TopUpRecord(
                id = id,
                amount = obj.doubleOf("amount") ?: 0.0,
                createTime = obj.longOf("create_time") ?: obj.longOf("created_at") ?: 0L,
                status = obj.str("status") ?: "",
                payType = obj.str("pay_type") ?: obj.str("type") ?: "",
            )
        }
    }

    fun parseUserSubscription(raw: JsonElement?): UserSubscription? {
        val obj = ((raw as? JsonObject)?.get("data") as? JsonObject) ?: (raw as? JsonObject) ?: return null
        val plan = obj.str("plan_name") ?: obj.str("name") ?: return null
        return UserSubscription(
            planName = plan,
            expireTime = obj.longOf("expire_time") ?: -1L,
            status = obj.str("status") ?: "",
        )
    }
}
