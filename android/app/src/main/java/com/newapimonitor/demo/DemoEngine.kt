package com.newapimonitor.demo

import com.newapimonitor.domain.BalanceSample
import com.newapimonitor.domain.AdminUserInfo
import com.newapimonitor.domain.ChannelInfo
import com.newapimonitor.domain.DashboardSnapshot
import com.newapimonitor.domain.DataFlowSelf
import com.newapimonitor.domain.ErrorStats
import com.newapimonitor.domain.FlowItem
import com.newapimonitor.domain.Heatmap
import com.newapimonitor.domain.LogEntry
import com.newapimonitor.domain.LogMath
import com.newapimonitor.domain.LogPage
import com.newapimonitor.domain.LogSelfStat
import com.newapimonitor.domain.PingResult
import com.newapimonitor.domain.Quota
import com.newapimonitor.domain.SiteNotice
import com.newapimonitor.domain.SpendStats
import com.newapimonitor.domain.StatusInfo
import com.newapimonitor.domain.TokenInfo
import com.newapimonitor.domain.TopUpInfo
import com.newapimonitor.domain.TopUpRecord
import com.newapimonitor.domain.UserInfo
import com.newapimonitor.domain.UserSubscription
import com.newapimonitor.util.Fmt
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.random.Random

/**
 * 演示模式（移植 js/demo.js）：完全離線生成仿真數據，即開即有完整 5 日數據，
 * 每個 tick 有細微變化。snapshot() 形狀同真實 buildSnapshot 輸出一致，渲染層零差別。
 * demo 同真實模式狀態完全隔離：呢度唔會掂任何 network / repository 狀態。
 */
object DemoEngine {

    const val QPU = 500000.0
    private const val DEMO_INTERVAL_HINT_MS = 5000L

    /** 餘額歷史種子跨度（日） */
    const val HISTORY_SEED_DAYS = 7L

    @Volatile
    private var started = false

    @Volatile
    private var balance = 0.0 // USD

    @Volatile
    private var usedTotal = 87.4134 // USD（歷史）

    @Volatile
    private var requestCount = 1284L

    @Volatile
    private var logs: List<LogEntry> = emptyList() // 統一日誌池（跨 5 日），時間降序維護

    @Volatile
    private var tokens: List<TokenInfo> = emptyList() // 演示令牌池（狀態／用量隨機合理）

    @Volatile
    private var channels: List<ChannelInfo> = emptyList() // 演示渠道池（status 混合 1/2/3）

    private data class DemoModel(
        val name: String,
        val w: Int,
        val costLo: Double,
        val costHi: Double,
        val ptLo: Int,
        val ptHi: Int,
        val ctLo: Int,
        val ctHi: Int,
    )

    private val MODELS = listOf(
        DemoModel("gpt-4o", 24, 0.004, 0.045, 300, 4200, 120, 1800),
        DemoModel("gpt-4o-mini", 22, 0.0004, 0.004, 200, 3500, 80, 900),
        DemoModel("claude-sonnet-4-20250514", 18, 0.006, 0.060, 400, 6000, 150, 2200),
        DemoModel("deepseek-chat", 13, 0.0006, 0.006, 200, 4000, 100, 1500),
        DemoModel("gemini-2.5-pro", 9, 0.003, 0.050, 300, 5000, 100, 2000),
        DemoModel("claude-opus-4-1", 6, 0.020, 0.180, 500, 8000, 200, 3000),
        DemoModel("o3-mini", 5, 0.002, 0.020, 300, 3000, 200, 2500),
        DemoModel("qwen-max", 3, 0.001, 0.010, 200, 3000, 80, 1200),
    )

    private val TOKEN_NAMES = listOf("ide-chat", "prod-app", "cli-dev", "notebook", "agent-pipeline")

    /** type=5 錯誤記錄佔比（令 ErrorStats／熱力圖有嘢睇） */
    private const val ERROR_LOG_RATIO = 0.04

