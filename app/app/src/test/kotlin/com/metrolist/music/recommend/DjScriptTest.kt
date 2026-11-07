/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjScriptTest {
    @Test
    fun `announces inside the lookahead window`() {
        assertTrue(DjScript.shouldAnnounce(100_000, 108_000, false, true))
    }

    @Test
    fun `stays quiet before the window`() {
        assertFalse(DjScript.shouldAnnounce(80_000, 108_000, false, true))
    }

    @Test
    fun `stays quiet once only a couple of seconds remain`() {
        assertFalse(DjScript.shouldAnnounce(106_000, 108_000, false, true))
    }

    @Test
    fun `never repeats the same next track`() {
        assertFalse(DjScript.shouldAnnounce(100_000, 108_000, true, true))
    }

    @Test
    fun `never announces without a next track`() {
        assertFalse(DjScript.shouldAnnounce(100_000, 108_000, false, false))
    }

    @Test
    fun `unknown duration is not announced`() {
        assertFalse(DjScript.shouldAnnounce(0, 0, false, true))
        assertFalse(DjScript.shouldAnnounce(0, -1, false, true))
    }

    @Test
    fun `spanish wording`() {
        assertEquals(
            "A continuación: ANSIEDAD, de Fuerza Regida.",
            DjScript.announcement("ANSIEDAD", "Fuerza Regida", "es-MX"),
        )
    }

    @Test
    fun `english wording`() {
        assertEquals(
            "Up next: ANSIEDAD by Fuerza Regida.",
            DjScript.announcement("ANSIEDAD", "Fuerza Regida", "en-US"),
        )
    }

    @Test
    fun `missing artist still reads well`() {
        assertEquals("A continuación: ANSIEDAD.", DjScript.announcement("ANSIEDAD", "", "es"))
        assertEquals("Up next: ANSIEDAD.", DjScript.announcement("ANSIEDAD", "  ", "en"))
    }

    @Test
    fun `blank title produces no speech`() {
        assertEquals("", DjScript.announcement("  ", "Fuerza Regida", "es"))
    }

    @Test
    fun `intro mentions the first track and the persona`() {
        val line = DjScript.intro(DjPersona.CHERRY, "ANSIEDAD", "Fuerza Regida", "es-MX")
        assertTrue(line.contains("Cherry"))
        assertTrue(line.contains("ANSIEDAD"))
        assertTrue(line.contains("Fuerza Regida"))
        assertFalse(line.contains("let the beat", ignoreCase = true))
    }

    @Test
    fun `intro is not generic and changes with the track`() {
        val first = DjScript.intro(DjPersona.LEMON, "One", "Artist A", "en")
        val second = DjScript.intro(DjPersona.LEMON, "Two", "Artist B", "en")
        assertTrue(first.contains("One"))
        assertTrue(second.contains("Two"))
        assertFalse(first == second)
    }

    @Test
    fun `intro without a title is silent`() {
        assertEquals("", DjScript.intro(DjPersona.CHERRY, "  ", "Artist", "es"))
    }

    @Test
    fun `whitespace and newlines are collapsed`() {
        assertEquals(
            "A continuación: Una canción, de Un artista.",
            DjScript.announcement("Una \n  canción", "Un\tartista", "es"),
        )
    }
}
