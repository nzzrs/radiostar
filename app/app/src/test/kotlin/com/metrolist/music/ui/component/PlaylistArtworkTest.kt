/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistArtworkTest {
    @Test
    fun `the same tracks always paint the same artwork`() {
        val seeds = listOf(11, 22, 33, 44)

        assertEquals(GeneratedPlaylistArtwork.blobs(seeds), GeneratedPlaylistArtwork.blobs(seeds))
    }

    @Test
    fun `different tracks paint different artwork`() {
        val first = GeneratedPlaylistArtwork.blobs(listOf(11, 22, 33, 44))
        val second = GeneratedPlaylistArtwork.blobs(listOf(91, 72, 53, 34))

        assertNotEquals(first.map { it.hue }, second.map { it.hue })
    }

    @Test
    fun `artwork uses at most four track seeds`() {
        val blobs = GeneratedPlaylistArtwork.blobs(listOf(1, 2, 3, 4, 5, 6))

        assertEquals(4, blobs.size)
    }

    @Test
    fun `an empty playlist still gets a discreet fallback`() {
        val blobs = GeneratedPlaylistArtwork.blobs(emptyList())

        assertEquals(1, blobs.size)
    }

    @Test
    fun `blobs stay inside the card bounds`() {
        val blobs = GeneratedPlaylistArtwork.blobs(listOf(8, 21, 34, 55))

        blobs.forEach { blob ->
            assertTrue(blob.x in 0f..1f)
            assertTrue(blob.y in 0f..1f)
            assertTrue(blob.radius > 0f)
            assertTrue(blob.radius <= 1f)
            assertTrue(blob.hue in 0f..360f)
        }
    }

    @Test
    fun `hues stay apart so colours do not turn to mud`() {
        val hues = GeneratedPlaylistArtwork.blobs(listOf(10, 20, 30, 40)).map { it.hue }

        hues.forEachIndexed { index, hue ->
            hues.drop(index + 1).forEach { other ->
                val distance = kotlin.math.abs(hue - other).let { minOf(it, 360f - it) }
                assertTrue("hues $hue and $other are too close", distance >= 24f)
            }
        }
    }
}
