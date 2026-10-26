package com.newapimonitor.data.repo

import com.newapimonitor.data.api.ApiException
import com.newapimonitor.data.api.NewApiClient
import com.newapimonitor.data.api.ResponseParsing
import com.newapimonitor.data.api.asJsonObjectOrEmpty
import com.newapimonitor.data.api.doubleOf
import com.newapimonitor.data.api.longOf
import com.newapimonitor.data.api.str
import com.newapimonitor.data.store.InstanceRepository
import com.newapimonitor.demo.DemoEngine
import com.newapimonitor.domain.AppError
import com.newapimonitor.domain.BillingInfo
import com.newapimonitor.domain.ChannelInfo
import com.newapimonitor.domain.DailySpend
import com.newapimonitor.domain.DashboardSnapshot
import com.newapimonitor.domain.DataFlowSelf
import com.newapimonitor.domain.ErrorStats
import com.newapimonitor.domain.Heatmap
import com.newapimonitor.domain.Instance
import com.newapimonitor.domain.LogEntry
import com.newapimonitor.domain.LogSelfStat
import com.newapimonitor.domain.LogMath
import com.newapimonitor.domain.ModelStat
import com.newapimonitor.domain.PingResult
import com.newapimonitor.domain.Quota
import com.newapimonitor.domain.SiteNotice
import com.newapimonitor.domain.SpendCalendar
import com.newapimonitor.domain.SpendStats
import com.newapimonitor.domain.StatusInfo
import com.newapimonitor.domain.TokenInfo
import com.newapimonitor.domain.TopUpInfo
import com.newapimonitor.domain.TopUpRecord
import com.newapimonitor.domain.UserInfo
import com.newapimonitor.domain.UserSubscription
import com.newapimonitor.util.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * 儀表板快照構建（移植 js/app.js buildSnapshot + fetchUsageWindows）：
 *  - demo 模式完全離線（DemoEngine.tick + snapshot），唔會掂網絡
 *  - 真實模式：/api/status（可失敗）→ user / billing 並行 → 日誌窗口
 *  - 只有 API Key（keyOnly）→ 用 subscription+usage 顯示總額／已用（usage 美分 ÷100）
 *  - 用戶數據失敗（401 等）但 billing 有數據 → 降級 keyOnly（餘額 = 總額 − 已用）
 *  - 無 Access Token 或用戶數據失效 → 唔好再打管理 API（日誌/模型），keyOnly 數據照出
 */
class NewApiRepository(val store: InstanceRepository) {

    private val modelsCache = HashMap<String, List<String>>()
    private var monthCache: MonthCache? = null
    private var pollCount = 0

    /** 最後一次成功快照 — 日誌頁讀 qpu，確保同儀表板用同一個換算值 */
    @Volatile
    var lastSnapshot: DashboardSnapshot? = null
        private set

    /** lastSnapshot 所屬嘅數據來源（"demo" 或實例 id）：切實例／demo／wipeAll 之後殘留快照一律作廢 */
    @Volatile
    private var lastSnapshotKey: String? = null

    /**
     * 日誌頁讀快照嘅唯一入口：只有快照真係嚟自呢個數據來源（同一實例／demo）先至可以信 —
     * 否則會攞舊實例嘅 user id 落 New-Api-User header（會被新站點拒絕）、用舊 QPU 換算金額。
     */
    fun snapshotForSource(demo: Boolean, inst: Instance?): DashboardSnapshot? {
        val s = lastSnapshot ?: return null
        val key = if (demo) "demo" else inst?.id ?: return null
        return if (lastSnapshotKey == key) s else null
    }

    private data class MonthCache(val instId: String, val usd: Double, val truncated: Boolean, val fetchedAt: Long, val rows: List<LogEntry>)

