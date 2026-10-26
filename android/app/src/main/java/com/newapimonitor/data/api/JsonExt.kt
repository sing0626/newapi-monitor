package com.newapimonitor.data.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** kotlinx-serialization JsonElement 寬鬆取值工具（對應 js 防禦式解析） */

fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.longOf(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

fun JsonObject.doubleOf(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

fun JsonObject.intOf(key: String): Int? = longOf(key)?.let { l ->
    if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else null
}

/** js truthy：布林 true，或非零數字（is_stream 等欄位兼容） */
fun JsonObject.boolOf(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.let { p ->
        p.booleanOrNull == true || (p.longOrNull ?: 0L) != 0L
    } == true

/** data 欄位唔係 object 時返回空 object，唔炸 */
fun JsonElement?.asJsonObjectOrEmpty(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
