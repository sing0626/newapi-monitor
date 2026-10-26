package com.newapimonitor.domain

/**
 * 原始 quota ↔ USD 換算：
 *  - 優先用戶設定 → /api/status 嘅 quota_per_unit → fallback 500000
 *  - 日誌頁同儀表板必須用同一個值（repository 層經 snapshotForSource 共用同來源快照嘅 qpu）
 *  - 顯示貨幣必須另由 DisplayUnits 判定：billing API 欄名 *_usd 並不保證實際係 USD。
 */
object Quota {
    const val DEFAULT_QPU = 500000.0

    /** js：Number(inst.quotaPerUnit) || Number(status?.quota_per_unit) || 500000 */
    fun resolveQpu(instOverride: Double?, statusQpu: Double?): Double =
        instOverride?.takeIf { it.isFinite() && it > 0 }
            ?: statusQpu?.takeIf { it.isFinite() && it > 0 }
            ?: DEFAULT_QPU

    fun toUsd(quota: Double, qpu: Double): Double =
        quota / (if (qpu.isFinite() && qpu > 0) qpu else DEFAULT_QPU)

    /** usage 端點係站點展示單位嘅百分之一（唔一定係美分）。 */
    fun billingUsageHundredthsToAmount(hundredths: Double): Double = hundredths / 100.0

    /** 舊呼叫兼容：只喺 DisplayUnits.Usd 時結果先係 USD。 */
    @Deprecated("Billing usage may be CNY or TOKENS; resolve DisplayUnits before labeling the result")
    fun usageCentsToUsd(cents: Double): Double = billingUsageHundredthsToAmount(cents)
}
