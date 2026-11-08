package com.metrolist.music.recommend

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncConfigCodecTest {
    @Test
    fun `round trips every preference type`() {
        val values =
            listOf<Any>(
                true,
                42,
                1_700_000_000_000L,
                1.5f,
                "dark",
                setOf("a", "b"),
            )
        values.forEach { value ->
            val encoded = SyncConfigCodec.encodeValue(value)!!
            assertEquals(value, SyncConfigCodec.decodeValue(encoded))
        }
    }

    @Test
    fun `detects secrets by name`() {
        assertTrue(SyncConfigCodec.isSecret("scrobblerToken"))
        assertTrue(SyncConfigCodec.isSecret("openRouterApiKey"))
        assertTrue(SyncConfigCodec.isSecret("innerTubeCookie"))
        assertTrue(SyncConfigCodec.isSecret("lastfmSession"))
        assertFalse(SyncConfigCodec.isSecret("dynamicTheme"))
    }

    @Test
    fun `excludes device and sync bookkeeping`() {
        assertTrue(SyncConfigCodec.isExcluded("radiostarSyncLastSeq"))
        assertTrue(SyncConfigCodec.isExcluded("scrobblerCheckpoints"))
        assertTrue(SyncConfigCodec.isExcluded("playerVolume"))
        assertTrue(SyncConfigCodec.isExcluded("alarmHour"))
        assertFalse(SyncConfigCodec.isExcluded("dynamicTheme"))
    }

    @Test
    fun `snapshot is stable and parseable`() {
        val snapshot = mapOf("b" to "{\"v\":2}", "a" to "{\"v\":1}")
        val parsed = SyncConfigCodec.parseSnapshot(SyncConfigCodec.encodeSnapshot(snapshot))
        assertEquals(snapshot, parsed.toMap())
    }

    @Test
    fun `reaction key prefers video id`() {
        assertEquals("yt:abc", SyncConfigCodec.reactionKey("abc", "Song", "Artist"))
        assertEquals(
            "tx:song|artist",
            SyncConfigCodec.reactionKey(null, "  Song ", "Artist"),
        )
    }

    @Test
    fun `secret fingerprints do not contain plaintext`() {
        val encoded = SyncConfigCodec.encodeValue("super-secret-token")!!
        val fingerprint = SyncConfigCodec.fingerprint("scrobblerToken", encoded)
        assertFalse(fingerprint.contains("super-secret-token"))
        assertEquals(
            fingerprint,
            SyncConfigCodec.fingerprint("scrobblerToken", encoded),
        )
    }

    @Test
    fun `string set order does not affect canonical form`() {
        val first = SyncConfigCodec.encodeValue(setOf("b", "a"))!!
        val second = SyncConfigCodec.encodeValue(setOf("a", "b"))!!
        assertEquals(first.toString(), second.toString())
        assertFalse(JSONObject(first.toString()).isNull("value"))
    }
}
