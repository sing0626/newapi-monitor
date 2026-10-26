package com.newapimonitor.util

import com.newapimonitor.domain.DisplayUnits
import java.util.Locale
import kotlin.math.abs

/** Format an already-converted display amount; never invent USD for unknown status. */
object AmountFmt {
    fun display(value: Double?, units: DisplayUnits): String {
        if (value == null || !value.isFinite() || units == DisplayUnits.Unknown) return "—"
        if (units == DisplayUnits.Tokens) return String.format(Locale.US, "%,.0f tokens", value)
        val symbol = units.symbol ?: return "—"
        val sign = if (value < 0) "-" else ""
        if (value > 0 && value < 0.005) return "<$symbol" + "0.01"
        return sign + symbol + String.format(Locale.US, "%,.2f", abs(value))
    }
}