    suspend fun buildSnapshot(): DashboardSnapshot {
        val settings = store.settings.value
        if (settings.demo) {
            DemoEngine.tick()
            val snap = DemoEngine.snapshot()
            if (snap.ok) {
                lastSnapshot = snap
                lastSnapshotKey = "demo"
            }
            return snap
        }

        val inst = store.activeInstance.value
            ?: return DashboardSnapshot(ok = false, noInstance = true)
        if (!inst.hasAnyToken) {
            return DashboardSnapshot(ok = false, name = inst.name, noTokens = true)
        }

        val client = NewApiClient(
            baseUrl = inst.baseUrl,
            accessToken = inst.accessToken,
            apiKey = inst.apiKey,
            initialAccessStyle = inst.authStyle,
            initialKeyStyle = inst.authStyleKey,
            quotaPerUnit = inst.quotaPerUnit,
        )
        val errors = HashMap<String, AppError>()

        var status: JsonObject? = null
        try {
            status = client.fetchStatus()
        } catch (e: ApiException) {
            errors["status"] = e.toAppError()
        }
        val qpu = Quota.resolveQpu(inst.quotaPerUnit, status?.doubleOf("quota_per_unit"))

        val wantUser = inst.accessToken.isNotBlank()
        val wantBilling = inst.apiKey.isNotBlank()

        var user: UserInfo? = null
        var billing: BillingInfo? = null
        coroutineScope {
            val userJob = async {
                if (!wantUser) return@async
                try {
                    val u = client.fetchUserSelf().asJsonObjectOrEmpty()
                    client.setUserId(u.longOf("id"))
                    persistAuthStyles(inst, client)
                    user = UserInfo(
                        id = u.longOf("id"),
                        username = u.str("username"),
                        displayName = u.str("display_name") ?: u.str("username"),
                        group = u.str("group"),
                        quota = u.doubleOf("quota") ?: 0.0,
                        usedQuota = u.doubleOf("used_quota") ?: 0.0,
                        requestCount = u.longOf("request_count") ?: 0L,
                    )
                } catch (e: ApiException) {
                    errors["user"] = e.toAppError()
                }
            }
            val billingJob = async {
                if (!wantBilling) return@async
                try {
                    val sub = client.fetchSubscription()
                    val use = client.fetchUsage()
                    persistAuthStyles(inst, client)
                    billing = BillingInfo(
                        hardLimitUsd = sub.doubleOf("hard_limit_usd") ?: 0.0,
                        // usage 單位係美分 → ÷100
                        totalUsageUsd = Quota.usageCentsToUsd(use.doubleOf("total_usage") ?: 0.0),
                    )
                } catch (e: ApiException) {
                    errors["billing"] = e.toAppError()
                }
            }
            userJob.await()
            billingJob.await()
        }

        // 餘額解析（js 同款優先級）
        var balanceUsd: Double? = null
        var usedTotalUsd: Double? = null
        var keyOnly = false
        if (user != null) {
            balanceUsd = user.quota / qpu
            usedTotalUsd = user.usedQuota / qpu
        }
        if (!wantUser) keyOnly = true
        if (billing != null && (balanceUsd == null || !balanceUsd.isFinite())) {
            // 降級：用戶數據拿唔到（401/網絡）但 billing 有數據 → 按 keyOnly 顯示
            keyOnly = true
            balanceUsd = billing.hardLimitUsd - billing.totalUsageUsd
        }

        var todayUsd: Double? = null
        var todayTruncated = false
        var todayCount = 0
        var monthUsd: Double? = null
        var monthTruncated = false
        var monthRows: List<LogEntry> = emptyList()
        var weekDays: List<DailySpend> = emptyList()
        var weekApprox = false
        var recentLogs: List<LogEntry> = emptyList()
        var modelStats: List<ModelStat> = emptyList()
        var models: List<String> = emptyList()
        var todayErrors: ErrorStats.Stats? = null

        // 無 Access Token（或已證實失效）→ 唔好再打管理 API（日誌/模型），keyOnly 數據照出
        val canFetchLogs = wantUser && user != null
        if (canFetchLogs) {
            // 模型列表：每 session 抓一次
            models = modelsCache[inst.id] ?: run {
                try {
                    client.fetchUserModels().also { modelsCache[inst.id] = it }
                } catch (e: ApiException) {
                    emptyList()
                }
            }

            val nowSec = System.currentTimeMillis() / 1000

            // 今日＋今月窗口並行抓（原本串聯等兩層，RTT 高嘅站首屏慢一倍）
            coroutineScope {
                val todayJob = async {
                    try {
                        val w = client.fetchLogWindow(startTs = Fmt.todayStartTs(), endTs = nowSec, maxPages = 2)
                        // raw 行（消費＋type=5 錯誤齊上）先計錯誤統計，再過濾消費行
                        val raw = w.rows.mapNotNull { ResponseParsing.parseLogEntry(it) }
                        val rows = LogMath.filterConsume(raw)
                        todayUsd = LogMath.sumQuota(rows) / qpu
                        todayTruncated = w.truncated
                        todayCount = rows.size
                        recentLogs = rows.take(10)
                        modelStats = LogMath.aggregateStats(rows, qpu)
                        todayErrors = ErrorStats.compute(raw, System.currentTimeMillis())
                    } catch (e: ApiException) {
                        errors["logs"] = e.toAppError()
                    }
                }
                // 今月窗口：首輪必抓，之後 5 分鐘內且非每 8 個 tick 重抓（js 同款快取）
                val monthJob = async {
                    val cached = monthCache
                    if (cached != null && cached.instId == inst.id &&
                        System.currentTimeMillis() - cached.fetchedAt < 5 * 60_000L &&
                        pollCount % 8 != 0
                    ) {
                        monthUsd = cached.usd
                        monthTruncated = cached.truncated
                        monthRows = cached.rows
                    } else {
                        try {
                            val w = client.fetchLogWindow(startTs = Fmt.monthStartTs(), endTs = nowSec, maxPages = 3)
                            val rows = LogMath.filterConsume(w.rows.mapNotNull { ResponseParsing.parseLogEntry(it) })
                            monthUsd = LogMath.sumQuota(rows) / qpu
                            monthTruncated = w.truncated
                            monthRows = rows
                            monthCache = MonthCache(inst.id, monthUsd ?: 0.0, monthTruncated, System.currentTimeMillis(), rows)
                        } catch (e: ApiException) {
                            if (!errors.containsKey("logs")) errors["logs2"] = e.toAppError()
                        }
                    }
                }
                todayJob.await()
                monthJob.await()
            }

            // 7 日消費：優先 /api/data/self 數據看板；404／失敗／空 → fallback 用今月窗口日誌本地聚日
            try {
                val d = client.fetchDataSelf(startTs = Fmt.todayStartTs() - 6 * 86400L, endTs = nowSec)
                val points = ResponseParsing.pickLogItems(d)
                    .mapNotNull { ResponseParsing.parseUsagePoint(it) }
                if (points.isNotEmpty()) {
                    weekDays = SpendStats.dailyFromPoints(
                        points.map { SpendStats.Point(it.tsSec, it.quota) },
                        days = 7,
                        qpu = qpu,
                        nowMs = System.currentTimeMillis(),
                    )
                }
            } catch (e: ApiException) {
                // 舊版站點冇呢個端點（404）等 — 靜默 fallback，唔好為統計卡加錯誤 banner
            }
            if (weekDays.isEmpty() && monthRows.isNotEmpty()) {
                weekDays = SpendStats.dailyFromLogs(monthRows, 7, qpu, System.currentTimeMillis())
                weekApprox = monthTruncated
            }
        }
        pollCount += 1

        val snap = DashboardSnapshot(
            ok = balanceUsd != null && balanceUsd.isFinite(),
            demo = false,
            name = inst.name,
            keyOnly = keyOnly,
            fetchedAtMs = System.currentTimeMillis(),
            qpu = qpu,
            status = status?.let {
                StatusInfo(
                    systemName = it.str("system_name"),
                    version = it.str("version"),
                    startTimeSec = it.longOf("start_time"),
                    quotaPerUnit = it.doubleOf("quota_per_unit"),
                )
            },
            user = user,
            billing = billing,
            balanceUsd = balanceUsd,
            usedTotalUsd = usedTotalUsd,
            todayUsd = todayUsd,
            todayTruncated = todayTruncated,
            todayCount = todayCount,
            monthUsd = monthUsd,
            monthTruncated = monthTruncated,
            weekDays = weekDays,
            weekTotalUsd = if (weekDays.isNotEmpty()) weekDays.sumOf { it.usd } else null,
            weekApprox = weekApprox,
            recentLogs = recentLogs,
            modelStats = modelStats,
            models = models,
            errorStats = todayErrors,
            hourly = Heatmap.compute(monthRows, qpu).byHour.toList(),
            errors = errors.toMap(),
        )
        if (snap.ok) {
            lastSnapshot = snap
            lastSnapshotKey = inst.id
        }
        return snap
    }

