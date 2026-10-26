package com.newapimonitor.domain

import kotlin.random.Random

/** 實例清洗（移植 store.js sanitizeInstance），入庫前必經 */
object InstanceSanitizer {

    fun sanitizeBaseUrl(raw: String): String = raw.trim().trimEnd('/').take(300)

    /** 設定表單用：自動補 https:// 前綴（js formValues） */
    fun normalizeBaseUrlInput(raw: String): String {
        val url = sanitizeBaseUrl(raw)
        return if (url.isNotEmpty() &&
            !url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            "https://$url"
        } else {
            url
        }
    }

    fun sanitize(o: Instance?): Instance? {
        o ?: return null
        val id = o.id.takeIf { it.isNotBlank() }?.take(64) ?: return null
        return Instance(
            id = id,
            name = o.name.trim().take(60).ifEmpty { "未命名實例" },
            baseUrl = sanitizeBaseUrl(o.baseUrl),
            accessToken = o.accessToken.trim().take(300),
            apiKey = o.apiKey.trim().take(300),
            quotaPerUnit = o.quotaPerUnit?.takeIf { it.isFinite() && it > 0 },
            authStyle = o.authStyle?.takeIf { it == AuthStyle.RAW },
            authStyleKey = o.authStyleKey?.takeIf { it == AuthStyle.RAW },
            createdAt = o.createdAt,
        )
    }

    /** js：'i' + Date.now().toString(36) + Math.random().toString(36).slice(2,7) */
    fun newInstanceSeed(): String =
        "i" + java.lang.Long.toString(System.currentTimeMillis(), 36) + randomSuffix(5)

    private fun randomSuffix(len: Int): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        return (1..len).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }
}
