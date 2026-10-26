package com.newapimonitor.domain

/** 一個 NewAPI 實例（對應 store.js sanitizeInstance 嘅形狀） */
data class Instance(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val accessToken: String = "",
    val apiKey: String = "",
    val quotaPerUnit: Double? = null,
    val authStyle: AuthStyle? = null,
    val authStyleKey: AuthStyle? = null,
    val createdAt: Long = 0,
) {
    val hasAnyToken: Boolean get() = accessToken.isNotBlank() || apiKey.isNotBlank()
}

/** 全域設定（對應 store.js settings） */
data class AppSettings(
    val activeId: String? = null,
    val intervalSec: Int = 15,
    val demo: Boolean = false,
) {
    companion object {
        val VALID_INTERVALS = listOf(10, 15, 30, 60)
    }
}

/**
 * 一筆日誌（寬鬆解析：缺欄位用 null/0，type 過濾交俾 LogMath.filterConsume）。
 * 只有「消費記錄」（type==2 且帶 model_name + quota）會渲染到任何畫面；
 * type==5 係錯誤記錄（ErrorStats 用），content 帶上游錯誤信息。
 */
data class LogEntry(
    val type: Int? = null,
    val createdAt: Long = 0,
    val modelName: String? = null,
    val tokenName: String? = null,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val quota: Double? = null,
    val useTime: Double = 0.0,
    val isStream: Boolean = false,
    val username: String? = null,
    val content: String? = null,
)

/** 按模型聚合嘅統計（js aggregateStats 輸出） */
data class ModelStat(
    val model: String,
    val requests: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val quota: Double,
    val usd: Double,
) {
    val tokens: Long get() = promptTokens + completionTokens
}

data class UserInfo(
    val id: Long?,
    val username: String?,
    val displayName: String?,
    val group: String?,
    val quota: Double,
    val usedQuota: Double,
    val requestCount: Long,
)

/** billing 端點（/v1/dashboard/billing/ 下）— 已換算成美元（usage 美分 ÷100） */
data class BillingInfo(
    val hardLimitUsd: Double,
    val totalUsageUsd: Double,
)

data class StatusInfo(
    val systemName: String?,
    val version: String?,
    val startTimeSec: Long?,
    val quotaPerUnit: Double?,
)

/**
 * 儀表板快照（形狀對應網頁版 buildSnapshot / DemoEngine.snapshot，渲染層零差別）。
 * errors 鍵：status / user / billing / logs / logs2 / fatal。
 */
data class DashboardSnapshot(
    val ok: Boolean = false,
    val demo: Boolean = false,
    val name: String? = null,
    val keyOnly: Boolean = false,
    val noInstance: Boolean = false,
    val noTokens: Boolean = false,
    val fetchedAtMs: Long = 0,
    val qpu: Double = Quota.DEFAULT_QPU,
    val status: StatusInfo? = null,
    val user: UserInfo? = null,
    val billing: BillingInfo? = null,
    val balanceUsd: Double? = null,
    val usedTotalUsd: Double? = null,
    val todayUsd: Double? = null,
    val todayTruncated: Boolean = false,
    val todayCount: Int = 0,
    val monthUsd: Double? = null,
    val monthTruncated: Boolean = false,
    /** 最近 7 日每日消費（固定 7 槽位舊→新，缺日補 0；空 = 今輪無數據，卡面要隱藏） */
    val weekDays: List<DailySpend> = emptyList(),
    val weekTotalUsd: Double? = null,
    /** week 數據來自本地日誌 fallback 且窗口被分頁封頂 → 顯示「約」 */
    val weekApprox: Boolean = false,
    val recentLogs: List<LogEntry> = emptyList(),
    val modelStats: List<ModelStat> = emptyList(),
    val models: List<String> = emptyList(),
    /** 錯誤統計（今日窗口 raw rows 計，type=5；null = 今輪無日誌數據）— 穩定性卡用 */
    val errorStats: ErrorStats.Stats? = null,
    /** 時段熱力圖（今月窗口 raw quota ÷ qpu，24 格裝置時區 hour-of-day；空 = 無數據） */
    val hourly: List<Double> = emptyList(),
    val errors: Map<String, AppError> = emptyMap(),
) {
    /** 錯誤優先級（js firstError：user → billing → logs → status → fatal） */
    fun primaryError(): AppError? =
        errors["user"] ?: errors["billing"] ?: errors["logs"] ?: errors["status"] ?: errors["fatal"]
}

/** 日誌頁一頁結果 */
data class LogPage(
    val rows: List<LogEntry>,
    val total: Int,
)

/**
 * 一張 API 令牌（/api/token/ 寬鬆解析）：status 用上游數字（1=已啟用 2=手動停用 3=自動封禁），
 * expiredTime -1 = 永不過期；列表 key 絕不儲存（需用戶明確點擊後另行 reveal）。
 */
