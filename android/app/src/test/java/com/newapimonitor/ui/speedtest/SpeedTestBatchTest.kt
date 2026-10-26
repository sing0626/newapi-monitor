package com.newapimonitor.ui.speedtest

import com.newapimonitor.domain.PingResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class SpeedTestBatchTest {
    @Test fun `model failure does not abort batch and batch never exceeds five`() = runBlocking {
        val attempted = mutableListOf<String>()
        val results = mutableListOf<PingResult>()
        runSpeedTestBatch((1..7).map { "model-$it" }, ping = { model ->
            attempted.add(model)
            if (model == "model-2") error("unavailable")
            PingResult(true, 12, model)
        }, onResult = { _, result -> results.add(result) })
        assertEquals((1..5).map { "model-$it" }, attempted)
        assertEquals(5, results.size)
        assertFalse(results[1].ok)
        assertEquals("unavailable", results[1].message)
        assertEquals("model-5", results.last().model)
    }

    @Test fun `cancellation is propagated rather than recorded as model failure`() = runBlocking {
        try {
            runSpeedTestBatch(listOf("a", "b"), ping = { throw CancellationException("stop") },
                onResult = { _, _ -> fail("cancelled result must not be recorded") })
            fail("cancellation should propagate")
        } catch (_: CancellationException) {
            // Expected.
        }
    }
}
