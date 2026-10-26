package com.newapimonitor.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Versioned, authenticated envelope. Passwords and derived keys are never persisted. */
object BackupCipher {
    private const val FORMAT = "newapi-monitor-encrypted"
    private const val ITERATIONS = 210_000
    private const val MAX_BYTES = 8 * 1024 * 1024
    private val aad = "newapi-monitor-backup:v1".toByteArray(Charsets.UTF_8)
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    @Serializable
    private data class Envelope(
        val format: String = FORMAT, val version: Int = 1,
        val kdf: String = "PBKDF2-SHA256", val cipher: String = "AES-256-GCM",
        val iterations: Int = ITERATIONS, val salt: String, val iv: String, val data: String,
    )
    fun isEncrypted(text: String): Boolean = runCatching {
        Json.parseToJsonElement(text).jsonObject["format"]?.jsonPrimitive?.content == FORMAT
    }.getOrDefault(false)

    fun encrypt(plain: String, password: CharArray): String {
        require(password.size >= 10) { "密碼至少需要 10 個字元" }
        require(plain.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return json.encodeToString(Envelope(salt = b64.encodeToString(salt), iv = b64.encodeToString(iv), data = b64.encodeToString(data)))
    }
    fun decrypt(text: String, password: CharArray): String {
        require(text.length <= MAX_BYTES * 2)
        val e = json.decodeFromString<Envelope>(text)
        require(e.format == FORMAT && e.version == 1 && e.iterations == ITERATIONS && e.kdf == "PBKDF2-SHA256" && e.cipher == "AES-256-GCM")
        val b64 = Base64.getDecoder()
        val salt = b64.decode(e.salt); val iv = b64.decode(e.iv); val data = b64.decode(e.data)
        require(salt.size == 16 && iv.size == 12 && data.size in 16..MAX_BYTES + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(data).toString(Charsets.UTF_8)
    }
    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return try { SecretKeySpec(bytes, "AES") } finally { bytes.fill(0) }
        } finally { spec.clearPassword() }
    }
}
