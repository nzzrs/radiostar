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

    @Test
    fun `session observations are true and persona aware`() {
        val line = DjScript.observation(DjPersona.CHERRY, 4, 12, 3, "es-MX")
        assertTrue(line.contains("4"))
        assertTrue(line.contains("12"))
        assertTrue(line.contains("Cherry"))
        assertTrue(line.length <= 180)
    }

    @Test
    fun `session observations change with the session`() {
        val first = DjScript.observation(DjPersona.LEMON, 1, 1, 1, "en")
        val second = DjScript.observation(DjPersona.LEMON, 6, 18, 4, "en")
        assertTrue(first != second)
    }

    @Test
    fun `a request is acknowledged in spanish`() {
        val line = DjScript.request("ANSIEDAD", "Fuerza Regida", "es-MX")
        assertTrue(line.contains("Pediste"))
        assertTrue(line.contains("ANSIEDAD"))
        assertTrue(line.contains("Fuerza Regida"))
        assertTrue(line.length <= 180)
    }

    @Test
    fun `a request is acknowledged in english`() {
        val line = DjScript.request("ANSIEDAD", "Fuerza Regida", "en-US")
        assertTrue(line.contains("You asked for"))
        assertTrue(line.contains("ANSIEDAD"))
        assertTrue(line.contains("Fuerza Regida"))
    }

    @Test
    fun `a request without an artist still reads well`() {
        assertEquals("Pediste ANSIEDAD. Aquí va.", DjScript.request("ANSIEDAD", "", "es"))
        assertEquals("You asked for ANSIEDAD. Here it is.", DjScript.request("ANSIEDAD", " ", "en"))
    }

    @Test
    fun `a request without a title is silent`() {
        assertEquals("", DjScript.request("  ", "Fuerza Regida", "es"))
    }

    @Test
    fun `the request note follows the listener language`() {
        assertEquals(" By your request.", DjScript.requestNote("en-US"))
        assertEquals(" A petición tuya.", DjScript.requestNote("es-MX"))
        assertFalse(DjScript.requestNote("en") == DjScript.requestNote("es"))
    }

    @Test
    fun `a request line is not the plain announcement`() {
        assertFalse(
            DjScript.request("Uno", "Artista", "es") ==
                DjScript.announcement("Uno", "Artista", "es"),
        )
    }
}
