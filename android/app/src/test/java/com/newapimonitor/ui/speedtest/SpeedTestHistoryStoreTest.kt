package com.newapimonitor.ui.speedtest

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newapimonitor.domain.Instance
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SpeedTestHistoryStoreTest {
    private val store = SpeedTestHistoryStore(ApplicationProvider.getApplicationContext<Application>())

    @Test fun `persists only latest 20 independently for instances and demo`() = runBlocking {
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        repeat(25) { index ->
            store.add(first, PingHistoryItem("model-$index", index.toLong(), index % 2 == 0,
                400 + index, "result-$index", index.toLong()))
        }
        store.add(second, PingHistoryItem("other", 3, true, 200, "ok", 100))
        store.add(SpeedTestHistoryStore.DEMO_SOURCE, PingHistoryItem("demo", 2, false, 500, "fail", 101))
        val reopened = SpeedTestHistoryStore(ApplicationProvider.getApplicationContext())
        assertEquals((24 downTo 5).map { "model-$it" }, reopened.read(first).map { it.model })
        assertEquals(424, reopened.read(first).first().status)
        assertEquals("result-24", reopened.read(first).first().message)
        assertEquals(listOf("other"), reopened.read(second).map { it.model })
        assertEquals(listOf("demo"), reopened.read(SpeedTestHistoryStore.DEMO_SOURCE).map { it.model })
    }

    @Test fun `source identifiers are isolated and manual selection is capped`() {
        assertEquals(SpeedTestHistoryStore.DEMO_SOURCE, speedTestSourceId(true, Instance(id = "demo")))
        assertEquals("instance.demo", speedTestSourceId(false, Instance(id = "demo")))
        assertEquals(null, speedTestSourceId(false, null))
        assertEquals(listOf("a", "b", "c", "d", "e"), selectedModels("a\na\nb\nc\nd\ne\nf"))
        assertTrue(selectedModels(" \n ").isEmpty())
    }
}
