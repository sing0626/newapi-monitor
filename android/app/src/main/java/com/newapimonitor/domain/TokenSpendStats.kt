package com.newapimonitor.domain

/**
 * 令牌燒錢榜（本月每個令牌消費聚合，純 JVM 可測）：
 *  - 輸入本月日誌（/api/log/self 或 DemoEngine.monthLogs 嘅 LogEntry 列表）
 *  - 內部先過 LogMath.filterConsume（type=2 消費行），充值／系統／錯誤行一律唔計
 *  - 跳過 tokenName 空白行 → 按 tokenName 聚合 sum(quota) ÷ qpu（USD）＋ 調用次數
 *  - usd 降序取前 TOP_N（5）；truncated 係抓取窗口撞 maxPages 封頂嘅透傳（UI 顯示「（約）」）
 */
object TokenSpendStats {

    /** 一個令牌嘅本月消費（USD） */
    data class TokenSpend(
        val name: String,
        val usd: Double,
        val calls: Int,
    )

    data class Result(
        val rows: List<TokenSpend>,
        /** 抓取窗口撞到 maxPages 封頂 → 數據只係約數 */
        val truncated: Boolean,
    )

    const val TOP_N = 5

    fun compute(logs: List<LogEntry>, qpu: Double, truncated: Boolean = false): Result {
        val safeQpu = if (qpu.isFinite() && qpu > 0) qpu else Quota.DEFAULT_QPU
        val rows = LogMath.filterConsume(logs)
        class Acc {
            var calls = 0
            var quota = 0.0
        }
        val acc = HashMap<String, Acc>()
        for (r in rows) {
            val name = r.tokenName?.trim().orEmpty()
            if (name.isEmpty()) continue
            val a = acc.getOrPut(name) { Acc() }
            a.calls += 1
            a.quota += r.quota ?: 0.0
        }
        return Result(
            rows = acc.map { (name, a) -> TokenSpend(name, a.quota / safeQpu, a.calls) }
                .sortedWith(compareByDescending<TokenSpend> { it.usd }.thenBy { it.name })
                .take(TOP_N),
            truncated = truncated,
        )
    }
}