    /* ---------------- 日誌頁 / 模型篩選 ---------------- */

    data class LogsPage(val rows: List<LogEntry>, val total: Long?, val rawCount: Int)

    /* ---------------- 令牌頁 ---------------- */

    data class TokensPage(val tokens: List<TokenInfo>, val truncated: Boolean)

    /**
     * 令牌列表：GET /api/token/?p=1&size=100（Access Token 管理 API）。
     * 回應多過 100 張 → truncated（列表只顯示首 100 張）。
     */
    suspend fun fetchTokensPage(inst: Instance, p: Int = 1, size: Int = 100, keyword: String = ""): TokensPage {
        val client = clientFor(inst)
        snapshotForSource(demo = false, inst)?.user?.id?.let { client.setUserId(it) }
        val data = client.fetchTokens(p = p, size = size, keyword = keyword)
        val objs = ResponseParsing.pickTokenItems(data)
        val tokens = objs.mapNotNull { ResponseParsing.parseToken(it) }
        return TokensPage(tokens, truncated = objs.size >= size)
    }

    /** Every status change fetches fresh detail and overlays status; never PUT a list row. */
    suspend fun setTokenStatus(inst: Instance, id: Long, status: Int) {
        require(status == 1 || status == 2)
        val client = clientFor(inst)
        snapshotForSource(demo = false, inst)?.user?.id?.let { client.setUserId(it) }
        client.updateToken(id, mapOf("status" to JsonPrimitive(status)))
    }

