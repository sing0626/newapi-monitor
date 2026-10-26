package com.newapimonitor.domain

/**
 * 錯誤統計（純函數）：new-api 日誌 type=5 係錯誤記錄（quota=0、content 帶錯誤信息）。
 * 輸入係「raw」日誌行（未經 LogMath.filterConsume 過濾，type=2 消費＋type=5 錯誤齊上），
 * 呢度自己分類：只做純函數，UI 之前保留 raw rows 嘅設計。
 */
object ErrorStats {

    /** new-api LogTypeError */
    const val LOG_TYPE_ERROR = 5

    data class Stats(
        /** 最近 24 小時錯誤行數 */
        val last24hCount: Int,
        /** 輸入窗口（全部 rows）內錯誤行數 */
        val windowCount: Int,
        /** 窗口內消費行數（同 LogMath.filterConsume 口徑一致） */
        val consumeCount: Int,
        /** 錯誤率 = windowCount ÷ (windowCount + consumeCount)；無數據 = 0.0 */
        val rate: Double,
        /** 窗口內錯誤按模型計數（缺 model_name 計入 "unknown"） */
        val byModel: Map<String, Int>,
    )

    fun compute(rows: List<LogEntry>, nowMs: Long): Stats {
        if (rows.isEmpty()) return Stats(0, 0, 0, 0.0, emptyMap())
        val cutoffSec = nowMs / 1000 - 86_400L
        var last24h = 0
        var windowErrors = 0
        var consume = 0
        val byModel = LinkedHashMap<String, Int>()
        for (r in rows) {
            if (r.type == LOG_TYPE_ERROR) {
                windowErrors += 1
                if (r.createdAt >= cutoffSec) last24h += 1
                val key = if (r.modelName.isNullOrEmpty()) "unknown" else r.modelName
                byModel[key] = (byModel[key] ?: 0) + 1
            } else if (r.type == null || r.type == 2) {
                // 同 filterConsume 口徑：type 缺失（舊站）靠 model_name + quota 把關
                if (!r.modelName.isNullOrEmpty() && r.quota != null) consume += 1
            }
        }
        val total = windowErrors + consume
        val rate = if (total > 0) windowErrors.toDouble() / total else 0.0
        return Stats(
            last24hCount = last24h,
            windowCount = windowErrors,
            consumeCount = consume,
            rate = rate,
            byModel = byModel,
        )
    }
}