    private val ERROR_MESSAGES = listOf(
        "无可用渠道 (rate limit exceeded)",
        "429 Too Many Requests：上游限流，請稍後重試",
        "upstream timeout after 30s (connect)",
        "insufficient quota: user quota exhausted",
        "502 Bad Gateway: upstream channel error",
    )

    private val CHANNEL_TYPES = listOf(1, 14, 24, 33, 42) // OpenAI / Anthropic / Gemini / AWS / Vertex 風味

    const val DEMO_TICK_MS = DEMO_INTERVAL_HINT_MS

    private fun rand(a: Double, b: Double): Double = a + Random.nextDouble() * (b - a)
    private fun rnd(lo: Int, hi: Int): Int = Random.nextInt(lo, hi + 1)

    private fun pickModel(): DemoModel {
        val total = MODELS.sumOf { it.w }
        var r = Random.nextDouble() * total
        for (m in MODELS) {
            r -= m.w
            if (r <= 0) return m
        }
        return MODELS[0]
    }

    private fun makeLog(tsSec: Double): LogEntry {
        val m = pickModel()
        val pt = round(rand(m.ptLo.toDouble(), m.ptHi.toDouble())).toLong()
        val ct = round(rand(m.ctLo.toDouble(), m.ctHi.toDouble())).toLong()
        val cost = rand(m.costLo, m.costHi) * (0.6 + (pt / (m.ptHi * 0.8)))
        val useTime = round(rand(0.4, 9.5) * 10) / 10.0
        return LogEntry(
            type = 2,
            createdAt = floor(tsSec).toLong(),
            modelName = m.name,
            tokenName = TOKEN_NAMES[Random.nextInt(TOKEN_NAMES.size)],
            promptTokens = pt,
            completionTokens = ct,
            quota = max(1.0, round(cost * QPU)),
            useTime = useTime,
            isStream = Random.nextDouble() < 0.85,
            username = "demo_user",
        )
    }

    /** type=5 錯誤記錄：quota=0（唔會入消費）、content 帶錯誤信息（ErrorStats／日誌頁用） */
    private fun makeErrorLog(tsSec: Double): LogEntry {
        val m = pickModel()
        return LogEntry(
            type = 5,
            createdAt = floor(tsSec).toLong(),
            modelName = m.name,
            tokenName = TOKEN_NAMES[Random.nextInt(TOKEN_NAMES.size)],
            promptTokens = 0,
            completionTokens = 0,
            quota = 0.0,
            useTime = round(rand(0.1, 30.0) * 10) / 10.0,
            isStream = false,
            username = "demo_user",
            content = ERROR_MESSAGES[Random.nextInt(ERROR_MESSAGES.size)],
        )
    }

    /** 生成 5 日日誌（即載即有完整數據），再推導餘額 */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        val nowSec = System.currentTimeMillis() / 1000.0
        val todayStart = Fmt.todayStartTs()
        val list = ArrayList<LogEntry>()
        var spend = 0.0

        for (day in 4 downTo 0) {
            val dayBase = todayStart - day * 86400L
            val dayEnd = min(nowSec, (dayBase + 86400L).toDouble())
            val count = if (day == 0) 34 + rnd(0, 7) else 8 + rnd(0, 11)
            repeat(count) {
                val ts: Double = if (day == 0) {
                    // 今日：40% 落喺最近 60 分鐘（保證 feed 有新鮮數據），其餘攤開 12 小時
                    val elapsed = (nowSec - todayStart).coerceAtLeast(1.0)
                    if (Random.nextDouble() < 0.4) {
                        nowSec - rand(0.0, min(3600.0, elapsed * 0.9))
                    } else {
                        nowSec - rand(0.0, min(12 * 3600.0, elapsed * 0.9))
                    }
                } else {
                    rand(dayBase + 8 * 3600.0, dayEnd - 600.0)
                }
                if (ts >= nowSec) return@repeat
                // ~4% 係 type=5 錯誤記錄（quota=0，唔入消費統計）
                val log = if (Random.nextDouble() < ERROR_LOG_RATIO) makeErrorLog(ts) else makeLog(ts)
                list.add(log)
                spend += log.quota ?: 0.0
            }
        }