    suspend fun setTokenStatus(inst: Instance, token: TokenInfo, newStatus: Int) =
        setTokenStatus(inst, token.id, newStatus)

    suspend fun revealTokenKey(inst: Instance, id: Long): String {
        val client = clientFor(inst)
        snapshotForSource(demo = false, inst)?.user?.id?.let { client.setUserId(it) }
        return client.revealTokenKey(id)
    }

    /** 新增令牌：POST /api/token/（name 必填；remainQuota 係 raw quota 單位；expiredTime -1 = 永不過期） */
    suspend fun createToken(
        inst: Instance,
        name: String,
        remainQuota: Long,
        unlimited: Boolean,
        expiredTime: Long = -1L,
        group: String = "",
        modelLimitsEnabled: Boolean = false,
        modelLimits: String = "",
        allowIps: String = "",
    ) {
        val client = clientFor(inst)
        snapshotForSource(demo = false, inst)?.user?.id?.let { client.setUserId(it) }
        client.createToken(name, remainQuota, unlimited, expiredTime, group, modelLimitsEnabled, modelLimits, allowIps)
    }

    /** Edit only form fields onto freshly fetched complete detail. Never overlay stale status or key. */
    suspend fun saveTokenEdits(inst: Instance, token: TokenInfo, changes: Map<String, JsonPrimitive>) {
        val client = clientFor(inst)
        snapshotForSource(demo = false, inst)?.user?.id?.let { client.setUserId(it) }
        client.updateToken(token.id, changes)
    }

