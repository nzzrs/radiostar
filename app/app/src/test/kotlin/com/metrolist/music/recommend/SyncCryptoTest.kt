package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncCryptoTest {
    @Test
    fun `round trips with the same passphrase and salt`() {
        val salt = SyncCrypto.newSalt()
        val key = SyncCrypto.deriveKey("correct horse battery", salt)
        val encrypted = SyncCrypto.encrypt("{\"token\":\"abc\"}", key)
        assertEquals("{\"token\":\"abc\"}", SyncCrypto.decrypt(encrypted, key))
    }

    @Test
    fun `wrong passphrase cannot decrypt`() {
        val salt = SyncCrypto.newSalt()
        val encrypted =
            SyncCrypto.encrypt("secret", SyncCrypto.deriveKey("correct horse battery", salt))
        val other = SyncCrypto.deriveKey("wrong horse battery", salt)
        assertNull(SyncCrypto.decrypt(encrypted, other))
    }

    @Test
    fun `salt is random`() {
        assertNotEquals(SyncCrypto.newSalt(), SyncCrypto.newSalt())
    }
}
