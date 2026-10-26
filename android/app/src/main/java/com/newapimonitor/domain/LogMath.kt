package com.newapimonitor.domain

/**
 * 日誌數據處理（真實 / 演示共用），移植自 js/api.js。
 */
object LogMath {

    /**
     * 只保留「消費」行：type===2（new-api LogTypeConsume）且帶 model_name + quota。
     * 充值（type=1）/系統/管理記錄一律剔除，佢哋嘅 quota 絕唔會當美元顯示。
     * type 缺失（舊版站點）→ 保留，靠 model_name + quota 把關。
     */
    fun filterConsume(rows: List<LogEntry>): List<LogEntry> = rows.filter { r ->
        (r.type == null || r.type == 2) && !r.modelName.isNullOrEmpty() && r.quota != null
    }

    fun sumQuota(rows: List<LogEntry>): Double = rows.sumOf { it.quota ?: 0.0 }

    /** 按模型聚合：請求數、tokens、消耗（USD），按 usd 降序、requests 降序 */
    fun aggregateStats(rows: List<LogEntry>, qpu: Double): List<ModelStat> {
        if (rows.isEmpty()) return emptyList()
        class Acc {
            var requests = 0
            var pt = 0L
            var ct = 0L
            var quota = 0.0
        }
        val map = LinkedHashMap<String, Acc>()
        for (r in rows) {
            val key = r.modelName ?: "unknown"
            val acc = map.getOrPut(key) { Acc() }
            acc.requests += 1
            acc.pt += r.promptTokens
            acc.ct += r.completionTokens
            acc.quota += r.quota ?: 0.0
        }
        val safeQpu = if (qpu.isFinite() && qpu > 0) qpu else Quota.DEFAULT_QPU
        return map.map { (model, a) ->
            ModelStat(model, a.requests, a.pt, a.ct, a.quota, a.quota / safeQpu)
        }.sortedWith(
            compareByDescending<ModelStat> { it.usd }.thenByDescending { it.requests }
        )
    }

    /**
     * 日誌去重 key（/api/log/self 回應嘅 id 係重新編號，唔可以用 id 做 cursor）
     */
    fun logKey(r: LogEntry): String = listOf(
        r.createdAt, r.modelName, r.tokenName, r.promptTokens,
        r.completionTokens, r.quota, r.useTime,
    ).joinToString("|")

    /** 模型名 → palette 色索引（穩定 hash，同一模型永遠同色；js charts.js colorForModel 嘅 >>>0 % n 語義 → 正 modulo） */
    fun colorIndexForModel(name: String?, paletteSize: Int): Int {
        val s = name ?: ""
        var h = 0
        for (c in s) h = h * 31 + c.code
        val size = paletteSize.coerceAtLeast(1)
        return ((h % size) + size) % size
    }
}