    /** 刪除令牌：DELETE /api/token/{id} */
    suspend fun deleteToken(inst: Instance, id: Long) {
        val client = clientFor(inst)
        snapshotForSource(demo = false, inst)?.user?.id?.let { client.setUserId(it) }
        client.deleteToken(id)
    }

    /** 全站使用者一頁（GET /api/user/，admin 唯讀）；401/403 原樣擲 → UI 出權限提示 */
    suspend fun fetchAdminUsersPage(inst: Instance, p: Int = 1, size: Int = 100, keyword: String = ""): AdminUsersPage {
        val client = clientFor(inst)
        snapshotForSource(demo = false, inst)?.user?.id?.let { client.setUserId(it) }
        val data = client.fetchUsers(p = p, size = size, keyword = keyword)
        val objs = ResponseParsing.pickUserItems(data)
        val users = objs.mapNotNull { obj -> (obj as? JsonObject)?.let { ResponseParsing.parseAdminUser(it) } }
        return AdminUsersPage(users, truncated = objs.size >= size)
    }

    data class AdminUsersPage(val users: List<com.newapimonitor.domain.AdminUserInfo>, val truncated: Boolean)

    /** 全站日誌一頁（GET /api/log/，admin 唯讀）；行解析同 /api/log/self 完全同款 */
    suspend fun fetchAdminLogsPage(inst: Instance, p: Int = 1, pageSize: Int = 100): AdminLogsPage {
        val client = clientFor(inst)
        snapshotForSource(demo = false, inst)?.user?.id?.let { client.setUserId(it) }
        val data = client.fetchAllLogs(p = p, pageSize = pageSize)
        val objs = ResponseParsing.pickLogItems(data)
        val rows = objs.mapNotNull { ResponseParsing.parseLogEntry(it) }
        return AdminLogsPage(rows, truncated = objs.size >= pageSize)
    }

    data class AdminLogsPage(val rows: List<LogEntry>, val truncated: Boolean)

    data class MonthLogsPage(val rows: List<LogEntry>, val truncated: Boolean)

    /**
     * 本月日誌窗口（GET /api/log/self，per-user 端點唔使 admin）：令牌燒錢榜用。
     * 最多 maxPages × pageSize 行（預設 3×100 = 300）；撞頂 → truncated（統計只係約數）。
     */
    suspend fun monthLogsPage(inst: Instance, maxPages: Int = 3, pageSize: Int = 100): MonthLogsPage {
        val client = clientFor(inst)
        // 儀表板攞到嘅 user id 帶過去 → New-Api-User header 兼容新版站點（同 queryLogsPage 同款）
        snapshotForSource(demo = false, inst)?.user?.id?.let { client.setUserId(it) }
        val nowMs = System.currentTimeMillis()
        val w = client.fetchLogWindow(
            startTs = Fmt.monthStartTs(nowMs),
            endTs = nowMs / 1000,
            maxPages = maxPages,
            pageSize = pageSize,
        )
        val rows = LogMath.filterConsume(w.rows.mapNotNull { ResponseParsing.parseLogEntry(it) })
        return MonthLogsPage(rows, w.truncated)
    }

    /* ---------------- 月度報告 ---------------- */

    /**
     * 月度報告數據包（消費日曆＋月度統計用）：
     *  - thisMonth / lastMonth：每日 USD（GET /api/data/self 個人數據看板，同 AlertWorker 7 日消費同一端點）
     *  - monthLogs：報告統計用本月日誌（monthLogsPage，consume 過濾後；最多 maxPages×100 行）
     *  - lastMonthFetched：上月窗口真係抓到（false = 端點失敗 → UI 顯示「上月無數據」收起對比）
     *  - /api/data/self 失敗（舊站 404 等）：本月 fallback 用日誌本地聚日；上月冇 fallback → 空列表
     *  - 日誌窗口失敗：monthLogs 空 + logsTruncated=false（統計／燒錢榜卡收起），絕不炸
     */
    data class MonthDailyTotals(
        val thisMonth: List<SpendCalendar.DailyUsd>,
        val lastMonth: List<SpendCalendar.DailyUsd>,
        val lastMonthFetched: Boolean,
        val qpu: Double,
        val monthLogs: List<LogEntry>,
        val logsTruncated: Boolean,
    )

