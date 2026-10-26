package com.newapimonitor.data.monitor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newapimonitor.data.store.InstanceRepository
import com.newapimonitor.domain.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class MonitorServiceTest {
    private val store = InstanceRepository(ApplicationProvider.getApplicationContext<Application>(), com.newapimonitor.TestSecrets.cipher())
    private val service = MonitorService(store)
    private fun json(value:String)=MockResponse().setHeader("Content-Type","application/json").setBody(value)
    private fun instance(server: MockWebServer,id:String="monitor-test",keyOnly:Boolean=false)=Instance(id=id,
        baseUrl=server.url("/").toString(),accessToken=if(keyOnly) "" else "access-$id",apiKey=if(keyOnly) "key-$id" else "")

    @Test fun `refresh isolates credentials and uses aggregate daily totals not bounded logs`() = runBlocking {
        MockWebServer().use { server ->
            val at=LocalDate.now().atTime(1,0).atZone(ZoneId.systemDefault()).toEpochSecond()
            server.enqueue(json("""{"success":true,"data":{"quota_per_unit":500000}}"""))
            server.enqueue(json("""{"success":true,"data":{"id":123,"quota":5000000}}"""))
            server.enqueue(json("""{"success":true,"data":[{"created_at":$at,"quota":1000000}]}"""))
            val row=service.refresh(instance(server),null)
            assertEquals(10.0,row.balanceUsd!!,0.0); assertEquals(2.0,row.todayUsd!!,0.0)
            assertTrue(row.balanceFresh()); assertTrue(row.spendFresh())
            assertNull(server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer access-monitor-test",server.takeRequest().getHeader("Authorization"))
            assertEquals("123",server.takeRequest().getHeader("New-Api-User"))
        }
    }
    @Test fun `failure keeps last balance marked stale and never invents zero spend`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"success":true,"data":{}}"""))
            repeat(2) { server.enqueue(MockResponse().setResponseCode(401).setBody("{}")) }
            repeat(2) { server.enqueue(MockResponse().setResponseCode(401).setBody("{}")) }
            val old=MonitorSnapshot("monitor-test",balanceUsd=5.0,balanceAt=1,attemptedAt=1,sourceStamp=MonitorSnapshot.stamp(instance(server)))
            val result=service.refresh(instance(server),old)
            assertEquals(5.0,result.balanceUsd!!,0.0); assertFalse(result.balanceFresh()); assertNull(result.todayUsd)
            assertNotNull(result.balanceFailure); assertNotNull(result.spendFailure)
        }
    }
    @Test fun `CNY billing is converted before summing USD`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"success":true,"data":{"quota_display_type":"CNY","usd_exchange_rate":7.0}}"""))
            server.enqueue(json("""{"hard_limit_usd":70.0}"""))
            server.enqueue(json("""{"total_usage":1400.0}"""))
            val row=service.refresh(instance(server,keyOnly=true),null)
            assertEquals(8.0,row.balanceUsd!!,0.0); assertNull(row.todayUsd)
        }
    }
    @Test fun `unknown key only billing unit is excluded from USD totals`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"success":true,"data":{}}"""))
            server.enqueue(json("""{"hard_limit_usd":700.0}"""));server.enqueue(json("""{"total_usage":100.0}"""))
            val row=service.refresh(instance(server,keyOnly=true),null)
            assertNull(row.balanceUsd);assertFalse(row.balanceFresh());assertNotNull(row.balanceFailure)
        }
    }
    @Test fun `refresh all limits active instances to three and never changes active settings`() = runBlocking {
        val active=AtomicInteger();val maximum=AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher=object: Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    val count=active.incrementAndGet(); maximum.updateAndGet { maxOf(it,count) }
                    try {
                        Thread.sleep(30)
                        return when { request.path!!.startsWith("/api/status") -> json("""{"success":true,"data":{"quota_per_unit":500000}}""")
                            request.path!!.startsWith("/api/user/self") -> json("""{"success":true,"data":{"id":1,"quota":500000}}""")
                            else -> json("""{"success":true,"data":[]}""") }
                    } finally { active.decrementAndGet() }
                }
            }
            val settings=store.settings.value
            val rows=service.refreshAll((1..6).map { instance(server,"parallel-$it") },emptyList())
            assertEquals(6,rows.size);assertTrue(maximum.get()<=3);assertEquals(settings,store.settings.value)
            assertEquals(6.0,rows.sumOf { it.balanceUsd ?: 0.0 },0.0)
        }
    }
    @Test fun `daily parser rejects invalid containers numbers and timestamps`() {
        for (raw in listOf("null","{}","[{}]","[{\"created_at\":0,\"quota\":1}]","[{\"created_at\":1,\"quota\":-1}]")) {
            assertTrue(runCatching { MonitorService.dailyQuota(kotlinx.serialization.json.Json.parseToJsonElement(raw),500000.0) }.isFailure)
        }
        assertTrue(MonitorService.dailyQuota(kotlinx.serialization.json.Json.parseToJsonElement("[]"),500000.0).isEmpty())
    }
}
