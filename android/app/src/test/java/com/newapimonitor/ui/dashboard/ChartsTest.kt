package com.newapimonitor.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartsTest {
    @Test fun `nearest point selection clamps to chart bounds`() {
        assertEquals(0, sparklineIndex(-100f, 100f, 5, 3f))
        assertEquals(0, sparklineIndex(3f, 100f, 5, 3f))
        assertEquals(2, sparklineIndex(50f, 100f, 5, 3f))
        assertEquals(4, sparklineIndex(97f, 100f, 5, 3f))
        assertEquals(4, sparklineIndex(1000f, 100f, 5, 3f))
    }

    @Test fun `degenerate charts select first point`() {
        assertEquals(0, sparklineIndex(50f, 100f, 0, 3f))
        assertEquals(0, sparklineIndex(50f, 100f, 1, 3f))
        assertEquals(0, sparklineIndex(50f, 4f, 5, 3f))
        assertEquals(0, sparklineIndex(Float.NaN, 100f, 5, 3f))
    }
}
