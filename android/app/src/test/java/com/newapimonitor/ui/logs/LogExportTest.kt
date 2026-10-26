package com.newapimonitor.ui.logs

import com.newapimonitor.domain.LogEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class LogExportTest {
    @Test fun `exports current rows and omits sensitive fields`() {
        val row = LogEntry(createdAt = 123, modelName = "=SUM(1,2)\"\nnext", promptTokens = 2,
            quota = 1.5, tokenName = "secret-token", username = "secret-user", content = "secret-content")
        val csv = LogExport.csv(listOf(row))
        val json = LogExport.json(listOf(row))
        assertTrue(csv.startsWith("created_at,model_name,prompt_tokens"))
        assertTrue(csv.contains("\"'=SUM(1,2)\"\"\nnext\""))
        val obj = Json.parseToJsonElement(json).jsonArray.single().jsonObject
        assertEquals("=SUM(1,2)\"\nnext", obj.getValue("model_name").jsonPrimitive.content)
        assertEquals("123", obj.getValue("created_at").jsonPrimitive.content)
        for (secret in listOf("secret-token", "secret-user", "secret-content")) {
            assertFalse(csv.contains(secret))
            assertFalse(json.contains(secret))
        }
    }

    @Test fun `non finite numbers remain valid JSON`() {
        val json = LogExport.json(listOf(LogEntry(quota = Double.NaN, useTime = Double.POSITIVE_INFINITY)))
        val obj = Json.parseToJsonElement(json).jsonArray.single().jsonObject
        assertFalse(obj.containsKey("quota"))
        assertFalse(obj.containsKey("use_time"))
    }
}
