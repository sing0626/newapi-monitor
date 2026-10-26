package com.newapimonitor.data.backup

import com.newapimonitor.data.store.Appearance
import com.newapimonitor.data.monitor.InstanceAlertRule
import com.newapimonitor.data.monitor.MonitorPreferences
import com.newapimonitor.data.monitor.ProbeSuite
import com.newapimonitor.domain.AlertSettings
import com.newapimonitor.domain.AuthStyle
import com.newapimonitor.domain.Instance
import com.newapimonitor.domain.InstanceSanitizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 備份檔入面一個實例（欄位名同 InstanceRepository.InstanceDto 一致） */
@Serializable
data class BackupInstanceDto(
    val name: String = "",
    val baseUrl: String = "",
    val accessToken: String = "",
    val apiKey: String = "",
    val authStyle: String? = null,
    val authStyleKey: String? = null,
    val quotaPerUnit: Double? = null,
    val monthlyBudgetUsd: Double? = null,
    val pinned: Boolean? = null,
    val balanceIncluded: Boolean? = null,
    val homeOrder: Int? = null,
    val alertRule: InstanceAlertRule? = null,
)

/** 外觀設定（欄位可缺；缺 = 匯入時唔好掂該項） */
@Serializable
data class BackupAppearanceDto(
    val language: String? = null,
    val mode: String? = null,
    val accent: String? = null,
)

/** 警報設定（欄位同 AlertSettingsStore.AlertSettingsDto 一致） */
@Serializable
data class BackupAlertSettingsDto(
    val enabled: Boolean = false,
    val balanceBelowUsd: Double? = null,
    val dailySpendAboveUsd: Double? = null,
    val spikeMultiplier: Double = 3.0,
    val cooldownMinutes: Int = 60,
    val notifyTokenBanned: Boolean = true,
    val monthlyBudgetUsd: Double? = null,
    val notifyErrorRate: Boolean = false,
    val notifyTokenExpiry: Boolean = false,
    val persistentStatus: Boolean = false,
)

/** 備份檔根物件：{"version":1,"exportedAt":ms,"instances":[...],"appearance":{...},"alertSettings":{...}} */
@Serializable
data class BackupFileDto(
    val app: String = "newapi-monitor",
    val version: Int = BackupManager.VERSION,
    val exportedAt: Long = 0,
    val instances: List<BackupInstanceDto> = emptyList(),
    val appearance: BackupAppearanceDto? = null,
    val alertSettings: BackupAlertSettingsDto? = null,
    val budgets: Map<String, Double> = emptyMap(),
    val compactHome: Boolean? = null,
    val hideMoney: Boolean? = null,
    val favoriteModels: Set<String> = emptySet(),
    val probeSuites: List<ProbeSuite> = emptyList(),
)

/**
 * 本機備份（純 JSON 序列化／解析，唔掂任何 Android context，可以直接 JVM 單測）。
 * authStyle／authStyleKey 同 InstanceRepository 同一款：只有 "raw" 會寫入檔，其餘 null。
 */
object BackupManager {

    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 外觀設定（欄位可缺；null = 檔案冇該欄，匯入時保留現狀） */
    data class ParsedAppearance(
        val language: String? = null,
        val mode: String? = null,
        val accent: String? = null,
    )

    /**
     * parse 結果：instances 係已清洗嘅草稿（id 空、createdAt 0 —
     * 交 repo.store.addInstance／updateInstance 落地時先派 id）。
     */
    data class ParsedBackup(
        val version: Int,
        val exportedAt: Long,
        val instances: List<Instance>,
        val appearance: ParsedAppearance?,
        val alertSettings: AlertSettings?,
        val budgets: Map<String, Double> = emptyMap(),
        val instanceBudgets: List<InstanceBudget> = emptyList(),
        val homeOptions: MonitorPreferences? = null,
        val instanceOptions: List<InstanceOption> = emptyList(),
    )
    data class InstanceBudget(val name: String, val baseUrl: String, val usd: Double)
    data class InstanceOption(val name: String, val baseUrl: String, val pinned: Boolean?,
        val balanceIncluded: Boolean?, val homeOrder: Int?, val alertRule: InstanceAlertRule?)

    /** 序列化成備份檔 JSON（version 1 形狀） */
    fun toJson(
        instances: List<Instance>,
        appearance: Appearance?,
        alertSettings: AlertSettings?,
        exportedAtMs: Long = System.currentTimeMillis(),
        includeCredentials: Boolean = false,
        budgetsByBaseUrl: Map<String, Double> = emptyMap(),
        budgetsById: Map<String, Double> = emptyMap(),
        homeOptions: MonitorPreferences? = null,
        alertRules: Map<String, InstanceAlertRule> = emptyMap(),
    ): String = json.encodeToString(
        BackupFileDto(
            version = VERSION,
            exportedAt = exportedAtMs,
            instances = instances.map {
                dtoOf(if (includeCredentials) it else it.copy(accessToken = "", apiKey = "", authStyle = null, authStyleKey = null))
                    .copy(monthlyBudgetUsd = budgetsById[it.id]?.takeIf { budget -> budget.isFinite() && budget > 0 },
                        pinned = homeOptions?.let { options -> it.id in options.pinned },
                        balanceIncluded = homeOptions?.let { options -> it.id !in options.excluded },
                        homeOrder = homeOptions?.order?.indexOf(it.id)?.takeIf { index -> index >= 0 },
                        alertRule = alertRules[it.id]?.takeIf { rule -> runCatching { rule.validate() }.isSuccess })
            },
            compactHome = homeOptions?.compact,
            hideMoney = homeOptions?.hideMoney,
            favoriteModels = homeOptions?.favorites.orEmpty().filter { it.length <= 150 }.take(100).toSet(),
            probeSuites = homeOptions?.suites.orEmpty().take(20),
            budgets = budgetsByBaseUrl.filterValues { it.isFinite() && it > 0 },
            appearance = appearance?.let {
                BackupAppearanceDto(language = it.language, mode = it.mode, accent = it.accent)
            },
            alertSettings = alertSettings?.let {
                BackupAlertSettingsDto(
                    enabled = it.enabled,
                    balanceBelowUsd = it.balanceBelowUsd,
                    dailySpendAboveUsd = it.dailySpendAboveUsd,
                    spikeMultiplier = it.spikeMultiplier,
                    cooldownMinutes = it.cooldownMinutes,
                    notifyTokenBanned = it.notifyTokenBanned,
                    monthlyBudgetUsd = it.monthlyBudgetUsd,
                    notifyErrorRate = it.notifyErrorRate,
                    notifyTokenExpiry = it.notifyTokenExpiry,
                    persistentStatus = it.persistentStatus,
                )
            },
        )
    )

