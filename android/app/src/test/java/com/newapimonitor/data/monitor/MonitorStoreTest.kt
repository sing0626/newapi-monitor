package com.newapimonitor.data.monitor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.preferences.core.edit
import com.newapimonitor.NewApiMonitorApp
import com.newapimonitor.alert.*
import com.newapimonitor.data.store.*
import com.newapimonitor.domain.Instance
import com.newapimonitor.ui.speedtest.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class MonitorStoreTest {
    private val context=ApplicationProvider.getApplicationContext<Application>()
    @Test fun `budgets and alert phases persist independently across instances and months`() = runBlocking {
        val cache=MonitorStore(context);val id=UUID.randomUUID().toString()
        cache.setBudget(id,50.0)
        assertEquals(50.0,MonitorStore(context).budgets.first()[id]!!,0.0)
        assertTrue(cache.claimBudgetPhase(id,"2026-10",90)); assertFalse(cache.claimBudgetPhase(id,"2026-10",90))
        assertTrue(cache.claimBudgetPhase(id,"2026-10",100)); assertTrue(cache.claimBudgetPhase(id,"2026-11",90))
        cache.setBudget(id,75.0); assertTrue(cache.claimBudgetPhase(id,"2026-10",90))
        cache.remove(id); assertNull(cache.budgets.first()[id])
    }
    @Test fun `alert records are bounded persist unread state and can be cleared`() = runBlocking {
        val history=AlertHistoryStore(context); history.clear()
        repeat(205) { history.add(AlertRecord(sourceId="audit",title="budget-$it",body="amount",type="budget",time=it.toLong())) }
        assertEquals(200,history.records.first().size);assertEquals("budget-204",history.records.first().first().title)
        history.markRead();assertTrue(AlertHistoryStore(context).records.first().all { it.read })
        history.clear();assertTrue(history.records.first().isEmpty())
    }
    @Test fun `wipe clears appearance layout probe histories budgets snapshots and notifications together`() = runBlocking {
        val app=ApplicationProvider.getApplicationContext<NewApiMonitorApp>()
        withTimeout(5000) { app.store.ready.first { it } }
        AppearanceStore(context).setLanguage("en")
        DashboardLayoutStore(context).setCardHidden("models",true)
        SpeedTestHistoryStore(context).add("demo",PingHistoryItem("m",10,true,200,"ok"))
        val cache=MonitorStore(context);cache.setBudget("wipe",30.0);cache.save(MonitorSnapshot("wipe",balanceUsd=1.0))
        AlertHistoryStore(context).add(AlertRecord(sourceId="wipe",title="t",body="b",type="test"))
        app.store.wipeAll()
        assertTrue(context.dataStore.data.first().asMap().isEmpty())
        assertEquals("yue",AppearanceStore(context).appearance.first().language)
        assertTrue(SpeedTestHistoryStore(context).read("demo").isEmpty())
        assertTrue(cache.snapshots.first().isEmpty());assertTrue(cache.budgets.first().isEmpty())
        assertTrue(AlertHistoryStore(context).records.first().isEmpty())
    }
    @Test fun `deleting instance removes both old and new probe history keys and audit cache`() = runBlocking {
        val app=ApplicationProvider.getApplicationContext<NewApiMonitorApp>()
        withTimeout(5000) { app.store.ready.first { it } }
        val inst=app.store.addInstance(Instance(baseUrl="https://delete.test"))!!
        val history=SpeedTestHistoryStore(context)
        history.add(inst.id,PingHistoryItem("legacy",10,true,200,"ok"))
        history.add("instance.${inst.id}",PingHistoryItem("stream",10,true,200,"ok",firstTokenMs=5,completionTokens=10,tokensPerSecond=20.0))
        MonitorStore(context).save(MonitorSnapshot(inst.id,balanceUsd=1.0))
        AlertHistoryStore(context).add(AlertRecord(sourceId=inst.id,title="t",body="b",type="test"))
        app.store.removeInstance(inst.id)
        assertTrue(history.read(inst.id).isEmpty());assertTrue(history.read("instance.${inst.id}").isEmpty())
        assertFalse(MonitorStore(context).snapshots.first().any { it.id==inst.id })
        assertFalse(AlertHistoryStore(context).records.first().any { it.sourceId==inst.id })
    }
}