data class TokenInfo(
    val id: Long = 0,
    val name: String = "",
    val key: String = "",
    val status: Int = 0,
    val usedQuota: Double = 0.0,
    val remainQuota: Double = 0.0,
    val unlimitedQuota: Boolean = false,
    val expiredTime: Long = -1,
    val createdTime: Long = 0,
    val group: String = "",
    val modelLimitsEnabled: Boolean = false,
    val modelLimits: String = "",
    val allowIps: String = "",
) {
    /** 已用（USD）— quota ÷ qpu，同儀表板同一換算 */
    fun usedUsd(qpu: Double): Double = Quota.toUsd(usedQuota, qpu)

    /** 剩餘（USD）；unlimited 時返回 null（顯示「無限制」） */
    fun remainUsd(qpu: Double): Double? = if (unlimitedQuota) null else Quota.toUsd(remainQuota, qpu)
}

/** 一日消費（UTC 秒當地日 0 點 + 該日 USD 總額）— 7 日消費卡用 */
data class DailySpend(
    val dayStartSec: Long,
    val usd: Double,
)

/**
 * 一個餘額取樣點（HistoryStore 持久化）：t = epoch millis、v = USD。
 * 同一分鐘內多個取樣會合併成最後一個（見 HistoryStore.mergeAndTrim）。
 */
data class BalanceSample(
    val t: Long,
    val v: Double,
)

/**
 * 一條渠道（GET /api/channel/?p=1&size=100 管理 API，要 admin 權限）：
 * status 用上游數字（1=啟用 2=手動停用 3=自動封禁）；responseTime 係 ms。
 */
data class ChannelInfo(
    val id: Long = 0,
    val name: String = "",
    val status: Int = 0,
    val type: Int = 0,
    val responseTime: Int? = null,
    /** 配置欄位只供讀取；修改需完整且安全嘅渠道編輯 API，唔可以用狀態端點覆寫。 */
    val priority: Long? = null,
    val weight: Long? = null,
) {
    val statusLabel: String
        get() = when (status) {
            1 -> "啟用"
            2 -> "手動停用"
            3 -> "自動封禁"
            else -> "未知"
        }
}

/** 測速探針結果 */
data class PingResult(
    val ok: Boolean,
    val latencyMs: Long,
    val model: String,
    val status: Int = 0,
    val message: String = "",
)

/** 警報設定（全域，AlertSettingsStore 持久化到 DataStore） */
data class AlertSettings(
    val enabled: Boolean = false,
    val balanceBelowUsd: Double? = null,
    val dailySpendAboveUsd: Double? = null,
    /** 今日消費速度外推 ≥ 過去日均 × 呢個倍數就報 spike；0 = 關閉 */
    val spikeMultiplier: Double = 3.0,
    /** 同類警報最少隔幾多分鐘先可以再響 */
    val cooldownMinutes: Int = 60,
    /** 令牌被自動封禁（status==3）時通知 */
    val notifyTokenBanned: Boolean = true,
    /** Monthly USD budget; null disables both 90% and 100% alerts. */
    val monthlyBudgetUsd: Double? = null,
    val notifyErrorRate: Boolean = false,
    val notifyTokenExpiry: Boolean = false,
    val persistentStatus: Boolean = false,
)

/** 站點公告（GET /api/notice），帶 Markdown / HTML 內容 */
data class SiteNotice(
    val content: String = "",
)

/**
 * 全站使用者（GET /api/user/，admin 唯讀監察）：
 * role：1=普通 10=管理 100=Root；status：1=啟用 2=停用。quota 原始單位，顯示由 UI 按 qpu 換算。
 */
data class AdminUserInfo(
    val id: Long = 0,
    val username: String = "",
    val displayName: String = "",
    val group: String = "",
    val role: Int = 0,
    val status: Int = 0,
    val quota: Double = 0.0,
    val usedQuota: Double = 0.0,
    val requestCount: Long = 0,
) {
    val roleLabel: String
        get() = when {
            role >= 100 -> "Root"
            role >= 10 -> "管理員"
            else -> "用戶"
        }
}

/** 個人實時流量統計（GET /api/log/self/stat） */
data class LogSelfStat(
    val quota: Double = 0.0,
    val tokenUsed: Long = 0L,
    val rpm: Long = 0L,
    val tpm: Long = 0L,
)

/** 流量維度項目（GET /api/data/flow/self 內部元素） */
data class FlowItem(
    val name: String,
    val value: Double,
)

/** 個人多維度流量統計（GET /api/data/flow/self） */
data class DataFlowSelf(
    val users: List<FlowItem> = emptyList(),
    val models: List<FlowItem> = emptyList(),
    val tokens: List<FlowItem> = emptyList(),
    val groups: List<FlowItem> = emptyList(),
)

/** 充值方式資訊（GET /api/user/topup/info） */
data class TopUpInfo(
    val minTopUp: Double? = null,
    val enableOnlineTopUp: Boolean = false,
    val enableExchangeTopUp: Boolean = false,
    val payMethods: List<String> = emptyList(),
)

/** 個人充值記錄條目（GET /api/user/topup/self） */
data class TopUpRecord(
    val id: Long = 0L,
    val amount: Double = 0.0,
    val createTime: Long = 0L,
    val status: String = "",
    val payType: String = "",
)

/** 個人訂閱/方案資訊（GET /api/subscription/self） */
data class UserSubscription(
    val planName: String = "",
    val expireTime: Long = -1L,
    val status: String = "",
)

