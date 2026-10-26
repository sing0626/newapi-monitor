package com.newapimonitor.ui.logs

import com.newapimonitor.domain.LogEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Only the visible page is exported. Identifiers and free-form upstream content are omitted. */
internal object LogExport {
    private val columns = listOf(
        "created_at", "model_name", "prompt_tokens", "completion_tokens", "quota", "use_time", "is_stream"
    )

    fun csv(rows: List<LogEntry>): String = buildString {
        appendLine(columns.joinToString(","))
        rows.forEach { row ->
            appendLine(listOf(
                row.createdAt.toString(), row.modelName.orEmpty(), row.promptTokens.toString(),
                row.completionTokens.toString(), row.quota?.takeIf(Double::isFinite)?.toString().orEmpty(),
                row.useTime.takeIf(Double::isFinite)?.toString().orEmpty(), row.isStream.toString()
            ).joinToString(",") { csvCell(it) })
        }
    }

    fun json(rows: List<LogEntry>): String = Json.encodeToString(
        kotlinx.serialization.json.JsonElement.serializer(),
        buildJsonArray {
            rows.forEach { row ->
                add(buildJsonObject {
                    put("created_at", row.createdAt)
                    put("model_name", row.modelName.orEmpty())
                    put("prompt_tokens", row.promptTokens)
                    put("completion_tokens", row.completionTokens)
                    row.quota?.takeIf(Double::isFinite)?.let { put("quota", it) }
                    row.useTime.takeIf(Double::isFinite)?.let { put("use_time", it) }
                    put("is_stream", row.isStream)
                })
            }
        }
    )

    private fun csvCell(value: String): String {
        // Spreadsheet clients interpret formula-like fields even when RFC-style quoted.
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${safe.replace("\"", "\"\"")}\""
        } else safe
    }
}
