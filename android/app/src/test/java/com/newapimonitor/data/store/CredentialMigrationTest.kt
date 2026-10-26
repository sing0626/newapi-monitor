package com.newapimonitor.data.store

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.newapimonitor.TestMonitorApp
import com.newapimonitor.TestSecrets
import com.newapimonitor.domain.Instance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
class CredentialMigrationTest {
    private val app get() = ApplicationProvider.getApplicationContext<TestMonitorApp>()
    private val legacy = stringPreferencesKey("instances.v1")
    private val secured = stringPreferencesKey("instances.v2")

    @Test fun `cipher uses distinct IV and authenticated instance context`() {
        val cipher = TestSecrets.cipher()
        val first = cipher.seal("sk-secret", "one:api")
        val second = cipher.seal("sk-secret", "one:api")
        assertNotEquals(first, second)
        assertEquals("sk-secret", cipher.open(first, "one:api"))
        assertTrue(runCatching { cipher.open(first, "two:api") }.isFailure)
        assertTrue(runCatching { cipher.open(first.dropLast(2) + "aa", "one:api") }.isFailure)
    }

    @Test fun `legacy plaintext migrates before ready and remains usable after reload`() = runBlocking {
        app.store.ready.first { it }; app.store.wipeAll()
        val old = listOf(InstanceDto(id = "i1", name = "Main", baseUrl = "https://example.test", accessToken = "access-secret", apiKey = "key-secret"))
        app.dataStore.edit { it[legacy] = Json.encodeToString(old) }
        val repo = InstanceRepository(app, TestSecrets.cipher())
        repo.load()
        assertTrue(repo.ready.value)
        assertEquals("access-secret", repo.instances.value.single().accessToken)
        val preferences = app.dataStore.data.first()
        assertNull(preferences[legacy])
        assertFalse(preferences[secured]!!.contains("access-secret"))
        assertFalse(preferences[secured]!!.contains("key-secret"))
        assertTrue(preferences[secured]!!.contains("ks1."))
        val reopened = InstanceRepository(app, TestSecrets.cipher())
        reopened.load()
        assertEquals("key-secret", reopened.instances.value.single().apiKey)
    }

    @Test fun `unreadable ciphertext survives unrelated edits and rekey of that source`() = runBlocking {
        app.store.ready.first { it }; app.store.wipeAll()
        val original = InstanceRepository(app, TestSecrets.cipher())
        original.load()
        val source = original.addInstance(Instance(id = "", name = "Main", baseUrl = "https://example.test", apiKey = "key-secret"))!!
        val before = app.dataStore.data.first()[secured]
        val wrongKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val unreadable = InstanceRepository(app, CredentialCipher { wrongKey })
        unreadable.load()
        assertNotNull(unreadable.credentialError.value)
        assertEquals("", unreadable.instances.value.single().apiKey)
        unreadable.addInstance(Instance(id = "", name = "Second", baseUrl = "https://second.test"))
        assertTrue(app.dataStore.data.first()[secured]!!.contains(Json.decodeFromString<List<InstanceDto>>(before!!).single().apiKey))
        unreadable.updateInstance(source.id, source.copy(apiKey = "new-key"))
        assertNull(unreadable.credentialError.value)
        val reopened = InstanceRepository(app, CredentialCipher { wrongKey })
        reopened.load()
        assertEquals("new-key", reopened.instances.value.first { it.id == source.id }.apiKey)
    }

    @Test fun `failed migration leaves legacy data and blocks writes`() = runBlocking {
        app.store.ready.first { it }; app.store.wipeAll()
        val raw = Json.encodeToString(listOf(InstanceDto(id = "i1", baseUrl = "https://example.test", apiKey = "key-secret")))
        app.dataStore.edit { it[legacy] = raw }
        val failing = object : SecretCipher {
            override fun seal(value: String, context: String): String = error("Unavailable")
            override fun open(value: String, context: String): String = error("Unavailable")
        }
        val repo = InstanceRepository(app, failing)
        repo.load()
        assertTrue(repo.ready.value)
        assertNotNull(repo.credentialError.value)
        assertEquals(raw, app.dataStore.data.first()[legacy])
        assertNull(app.dataStore.data.first()[secured])
        assertTrue(runCatching { repo.addInstance(Instance(id = "", baseUrl = "https://other.test")) }.isFailure)
        assertEquals(raw, app.dataStore.data.first()[legacy])
    }
}
