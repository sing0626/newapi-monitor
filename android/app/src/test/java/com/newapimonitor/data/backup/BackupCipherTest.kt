package com.newapimonitor.data.backup

import com.newapimonitor.domain.Instance
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class BackupCipherTest {
    @Test fun `separate accounts at the same URL retain independent budgets`() {
        val instances = listOf(Instance(id="one",name="one",baseUrl="https://same.test"),Instance(id="two",name="two",baseUrl="https://same.test"))
        val raw=BackupManager.toJson(instances,null,null,budgetsById=mapOf("one" to 10.0,"two" to 20.0))
        val budgets=BackupManager.parse(raw)!!.instanceBudgets
        assertEquals(listOf("one","two"),budgets.map { it.name }); assertEquals(listOf(10.0,20.0),budgets.map { it.usd })
    }
    @Test fun `default backup excludes credentials and roundtrip retains budgets`() {
        val raw = BackupManager.toJson(listOf(Instance(baseUrl="https://site.test",accessToken="access-secret",apiKey="sk-secret")),null,null,
            budgetsByBaseUrl=mapOf("https://site.test" to 100.0))
        assertFalse(raw.contains("access-secret")); assertFalse(raw.contains("sk-secret"))
        assertEquals(100.0,BackupManager.parse(raw)!!.budgets["https://site.test"]!!,0.0)
        assertTrue(BackupManager.parse(raw)!!.instances.single().apiKey.isEmpty())
    }
    @Test fun `authenticated encryption roundtrips unicode and uses fresh randomness`() {
        val plain="{\"apiKey\":\"secret\",\"name\":\"香港🌙\"}"
        val password="a long password 漢字".toCharArray()
        val first=BackupCipher.encrypt(plain,password)
        val second=BackupCipher.encrypt(plain,password)
        assertTrue(BackupCipher.isEncrypted(first)); assertNotEquals(first,second)
        assertFalse(first.contains("secret")); assertEquals(plain,BackupCipher.decrypt(first,password))
    }
    @Test fun `wrong password ciphertext tamper and parameter downgrade fail closed`() {
        val encrypted=BackupCipher.encrypt("sensitive data","correct password".toCharArray())
        assertTrue(runCatching { BackupCipher.decrypt(encrypted,"wrong password".toCharArray()) }.isFailure)
        val obj=Json.parseToJsonElement(encrypted).jsonObject
        val bytes=java.util.Base64.getDecoder().decode(obj["data"]!!.jsonPrimitive.content)
        bytes[0]=(bytes[0].toInt() xor 1).toByte()
        val tampered=JsonObject(obj.toMutableMap().apply { put("data",JsonPrimitive(java.util.Base64.getEncoder().encodeToString(bytes))) }).toString()
        assertTrue(runCatching { BackupCipher.decrypt(tampered,"correct password".toCharArray()) }.isFailure)
        val downgrade=encrypted.replace("210000","1")
        assertTrue(runCatching { BackupCipher.decrypt(downgrade,"correct password".toCharArray()) }.isFailure)
        assertTrue(runCatching { BackupCipher.encrypt("x","short".toCharArray()) }.isFailure)
    }
    @Test fun `unknown backup versions are rejected before any import`() {
        assertNull(BackupManager.parse("""{"version":99,"instances":[]}"""))
    }
}