    suspend fun monthDailyTotals(inst: Instance, maxPages: Int = 10): MonthDailyTotals {
        val zone = ZoneId.systemDefault()
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        val thisYm = YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(zone))
        val lastYm = thisYm.minusMonths(1)
        val thisStart = thisYm.atDay(1).atStartOfDay(zone).toEpochSecond()
        val lastStart = lastYm.atDay(1).atStartOfDay(zone).toEpochSecond()

        val client = clientFor(inst)
        // 儀表板攞到嘅 user id 帶過去 → New-Api-User header 兼容新版站點（同 monthLogsPage 同款）
        snapshotForSource(demo = false, inst)?.user?.id?.let { client.setUserId(it) }

        // qpu：同源快照優先（同儀表板同一換算）→ /api/status（可失敗）→ 設定值 → 預設
        var qpu = snapshotForSource(demo = false, inst)?.qpu ?: 0.0
        if (!(qpu.isFinite() && qpu > 0)) {
            val status = try {
                client.fetchStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                null
            }
            qpu = Quota.resolveQpu(inst.quotaPerUnit, status?.doubleOf("quota_per_unit"))
        }

        // 本月日誌（統計／燒錢榜用）— 失敗靜默，唔好拖垮日曆
        val logsPage = try {
            monthLogsPage(inst, maxPages = maxPages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MonthLogsPage(emptyList(), truncated = false)
        }

        // 本月＋上月看板窗口並行抓
        var thisPoints: List<SpendStats.Point>? = null
        var lastPoints: List<SpendStats.Point>? = null
        coroutineScope {
            val thisJob = async {
                try {
                    thisPoints = dataSelfPoints(client, thisStart, nowSec)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: ApiException) {
                    // fallback：下面用本月日誌本地聚日
                }
            }
            val lastJob = async {
                try {
                    lastPoints = dataSelfPoints(client, lastStart, thisStart - 1)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: ApiException) {
                    // 舊站冇呢個端點 → 上月對比收起
                }
            }
            thisJob.await()
            lastJob.await()
        }

        val thisDaily = thisPoints?.let { SpendCalendar.dailyTotals(it, qpu) }
            ?: SpendCalendar.dailyTotals(
                logsPage.rows.mapNotNull { r -> r.quota?.let { SpendStats.Point(r.createdAt, it) } },
                qpu,
            )
        return MonthDailyTotals(
            thisMonth = thisDaily,
            lastMonth = lastPoints?.let { SpendCalendar.dailyTotals(it, qpu) } ?: emptyList(),
            lastMonthFetched = lastPoints != null,
            qpu = qpu,
            monthLogs = logsPage.rows,
            logsTruncated = logsPage.truncated,
        )
    }

    /** /api/data/self 一個窗口 → usage points（created_at 秒 + raw quota） */
    private suspend fun dataSelfPoints(client: NewApiClient, startTs: Long, endTs: Long): List<SpendStats.Point> =
        ResponseParsing.pickLogItems(client.fetchDataSelf(startTs, endTs))
            .mapNotNull { ResponseParsing.parseUsagePoint(it) }

    /* ---------------- 渠道頁（admin 管理 API） ---------------- */

    data class ChannelsPage(val channels: List<ChannelInfo>, val truncated: Boolean)