        list.sortByDescending { it.createdAt }
        logs = list
        // 扣完 seed 消費後收喺 ~$236
        balance = 236.4752 + spend / QPU
        usedTotal = 87.4134 + spend / QPU
        requestCount = 1284L + list.size
        tokens = makeTokens(list)
        channels = makeChannels()
    }

    /** 生成演示令牌：名稱跟 log 池嘅 token_name（再加兩張裝飾卡），狀態／用量隨機合理 */
    private fun makeTokens(seedLogs: List<LogEntry>): List<TokenInfo> {
        val perName = HashMap<String, Double>()
        for (l in seedLogs) {
            val tn = l.tokenName ?: continue
            perName[tn] = (perName[tn] ?: 0.0) + (l.quota ?: 0.0)
        }
        val names = LinkedHashSet<String>()
        names.addAll(TOKEN_NAMES)
        names.addAll(listOf("playground", "sdk-ci"))
        val nowSec = System.currentTimeMillis() / 1000
        return names.mapIndexed { i, name ->
            val used = (perName[name] ?: rand(0.0, 40.0) * QPU) + rand(0.0, 6.0) * QPU
            // 一張無限制、其餘有限額；狀態多數啟用，攤張停用／封禁卡展示徽章
            val unlimited = i == 0
            val status = when {
                i == names.size - 1 -> 3
                i == names.size - 2 -> 2
                else -> 1
            }
            TokenInfo(
                id = 1000L + i,
                name = name,
                key = randomKey(),
                status = status,
                usedQuota = round(used),
                remainQuota = round(rand(40.0, 300.0) * QPU),
                unlimitedQuota = unlimited,
                expiredTime = if (i % 3 == 0) -1L else nowSec + rnd(5, 90) * 86400L,
                createdTime = nowSec - rnd(10, 120) * 86400L,
            )
        }
    }

    /** 生成演示渠道：5 條，status 混合 1/2/3（有 response_time ms，俾渠道頁／統計有嘢睇） */
    private fun makeChannels(): List<ChannelInfo> {
        val names = listOf("openai-primary", "anthropic-fallback", "gemini-cheap", "aws-bedrock", "vertex-turbo")
        // 狀態混合：3 啟用、1 手動停用、1 自動封禁
        val statuses = listOf(1, 1, 2, 3, 1)
        return names.mapIndexed { i, name ->
            ChannelInfo(
                id = 100L + i,
                name = name,
                status = statuses[i],
                type = CHANNEL_TYPES[i % CHANNEL_TYPES.size],
                responseTime = rnd(80, 3200),
            )
        }
    }

    private fun randomKey(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..48).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    /** 每個 tick：大機率入一條新 log（~4% 係錯誤記錄，quota=0 唔扣餘額）；餘額同步扣減 */
    @Synchronized
    fun tick() {
        if (!started) start()
        if (Random.nextDouble() < 0.68) {
            val isError = Random.nextDouble() < ERROR_LOG_RATIO
            val log = if (isError) makeErrorLog(System.currentTimeMillis() / 1000.0) else makeLog(System.currentTimeMillis() / 1000.0)
            logs = listOf(log) + logs
            if (logs.size > 600) logs = logs.take(600)
            requestCount += 1
            if (isError) return
            val q = log.quota ?: 0.0
            balance = max(0.0, balance - q / QPU + 0.0008 * Random.nextDouble())
            usedTotal += q / QPU
        } else {
            // 細微浮動，令趨勢線有呼吸感
            balance = max(0.0, balance - 0.0004 * Random.nextDouble())
        }
    }

    /** 清空 demo 狀態（wipeAll / demo→真實切換用；重入會重新 start） */
    @Synchronized
    fun reset() {
        started = false
        logs = emptyList()
        tokens = emptyList()
        channels = emptyList()
        balance = 0.0
        usedTotal = 87.4134
        requestCount = 1284L
    }

    /* ---------------- 查詢接口 ---------------- */

    @Synchronized
    fun todayLogs(): List<LogEntry> {
        val t0 = Fmt.todayStartTs()
        return logs.filter { it.createdAt >= t0 }
    }

    @Synchronized
    fun monthLogs(): List<LogEntry> {
        val m0 = Fmt.monthStartTs()
        return logs.filter { it.createdAt >= m0 }
    }

    /** queryLogs：日誌頁用（range: 'today' | '7d'，model 篩選，分頁） */
    @Synchronized
    fun queryLogs(range: String, model: String?, page: Int, pageSize: Int): LogPage {
        val start = if (range == "today") Fmt.todayStartTs() else System.currentTimeMillis() / 1000 - 7 * 86400L
        var rows = logs.filter { it.createdAt >= start }
        if (!model.isNullOrEmpty()) rows = rows.filter { it.modelName == model }
        val total = rows.size
        val p = page.coerceAtLeast(1)
        val size = pageSize.coerceAtLeast(1)
        val slice = rows.drop((p - 1) * size).take(size)
        return LogPage(slice, total)
    }

    @Synchronized
    fun distinctModels(): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (l in logs) {
            val m = l.modelName ?: continue
            if (seen.add(m)) out.add(m)
        }
        return out.sorted()
    }

    /** 令牌列表（照 logs 按 created_time 降序？上游慣例 id 排序 — 呢度照 id 升序輸出） */
    @Synchronized
    fun tokens(): List<TokenInfo> {
        if (!started) start()
        return tokens
    }

    /** 切換演示令牌狀態（1=啟用 2=停用）；返回有冇命中 */
    @Synchronized
    fun setTokenStatus(id: Long, status: Int): Boolean {
        val idx = tokens.indexOfFirst { it.id == id }
        if (idx < 0) return false
        tokens = tokens.toMutableList().apply { set(idx, tokens[idx].copy(status = status)) }
        return true
    }

    /** 演示：新增令牌（demo CRUD） */
    @Synchronized
    fun addToken(name: String, remainQuota: Double, unlimited: Boolean, expiredTime: Long): TokenInfo {
        if (!started) start()
        val t = TokenInfo(
            id = (tokens.maxOfOrNull { it.id } ?: 0L) + 1,
            name = name,
            key = randomKey(),
            status = 1,
            usedQuota = 0.0,
            remainQuota = remainQuota,
            unlimitedQuota = unlimited,
            expiredTime = expiredTime,
            createdTime = System.currentTimeMillis() / 1000,
        )
        tokens = listOf(t) + tokens
        return t
    }

    /** 演示：儲存令牌編輯（名稱／額度）；返回有冇命中 */
    @Synchronized
    fun updateTokenFields(id: Long, name: String, remainQuota: Double, unlimited: Boolean): Boolean {
        val idx = tokens.indexOfFirst { it.id == id }
        if (idx < 0) return false
        tokens = tokens.toMutableList().apply {
            set(idx, tokens[idx].copy(name = name, remainQuota = remainQuota, unlimitedQuota = unlimited))
        }
        return true
    }

    /** 演示：刪除令牌；返回有冇命中 */
    @Synchronized
    fun removeToken(id: Long): Boolean {
        val before = tokens.size
        tokens = tokens.filter { it.id != id }
        return tokens.size != before
    }

    /* ---------------- 渠道 / 餘額歷史種子 ---------------- */

    /** 演示渠道列表（5 條，status 混合 1/2/3，response_time ms） */
    @Synchronized
    fun channels(): List<ChannelInfo> {
        if (!started) start()
        return channels
    }

    /** 演示：更新渠道狀態 */
    @Synchronized
    fun updateChannelStatus(id: Long, newStatus: Int): Boolean {
        if (!started) start()
        val idx = channels.indexOfFirst { it.id == id }
        if (idx < 0) return false
        channels = channels.toMutableList().apply {
            set(idx, channels[idx].copy(status = newStatus))
        }
        return true
    }

    /** 演示：測試渠道延遲 */
    @Synchronized
    fun testChannel(id: Long): Int {
        if (!started) start()
        val ms = rnd(80, 2400)
        val idx = channels.indexOfFirst { it.id == id }
        if (idx >= 0) {
            channels = channels.toMutableList().apply {
                set(idx, channels[idx].copy(responseTime = ms))
            }
        }
        return ms
    }

    /** 演示：刪除渠道 */
    @Synchronized
    fun deleteChannel(id: Long): Boolean {
        if (!started) start()
        val before = channels.size
        channels = channels.filter { it.id != id }
        return channels.size != before
    }

    /** 演示：模型測速探針（150ms ~ 950ms 模擬延遲） */
    @Synchronized
    fun pingModel(modelName: String): PingResult {
        if (!started) start()
        val fail = Random.nextDouble() < 0.05
        val ms = (150L + Random.nextLong(800L))
        return if (fail) {
            PingResult(
                ok = false,
                latencyMs = ms,
                model = modelName,
                status = 500,
                message = "模擬上游伺服器逾時",
            )
        } else {
            PingResult(
                ok = true,
                latencyMs = ms,
                model = modelName,
                status = 200,
                message = "完成 (${ms}ms)",
            )
        }
    }

    /**
     * 7 日餘額歷史取樣種子（HistoryStore seeding 用）：每小時一點，帶自然下降曲線，
     * 由 7 日前較高餘額一路跌到當前 balance（帶細噪聲＋偶爾回彈）。instId 做 Random 種子
     * → 同一實例每次生成一致（可重現）。
     */
    @Synchronized
    fun historySeed(instId: String): List<BalanceSample> {
        if (!started) start()
        val now = System.currentTimeMillis()
        val startMs = now - HISTORY_SEED_DAYS * 86_400_000L
        val hours = (HISTORY_SEED_DAYS * 24).toLong()
        // instId hash 做 per-instance 穩定種子（demo 固定 instId → 曲線穩定）
        var seed = 1125899906842597L
        for (c in instId) seed = 31 * seed + c.code
        seed += now / (6 * 3_600_000L) // 每 6 小時換一次形狀，免得永遠同一條
        val rng = Random(seed)
        val current = if (balance > 0) balance else 236.4752
        val startLevel = current * (1.25 + rng.nextDouble() * 0.25)
        val out = ArrayList<BalanceSample>((hours + 1).toInt())
        for (h in 0..hours) {
            val t = startMs + h * 3_600_000L
            val frac = h.toDouble() / hours.toDouble()
            // 自然下降：起點高、終點=當前 balance；疊加正弦波動＋細噪聲
            val base = startLevel + (current - startLevel) * frac
            val wobble = base * 0.008 * kotlin.math.sin(frac * 6.0 * Math.PI + rng.nextDouble())
            val noise = base * (rng.nextDouble() - 0.5) * 0.006
            out.add(BalanceSample(t, max(0.0, base + wobble + noise)))
        }
        // 最新一點精準收喺當前餘額
        out[out.size - 1] = BalanceSample(now, current)
        return out
    }

    /* ---------------- snapshot（同真實數據形狀一致） ---------------- */

    @Synchronized
    fun snapshot(): DashboardSnapshot {
        if (!started) start()
        val nowSec = System.currentTimeMillis() / 1000
        val today = todayLogs()
        val month = monthLogs()
        // 消費統計只食 type=2（type=5 錯誤行 quota=0，唔可以當請求／金額入數）
        val consumeToday = LogMath.filterConsume(today)
        val stats = LogMath.aggregateStats(consumeToday, QPU)
        val week = SpendStats.dailyFromLogs(logs, 7, QPU, System.currentTimeMillis())
        return DashboardSnapshot(
            ok = true,
            demo = true,
            fetchedAtMs = System.currentTimeMillis(),
            name = "演示實例",
            keyOnly = false,
            qpu = QPU,
            status = StatusInfo(
                systemName = "NewAPI Demo Station",
                version = "v0.8.2-demo",
                startTimeSec = nowSec - 3 * 86400L,
                quotaPerUnit = QPU,
            ),
            user = UserInfo(
                id = 1L,
                username = "demo_user",
                displayName = "演示用戶",
                group = "vip",
                quota = round(balance * QPU),
                usedQuota = round(usedTotal * QPU),
                requestCount = requestCount,
            ),
            balanceUsd = balance,
            usedTotalUsd = usedTotal,
            todayUsd = LogMath.sumQuota(consumeToday) / QPU,
            todayTruncated = false,
            todayCount = consumeToday.size,
            monthUsd = LogMath.sumQuota(LogMath.filterConsume(month)) / QPU,
            monthTruncated = false,
            weekDays = week,
            weekTotalUsd = week.sumOf { it.usd },
            weekApprox = false,
            recentLogs = consumeToday.take(10),
            modelStats = stats,
            models = distinctModels(),
            // 穩定性卡／熱力圖：demo 日誌池有 ~4% type=5 錯誤行，統計有嘢示範
            errorStats = ErrorStats.compute(today, System.currentTimeMillis()),
            hourly = Heatmap.compute(month, QPU).byHour.toList(),
            // demo 永遠行 user 模式，keyOnly／billing 分支唔會出現
            billing = null,
            errors = emptyMap(),
        )
    }

    /* ---------------- 個人統計 / 充值 / 訂閱 / 公告 Demo 數據 ---------------- */

    fun notice(): SiteNotice = SiteNotice(
        content = "歡迎使用 NewAPI Monitor 演示模式！所有數據均為本地仿真模擬，不會連線任何伺服器。"
    )

    fun logSelfStat(): LogSelfStat = LogSelfStat(
        quota = round(balance * QPU),
        tokenUsed = round(usedTotal * QPU).toLong(),
        rpm = 12L,
        tpm = 45000L,
    )

    fun dataFlowSelf(): DataFlowSelf = DataFlowSelf(
        users = listOf(FlowItem("demo_user", 1250000.0)),
        models = listOf(
            FlowItem("gpt-4o", 2800000.0),
            FlowItem("claude-3-5-sonnet", 1950000.0),
            FlowItem("gemini-1.5-pro", 850000.0),
        ),
        tokens = listOf(
            FlowItem("default-token", 3200000.0),
            FlowItem("dev-key", 2400000.0),
        ),
        groups = listOf(FlowItem("vip", 5600000.0)),
    )

    fun topUpInfo(): TopUpInfo = TopUpInfo(
        minTopUp = 1.0,
        enableOnlineTopUp = true,
        enableExchangeTopUp = true,
        payMethods = listOf("alipay", "wxpay", "stripe"),
    )

    fun userTopUps(): List<TopUpRecord> = listOf(
        TopUpRecord(101L, 10.0, System.currentTimeMillis() / 1000 - 86400 * 2, "success", "alipay"),
        TopUpRecord(102L, 20.0, System.currentTimeMillis() / 1000 - 86400 * 10, "success", "stripe"),
    )

    fun subscriptionSelf(): UserSubscription = UserSubscription(
        planName = "VIP Pro 年費會員",
        expireTime = System.currentTimeMillis() / 1000 + 86400 * 180,
        status = "active",
    )

    /* ---------------- 管理員 Hub Demo 數據（唯讀模擬） ---------------- */

    /** 模擬全站使用者（admin 檢視）；role 混合 1/10/100，status 混合 1/2 */
    fun adminUsers(): List<AdminUserInfo> = listOf(
        AdminUserInfo(1L, "root", "站長", "default", 100, 1, round(320.5 * QPU), round(1842.2 * QPU), 90_120L),
        AdminUserInfo(2L, "demo_user", "演示用戶", "vip", 10, 1, round(balance * QPU), round(usedTotal * QPU), 1_284L),
        AdminUserInfo(3L, "alice", "Alice", "vip", 1, 1, round(45.2 * QPU), round(112.8 * QPU), 3_420L),
        AdminUserInfo(4L, "bob", "Bob", "default", 1, 2, 0.0, round(9.4 * QPU), 210L),
        AdminUserInfo(5L, "carol", "Carol", "svip", 1, 1, round(210.0 * QPU), round(655.1 * QPU), 12_006L),
    )
}