    /**
     * 解析備份檔；唔係合法 JSON／形狀唔對返回 null（當「檔案格式不正確」）。
     * 個別實例 baseUrl 清洗後空白就跳過嗰條；未知欄位一律容忍。
     */
    fun parse(text: String): ParsedBackup? = try {
        require(text.length <= 8 * 1024 * 1024)
        val shape = kotlinx.serialization.json.Json.parseToJsonElement(text.removePrefix("\uFEFF").trim()) as? kotlinx.serialization.json.JsonObject
        require(shape?.get("instances") is kotlinx.serialization.json.JsonArray)
        val f = json.decodeFromString<BackupFileDto>(text.removePrefix("\uFEFF").trim())
        require(f.version == VERSION && f.app == "newapi-monitor")
        ParsedBackup(
            version = f.version,
            exportedAt = f.exportedAt,
            budgets = f.budgets.filterValues { it.isFinite() && it > 0 },
            instanceBudgets = f.instances.mapNotNull { dto ->
                val budget = dto.monthlyBudgetUsd?.takeIf { it.isFinite() && it > 0 }
                val draft = draftOf(dto)
                if (budget != null && draft != null) InstanceBudget(draft.name, draft.baseUrl, budget) else null
            },
            homeOptions = if (f.compactHome != null || f.hideMoney != null || f.favoriteModels.isNotEmpty() || f.probeSuites.isNotEmpty())
                MonitorPreferences(compact = f.compactHome ?: false, hideMoney = f.hideMoney ?: false,
                    favorites = f.favoriteModels.filter { it.length <= 150 }.take(100).toSet(),
                    suites = f.probeSuites.filter { it.name.length in 1..40 && it.models.size in 1..3 && it.models.all { model -> model.length in 1..150 } }.take(20))
                else null,
            instanceOptions = f.instances.mapNotNull { dto -> draftOf(dto)?.let { draft ->
                InstanceOption(draft.name, draft.baseUrl, dto.pinned, dto.balanceIncluded, dto.homeOrder?.takeIf { it in 0..10_000 },
                    dto.alertRule?.takeIf { rule -> runCatching { rule.validate() }.isSuccess })
            } },
            instances = f.instances.mapNotNull { draftOf(it) },
            appearance = f.appearance?.let { ParsedAppearance(it.language, it.mode, it.accent) },
            alertSettings = f.alertSettings?.let {
                AlertSettings(
                    enabled = it.enabled,
                    balanceBelowUsd = it.balanceBelowUsd,
                    dailySpendAboveUsd = it.dailySpendAboveUsd,
                    spikeMultiplier = it.spikeMultiplier,
                    cooldownMinutes = it.cooldownMinutes,
                    notifyTokenBanned = it.notifyTokenBanned,
                    monthlyBudgetUsd = it.monthlyBudgetUsd,
                    notifyErrorRate = it.notifyErrorRate,
                    notifyTokenExpiry = it.notifyTokenExpiry,
                    persistentStatus = it.persistentStatus,
                )
            },
        )
    } catch (e: Exception) {
        null
    }

    private fun dtoOf(i: Instance) = BackupInstanceDto(
        name = i.name,
        baseUrl = i.baseUrl,
        accessToken = i.accessToken,
        apiKey = i.apiKey,
        // 只有 RAW 會持久化（同 InstanceRepository.instanceToDto 一致）
        authStyle = if (i.authStyle == AuthStyle.RAW) "raw" else null,
        authStyleKey = if (i.authStyleKey == AuthStyle.RAW) "raw" else null,
        quotaPerUnit = i.quotaPerUnit,
    )

    /** 清洗成草稿：baseUrl 必須非空白（sanitizeBaseUrl 同款限制），唔係就成條跳過 */
    private fun draftOf(d: BackupInstanceDto): Instance? {
        val baseUrl = InstanceSanitizer.sanitizeBaseUrl(d.baseUrl)
        if (baseUrl.isEmpty()) return null
        return Instance(
            id = "",
            name = d.name.trim().take(60),
            baseUrl = baseUrl,
            accessToken = d.accessToken.trim().take(300),
            apiKey = d.apiKey.trim().take(300),
            quotaPerUnit = d.quotaPerUnit?.takeIf { it.isFinite() && it > 0 },
            authStyle = if (d.authStyle == "raw") AuthStyle.RAW else null,
            authStyleKey = if (d.authStyleKey == "raw") AuthStyle.RAW else null,
            createdAt = 0,
        )
    }
}