    /**
     * 渠道列表：GET /api/channel/?p=1&size=100（管理 API，要 admin 權限；
     * 冇權限會擲 ApiException，由呼叫方決定點顯示）。
     */
    suspend fun fetchChannels(inst: Instance): ChannelsPage {
        val client = clientFor(inst)
        snapshotForSource(demo = false, inst)?.user?.id?.let { client.setUserId(it) }
        val data = client.fetchChannels(p = 1, size = 100)
        val objs = ResponseParsing.pickChannelItems(data)
        val channels = objs.mapNotNull { ResponseParsing.parseChannel(it) }
        return ChannelsPage(channels, truncated = objs.size >= 100)
    }

    /** 切換渠道狀態（1=啟用 2=手動停用）：使用專用 POST /api/channel/{id}/status，絕不 PUT 縮水配置。 */
    suspend fun updateChannelStatus(inst: Instance, channel: ChannelInfo, newStatus: Int) {
        val client = clientFor(inst)
        snapshotForSource(demo = false, inst)?.user?.id?.let { client.setUserId(it) }
        client.updateChannelStatus(channel, newStatus)
    }

    /** 測試單個渠道連通與延遲（回傳毫秒數） */
    suspend fun testChannel(inst: Instance, id: Long): Int {
        val client = clientFor(inst)
        snapshotForSource(demo = false, inst)?.user?.id?.let { client.setUserId(it) }
        val data = client.testChannel(id)
        val response = data as? JsonObject ?: error("渠道測試冇返回延遲數據")
        return response.doubleOf("response_time")?.toInt()
            ?: response.doubleOf("time")?.let { (it * 1000).toInt() }
            ?: error("渠道測試冇返回延遲數據")
    }

    /** 刪除渠道 */
    suspend fun deleteChannel(inst: Instance, id: Long) {
        val client = clientFor(inst)
        snapshotForSource(demo = false, inst)?.user?.id?.let { client.setUserId(it) }
        client.deleteChannel(id)
    }

    /** 測試模型真實 RTT 延遲 */
    suspend fun pingModel(inst: Instance, modelName: String): PingResult {
        val client = clientFor(inst)
        return client.pingModel(modelName)
    }

    /** 模型篩選選項：session 快取（js modelsCache 同款） */
    suspend fun modelsForFilter(inst: Instance?): List<String> {
        val i = inst ?: return emptyList()
        if (i.accessToken.isBlank()) return emptyList()
        modelsCache[i.id]?.let { return it }
        return try {
            val client = clientFor(i)
            val m = client.fetchUserModels()
            modelsCache[i.id] = m
            m
        } catch (e: ApiException) {
            emptyList()
        }
    }

    /**
     * 日誌頁分頁查詢：帶 type=2 參數 + 本地再過濾一次（雙保險，充值/系統記錄絕不出現）。
     */
    suspend fun queryLogsPage(
        inst: Instance,
        page: Int,
        pageSize: Int,
        startTs: Long,
        endTs: Long,
        model: String?,
    ): LogsPage {
        val client = clientFor(inst)
        // 儀表板攞到嘅 user id 帶過去 → New-Api-User header 兼容新版站點
        // 快照必須嚟自同一實例：切實例後殘留快照嘅 user id 唔可以發去第個站
        snapshotForSource(demo = false, inst)?.user?.id?.let { client.setUserId(it) }
        val data = client.fetchLogs(
            p = page,
            pageSize = pageSize,
            startTs = startTs,
            endTs = endTs,
            modelName = model?.takeIf { it.isNotBlank() },
            type = 2,
        )
        val objs = ResponseParsing.pickLogItems(data)
        val entries = LogMath.filterConsume(objs.mapNotNull { ResponseParsing.parseLogEntry(it) })
            .sortedByDescending { it.createdAt }
        return LogsPage(entries, ResponseParsing.parseLogTotal(data), objs.size)
    }

