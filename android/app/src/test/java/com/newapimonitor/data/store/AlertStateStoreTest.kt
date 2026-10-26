package com.newapimonitor.data.store

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/** 警報狀態持久化（per instance）：roundtrip、清除、損壞數據兜底 */
@RunWith(RobolectricTestRunner::class)
class AlertStateStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Application>()
    private val store = AlertStateStore(context)

    private fun newInstId(): String = "alert-" + UUID.randomUUID().toString().replace("-", "").take(16)

    @Test
    fun `預設狀態 - armed true 空記錄`() = runBlocking {
        val s = store.read(newInstId())
        assertTrue(s.armedBalanceOk)
        assertTrue(s.lastFiredMs.isEmpty())
        assertTrue(s.lastBannedTokenIds.isEmpty())
    }

    @Test
    fun `write 與 read roundtrip`() = runBlocking {
        val id = newInstId()
        store.write(
            id,
            AlertState(
                lastFiredMs = mapOf("BALANCE_LOW" to 1_234L, "SPIKE" to 5_678L),
                armedBalanceOk = false,
                lastBannedTokenIds = setOf(7L, 9L),
                tokenExpiryPhases = mapOf("7:1770000000" to 2),
            ),
        )
        val s = store.read(id)
        assertEquals(mapOf("BALANCE_LOW" to 1_234L, "SPIKE" to 5_678L), s.lastFiredMs)
        assertFalse(s.armedBalanceOk)
        assertEquals(setOf(7L, 9L), s.lastBannedTokenIds)
        assertEquals(mapOf("7:1770000000" to 2), s.tokenExpiryPhases)
        store.clear(id)
    }

    @Test
    fun `clear 清走狀態`() = runBlocking {
        val id = newInstId()
        store.write(id, AlertState(armedBalanceOk = false, lastBannedTokenIds = setOf(1L)))
        store.clear(id)
        assertTrue(store.read(id).armedBalanceOk)
    }

    @Test
    fun `損壞 JSON 返回預設狀態唔會炸`() = runBlocking {
        val id = newInstId()
        context.dataStore.edit {
            it[androidx.datastore.preferences.core.stringPreferencesKey("alert_state.v1.$id")] = "{broken"
        }
        assertTrue(store.read(id).armedBalanceOk)
        store.clear(id)
    }
}
