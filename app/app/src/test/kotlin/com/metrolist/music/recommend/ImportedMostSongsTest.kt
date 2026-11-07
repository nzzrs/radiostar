/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedMostSongsTest {
    private fun listen(
        title: String,
        artist: String,
        at: Long,
    ) = ImportedListen(
        service = "listenbrainz",
        title = title,
        artist = artist,
        album = null,
        listenedAt = at,
    )

    @Test
    fun `counts listens inside the window only`() {
        val listens =
            listOf(
                listen("Alpha", "One", 1_000),
                listen("Alpha", "One", 1_100),
                listen("Old", "One", 10),
            )
        val top = ImportedMostSongs.topListens(listens, sinceEpochSeconds = 500, limit = 10)
        assertEquals(1, top.size)
        assertEquals(2, top.first().count)
        assertEquals("Alpha", top.first().title)
    }

    @Test
    fun `matches title and artist case-insensitively`() {
        val listens =
            listOf(
                listen("Alpha", "One", 1_000),
                listen("alpha", "one", 1_100),
                listen("ALPHA", "ONE", 1_200),
            )
        val top = ImportedMostSongs.topListens(listens, sinceEpochSeconds = 0, limit = 10)
        assertEquals(1, top.size)
        assertEquals(3, top.first().count)
        assertEquals("ALPHA", top.first().title)
    }

    @Test
    fun `orders by count and applies the limit`() {
        val listens =
            listOf(
                listen("A", "X", 10),
                listen("B", "X", 20),
                listen("B", "X", 21),
                listen("C", "X", 30),
                listen("C", "X", 31),
                listen("C", "X", 32),
            )
        val top = ImportedMostSongs.topListens(listens, sinceEpochSeconds = 0, limit = 2)
        assertEquals(listOf("C", "B"), top.map { it.title })
        assertTrue(top[0].count > top[1].count)
    }

    @Test
    fun `zero limit returns nothing`() {
        val listens = listOf(listen("A", "X", 10))
        assertTrue(ImportedMostSongs.topListens(listens, 0, 0).isEmpty())
    }
}
