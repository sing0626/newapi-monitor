package com.newapimonitor.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The site's display unit, not the unit implied by the billing endpoint's *_usd field names.
 * Unknown is deliberate: absent/old/malformed status must not silently become dollars.
 * Pure JVM logic; no network, Android context, or mutable global settings.
 */
sealed interface DisplayUnits {
    val symbol: String?

    data object Unknown : DisplayUnits {
        override val symbol: String? = null
    }

    data object Usd : DisplayUnits {
        override val symbol = "$"
    }

    data class Cny(val usdExchangeRate: Double) : DisplayUnits {
        override val symbol = "¥"
    }

    data object Tokens : DisplayUnits {
        override val symbol: String? = null
    }

    data class Custom(val currencySymbol: String, val usdExchangeRate: Double) : DisplayUnits {
        override val symbol get() = currencySymbol
    }

    /** /api/status data object, or null on status failure. */
    companion object {
        fun fromStatus(status: JsonObject?): DisplayUnits {
            if (status == null) return Unknown
            val mode = (status["quota_display_type"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
            return when (mode) {
                "USD" -> Usd
                "TOKENS" -> Tokens
                "CNY" -> status.positiveNumber("usd_exchange_rate")?.let(::Cny) ?: Unknown
                "CUSTOM" -> {
                    val rate = status.positiveNumber("custom_currency_exchange_rate") ?: return Unknown
                    val symbol = (status["custom_currency_symbol"] as? JsonPrimitive)
                        ?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: "¤"
                    Custom(symbol, rate)
                }
                // display_in_currency is a legacy boolean: true cannot distinguish USD/CNY/CUSTOM.
                // Even false is not enough to override an absent/malformed explicit mode reliably.
                else -> Unknown
            }
        }
    }

    /** Raw user/log/token quota -> site's displayed quantity. Null means unknown/invalid. */
    fun quotaAmount(quota: Double, qpu: Double): Double? {
        if (!quota.isFinite() || !qpu.isFinite() || qpu <= 0) return null
        val usd = quota / qpu
        val amount = when (this) {
            Unknown -> return null
            Usd -> usd
            is Cny -> usd * usdExchangeRate
            Tokens -> quota
            is Custom -> usd * usdExchangeRate
        }
        return amount.takeIf { it.isFinite() }
    }

    /**
     * Billing hard_limit_usd or total_usage / 100 -> site's displayed quantity.
     * Upstream billing.go handles USD/CNY/TOKENS, but CUSTOM falls through to USD;
     * custom conversion must therefore be applied here exactly once.
     */
    fun billingAmount(value: Double): Double? {
        if (!value.isFinite()) return null
        val amount = when (this) {
            Unknown -> return null
            is Custom -> value * usdExchangeRate
            else -> value
        }
        return amount.takeIf { it.isFinite() }
    }

    /** The billing endpoint's value in actual USD when convertible, never for TOKENS/Unknown. */
    fun billingUsd(value: Double): Double? {
        if (!value.isFinite()) return null
        val usd = when (this) {
            Unknown, Tokens -> return null
            Usd, is Custom -> value
            is Cny -> value / usdExchangeRate
        }
        return usd.takeIf { it.isFinite() }
    }
}

private fun JsonObject.positiveNumber(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        ?.takeIf { it.isFinite() && it > 0 }
