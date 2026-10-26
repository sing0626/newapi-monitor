package com.newapimonitor.domain

/**
 * 模型性價比（本月每個模型每百萬 token 平均成本，純 JVM 可測）：
 *  - 輸入本月日誌（/api/log/self 或 DemoEngine.monthLogs 嘅 LogEntry 列表）
 *  - 輸入通常已經 consume 過濾，呢度防禦式再過一次 LogMath.filterConsume（充值／系統／錯誤行絕不入數）
 *  - 跳過 modelName 空白行；tokens = promptTokens + completionTokens；tokens <= 0 嘅模型唔計（冇得算單價）
 *  - usd = sum(quota) ÷ qpu；usdPer1M = usd ÷ (tokens ÷ 1,000,000)
 *  - 按 usdPer1M 降序（最貴排先）
 */
object ModelCostStats {

    /** 一個模型嘅本月性價比數據 */
    data class ModelCost(
        val name: String,
        val usd: Double,
        val calls: Int,
        val tokens: Long,
        /** 每百萬 token 平均成本（USD） */
        val usdPer1M: Double,
    )

    fun compute(logs: List<LogEntry>, qpu: Double): List<ModelCost> {
        val safeQpu = if (qpu.isFinite() && qpu > 0) qpu else Quota.DEFAULT_QPU
        val rows = LogMath.filterConsume(logs)
        class Acc {
            var calls = 0
            var pt = 0L
            var ct = 0L
            var quota = 0.0
        }
        val acc = LinkedHashMap<String, Acc>()
        for (r in rows) {
            val name = r.modelName?.trim().orEmpty()
            if (name.isEmpty()) continue
            val a = acc.getOrPut(name) { Acc() }
            a.calls += 1
            a.pt += r.promptTokens
            a.ct += r.completionTokens
            a.quota += r.quota ?: 0.0
        }
        return acc.mapNotNull { (name, a) ->
            val tokens = a.pt + a.ct
            if (tokens <= 0L) return@mapNotNull null
            val usd = a.quota / safeQpu
            ModelCost(
                name = name,
                usd = usd,
                calls = a.calls,
                tokens = tokens,
                usdPer1M = usd / (tokens / 1_000_000.0),
            )
        }.sortedByDescending { it.usdPer1M }
    }
}
