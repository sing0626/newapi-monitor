package com.newapimonitor.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayUnitsTest {
    private fun status(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    @Test fun `missing or legacy status never silently becomes USD`() {
        assertEquals(DisplayUnits.Unknown, DisplayUnits.fromStatus(null))
        assertEquals(DisplayUnits.Unknown, DisplayUnits.fromStatus(status("{}")))
        assertEquals(DisplayUnits.Unknown, DisplayUnits.fromStatus(status("""{"display_in_currency":true}""")))
        assertEquals(DisplayUnits.Unknown, DisplayUnits.fromStatus(status("""{"display_in_currency":false}""")))
        assertEquals(DisplayUnits.Unknown, DisplayUnits.fromStatus(status("""{"quota_display_type":"BTC"}""")))
        assertEquals(DisplayUnits.Unknown, DisplayUnits.fromStatus(status("""{"quota_display_type":42}""")))
        assertNull(DisplayUnits.Unknown.billingUsd(12.0))
        assertNull(DisplayUnits.Unknown.quotaAmount(500000.0, 500000.0))
    }

    @Test fun `USD has no extra conversion`() {
        val unit = DisplayUnits.fromStatus(status("""{"quota_display_type":"USD"}"""))
        assertEquals(DisplayUnits.Usd, unit)
        assertEquals(2.0, unit.quotaAmount(1000000.0, 500000.0)!!, 1e-12)
        assertEquals(2.0, unit.billingAmount(2.0)!!, 1e-12)
        assertEquals(2.0, unit.billingUsd(2.0)!!, 1e-12)
    }

    @Test fun `CNY uses server rate for quota and converts billing back to USD`() {
        val unit = DisplayUnits.fromStatus(status("""{"quota_display_type":"CNY","usd_exchange_rate":7.25}"""))
        assertEquals(DisplayUnits.Cny(7.25), unit)
        assertEquals(14.5, unit.quotaAmount(1000000.0, 500000.0)!!, 1e-12)
        assertEquals(14.5, unit.billingAmount(14.5)!!, 1e-12)
        assertEquals(2.0, unit.billingUsd(14.5)!!, 1e-12)
    }

    @Test fun `CNY missing or invalid rate remains unknown`() {
        for (rate in listOf("null", "0", "-1", "\"7.25\"")) {
            assertEquals(DisplayUnits.Unknown, DisplayUnits.fromStatus(status("""{"quota_display_type":"CNY","usd_exchange_rate":$rate}""")))
        }
    }

    @Test fun `tokens preserve quota and billing units but cannot be labeled USD`() {
        val unit = DisplayUnits.fromStatus(status("""{"quota_display_type":"TOKENS","display_in_currency":false}"""))
        assertEquals(DisplayUnits.Tokens, unit)
        assertEquals(500000.0, unit.quotaAmount(500000.0, 500000.0)!!, 1e-12)
        assertEquals(700.0, unit.billingAmount(700.0)!!, 1e-12)
        assertNull(unit.billingUsd(700.0))
    }

    @Test fun `CUSTOM uses custom exchange rate for quota and billing only once`() {
        val unit = DisplayUnits.fromStatus(status("""{"quota_display_type":"CUSTOM","custom_currency_symbol":"€","custom_currency_exchange_rate":0.9}"""))
        assertEquals(DisplayUnits.Custom("€", 0.9), unit)
        assertEquals(0.9, unit.quotaAmount(500000.0, 500000.0)!!, 1e-12)
        assertEquals(0.9, unit.billingAmount(1.0)!!, 1e-12)
        assertEquals(1.0, unit.billingUsd(1.0)!!, 1e-12)
    }

    @Test fun `CUSTOM symbol fallback but invalid rate remains unknown`() {
        assertEquals(DisplayUnits.Custom("¤", 1.0), DisplayUnits.fromStatus(status("""{"quota_display_type":"CUSTOM","custom_currency_symbol":"","custom_currency_exchange_rate":1}""")))
        assertEquals(DisplayUnits.Unknown, DisplayUnits.fromStatus(status("""{"quota_display_type":"CUSTOM","custom_currency_exchange_rate":0}""")))
        assertEquals(DisplayUnits.Unknown, DisplayUnits.fromStatus(status("""{"quota_display_type":"CUSTOM"}""")))
    }

    @Test fun `invalid numeric inputs are not shown as money`() {
        assertNull(DisplayUnits.Usd.quotaAmount(1.0, 0.0))
        assertNull(DisplayUnits.Usd.quotaAmount(Double.NaN, 1.0))
        assertNull(DisplayUnits.Usd.billingAmount(Double.POSITIVE_INFINITY))
        assertNull(DisplayUnits.Custom("€", 2.0).billingAmount(Double.MAX_VALUE))
    }
}
