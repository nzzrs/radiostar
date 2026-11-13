/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeneratedPlaylistMetaTest {
    private val now = LocalDateTime.of(2026, 11, 6, 7, 12)

    @Test
    fun `a first write starts at version one and records creation`() {
        val meta = GeneratedPlaylistMeta().afterRewrite(now, "2026-11-06", 30)

        assertEquals(1, meta.version)
        assertEquals(now, meta.generatedAt)
        assertEquals(now, meta.createdAt)
        assertEquals("2026-11-06", meta.stamp)
        assertEquals(30, meta.trackCount)
    }

    @Test
    fun `a rewrite increments the version and keeps the creation date`() {
        val created = now.minusDays(4)
        val meta =
            GeneratedPlaylistMeta(
                version = 3,
                generatedAt = now.minusDays(1),
                createdAt = created,
                stamp = "2026-11-05",
                trackCount = 28,
            ).afterRewrite(now, "2026-11-06", 30)

        assertEquals(4, meta.version)
        assertEquals(now, meta.generatedAt)
        assertEquals(created, meta.createdAt)
        assertEquals("2026-11-06", meta.stamp)
        assertEquals(30, meta.trackCount)
    }

    @Test
    fun `a read only pass keeps the version`() {
        val entry =
            GeneratedPlaylistMeta(
                version = 2,
                generatedAt = now.minusDays(1),
                createdAt = now.minusDays(8),
                stamp = "2026-11-05",
                trackCount = 27,
            )
        val encoded = GeneratedPlaylistMetaCodec.encode(mapOf("LP_RS_DAILY_JAMS" to entry))

        val decoded = GeneratedPlaylistMetaCodec.entry(encoded, "LP_RS_DAILY_JAMS")

        assertEquals(entry, decoded)
    }

    @Test
    fun `metadata for several playlists survives a json round trip`() {
        val first = GeneratedPlaylistMeta(version = 5, generatedAt = now, createdAt = now.minusDays(30), stamp = "2026-W45", trackCount = 30)
        val second = GeneratedPlaylistMeta(version = 1, generatedAt = now.minusMinutes(3), trackCount = 29)

        val encoded = GeneratedPlaylistMetaCodec.encode(mapOf("LP_RS_WEEKLY_DISCOVER" to first, "LP_RS_DAILY_EXPLORE" to second))
        val decoded = GeneratedPlaylistMetaCodec.decode(encoded)

        assertEquals(2, decoded.size)
        assertEquals(first, decoded["LP_RS_WEEKLY_DISCOVER"])
        assertEquals(second, decoded["LP_RS_DAILY_EXPLORE"])
    }

    @Test
    fun `a missing or broken store falls back to no metadata`() {
        assertNull(GeneratedPlaylistMetaCodec.entry(null, "LP_RS_DAILY_JAMS"))
        assertNull(GeneratedPlaylistMetaCodec.entry("", "LP_RS_DAILY_JAMS"))
        assertNull(GeneratedPlaylistMetaCodec.entry("not json", "LP_RS_DAILY_JAMS"))
        assertNull(GeneratedPlaylistMetaCodec.entry("""{"LP_RS_DAILY_JAMS":{"version":2}}""", "LP_RS_WEEKLY_JAMS"))
        assertTrue(GeneratedPlaylistMetaCodec.decode("not json").isEmpty())
    }
}
