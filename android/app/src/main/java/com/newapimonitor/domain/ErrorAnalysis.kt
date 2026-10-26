package com.newapimonitor.domain

/**
 * 錯誤深入分析（純函數聚合，日誌頁「錯誤分析」卡用）：
 * 輸入係已過濾嘅今日 type=5 錯誤行（LogsViewModel 平行輕量抓取；demo 為 DemoEngine.todayLogs 同款過濾）。
 * LogEntry 唔保證帶上游錯誤信息文本（content 舊站可能缺）→ 按模型聚合，唔按信息分組。
 * 模型名 blank（null／空／全空白）嘅行整筆跳過 —— 唔好整出空名組。
 */
object ErrorAnalysis {

    /** 「錯誤最多模型」一組 */
    data class ErrGroup(
        val name: String,
        val count: Int,
    )

    /** 「最近錯誤」一筆 */
    data class RecentError(
        val model: String,
        val token: String?,
        /** epoch 秒 */
        val createdAtSec: Long,
    )

    data class Summary(
        /** 計入聚合嘅錯誤行數（blank model 行唔計） */
        val total: Int,
        /** 錯誤最多模型 Top3（次數 desc；同分按名稱 asc 穩定排序） */
        val topModels: List<ErrGroup>,
        /** 最近 10 筆（時間 desc；同秒保持輸入順序） */
        val recent: List<RecentError>,
    )

    const val TOP_MODELS = 3
    const val RECENT_LIMIT = 10

    fun compute(errorRows: List<LogEntry>): Summary {
        if (errorRows.isEmpty()) return Summary(0, emptyList(), emptyList())
        val byModel = LinkedHashMap<String, Int>()
        for (r in errorRows) {
            val m = r.modelName?.trim().orEmpty()
            if (m.isEmpty()) continue
            byModel[m] = (byModel[m] ?: 0) + 1
        }
        val total = byModel.values.sum()
        val topModels = byModel.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(TOP_MODELS)
            .map { ErrGroup(it.key, it.value) }
        val recent = errorRows
            .filter { !it.modelName?.trim().isNullOrEmpty() }
            .sortedByDescending { it.createdAt }
            .take(RECENT_LIMIT)
            .map { r -> RecentError(r.modelName?.trim().orEmpty(), r.tokenName, r.createdAt) }
        return Summary(total, topModels, recent)
    }
}