    /**
     * 今日錯誤行（type=5）：日誌頁「錯誤分析」卡用 — 輕量窗口（最多 maxPages×pageSize 行）。
     * 唔做 filterConsume，本地只留 type=5（同儀表板 ErrorStats 口徑一致）；
     * 失敗擲 ApiException 由呼叫方靜默處理（卡片收起，唔好拖垮日誌列表）。
     */
    suspend fun todayErrorLogs(inst: Instance, maxPages: Int = 2, pageSize: Int = 100): List<LogEntry> {
        val client = clientFor(inst)
        // 儀表板攞到嘅 user id 帶過去 → New-Api-User header 兼容新版站點（同 queryLogsPage 同款）
        snapshotForSource(demo = false, inst)?.user?.id?.let { client.setUserId(it) }
        val nowMs = System.currentTimeMillis()
        val w = client.fetchLogWindow(
            startTs = Fmt.todayStartTs(nowMs),
            endTs = nowMs / 1000,
            maxPages = maxPages,
            pageSize = pageSize,
        )
        return w.rows.mapNotNull { ResponseParsing.parseLogEntry(it) }
            .filter { it.type == ErrorStats.LOG_TYPE_ERROR }
    }

    /** 站點公告（/api/notice） */
    suspend fun fetchNotice(inst: Instance): SiteNotice {
        val client = clientFor(inst)
        val text = ResponseParsing.parseNotice(client.fetchNotice())
        return SiteNotice(content = text)
    }

    /** 個人流量與即時 RPM/TPM 統計（/api/log/self/stat） */
    suspend fun fetchLogSelfStat(inst: Instance): LogSelfStat {
        val client = clientFor(inst)
        snapshotForSource(demo = false, inst)?.user?.id?.let { client.setUserId(it) }
        return ResponseParsing.parseLogSelfStat(client.fetchLogSelfStat())
    }

    /** 個人多維度流量統計（/api/data/flow/self），30天窗口保護 */
    suspend fun fetchDataFlowSelf(inst: Instance, startTs: Long, endTs: Long): DataFlowSelf {
        val client = clientFor(inst)
        snapshotForSource(demo = false, inst)?.user?.id?.let { client.setUserId(it) }
        return ResponseParsing.parseDataFlowSelf(client.fetchDataFlowSelf(startTs, endTs))
    }

    /** 充值方式及限制資訊（/api/user/topup/info） */
    suspend fun fetchTopUpInfo(inst: Instance): TopUpInfo {
        val client = clientFor(inst)
        snapshotForSource(demo = false, inst)?.user?.id?.let { client.setUserId(it) }
        return ResponseParsing.parseTopUpInfo(client.fetchTopUpInfo())
    }

    /** 個人充值記錄（/api/user/topup/self） */
    suspend fun fetchUserTopUps(inst: Instance): List<TopUpRecord> {
        val client = clientFor(inst)
        snapshotForSource(demo = false, inst)?.user?.id?.let { client.setUserId(it) }
        return ResponseParsing.parseTopUpRecords(client.fetchUserTopUps())
    }

    /** 個人訂閱資訊（/api/subscription/self） */
    suspend fun fetchSubscriptionSelf(inst: Instance): UserSubscription? {
        val client = clientFor(inst)
        snapshotForSource(demo = false, inst)?.user?.id?.let { client.setUserId(it) }
        return ResponseParsing.parseUserSubscription(client.fetchSubscriptionSelf())
    }

    private suspend fun persistAuthStyles(inst: Instance, client: NewApiClient) {
        val access = client.currentAccessStyle()
        val key = client.currentKeyStyle()
        if ((access != null && access != inst.authStyle) || (key != null && key != inst.authStyleKey)) {
            store.updateAuthStyles(inst.id, access, key)
        }
    }

    private fun clientFor(inst: Instance): NewApiClient = NewApiClient(
        baseUrl = inst.baseUrl,
        accessToken = inst.accessToken,
        apiKey = inst.apiKey,
        initialAccessStyle = inst.authStyle,
        initialKeyStyle = inst.authStyleKey,
        quotaPerUnit = inst.quotaPerUnit,
    )
}
