/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side encryption for synced secrets.
 *
 * The passphrase never reaches the server. A random salt is stored next to the
 * encrypted values so every device can derive the same key, while the server
 * only ever sees AES/GCM ciphertext.
 */
object SyncCrypto {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "v1:"

    private val random = SecureRandom()

    fun newSalt(): String = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))

    fun deriveKey(
        passphrase: String,
        salt: String,
    ): SecretKey {
        val saltBytes = Base64.getDecoder().decode(salt)
        val spec = PBEKeySpec(passphrase.toCharArray(), saltBytes, ITERATIONS, KEY_BITS)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(
        plaintext: String,
        key: SecretKey,
    ): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val payload = cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(payload)
    }

    fun decrypt(
        ciphertext: String,
        key: SecretKey,
    ): String? =
        runCatching {
            if (!ciphertext.startsWith(PREFIX)) return null
            val payload = Base64.getDecoder().decode(ciphertext.removePrefix(PREFIX))
            if (payload.size <= IV_BYTES) return null
            val iv = payload.copyOfRange(0, IV_BYTES)
            val body = payload.copyOfRange(IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()

    fun checkValue(): String = "radiostar-sync-v1"
}
