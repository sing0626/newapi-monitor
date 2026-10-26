package com.newapimonitor.domain

import com.newapimonitor.util.AmountFmt
import org.junit.Assert.assertEquals
import org.junit.Test

class AmountFmtTest {
    @org.junit.Test fun compactUsdPromotesRoundedBillionsAndPreservesNegativeSign() {
        assertEquals("$1T", com.newapimonitor.util.Fmt.usdCompact(999_999_999_999.25))
        assertEquals("$1.01T", com.newapimonitor.util.Fmt.usdCompact(1_010_000_000_000.0))
        assertEquals("-$1.2K", com.newapimonitor.util.Fmt.usdCompact(-1200.25))
        assertEquals("$12.34", com.newapimonitor.util.Fmt.usdCompact(12.34))
    }
    @Test fun `unknown and invalid are not shown as dollars`() {
        assertEquals("—", AmountFmt.display(3.0, DisplayUnits.Unknown))
        assertEquals("—", AmountFmt.display(null, DisplayUnits.Usd))
        assertEquals("—", AmountFmt.display(Double.NaN, DisplayUnits.Usd))
    }

    @Test fun `format matched currency and token labels`() {
        assertEquals("$1,234.50", AmountFmt.display(1234.5, DisplayUnits.Usd))
        assertEquals("¥7.25", AmountFmt.display(7.25, DisplayUnits.Cny(7.25)))
        assertEquals("€0.90", AmountFmt.display(0.9, DisplayUnits.Custom("€", 0.9)))
        assertEquals("500,000 tokens", AmountFmt.display(500000.0, DisplayUnits.Tokens))
        assertEquals("-$2.00", AmountFmt.display(-2.0, DisplayUnits.Usd))
        assertEquals("<$0.01", AmountFmt.display(0.001, DisplayUnits.Usd))
    }
}
