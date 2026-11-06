/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicalKeyTest {
    @Test
    fun `camelot number for major keys`() {
        assertEquals(8, MusicalKey(0, minor = false).camelot) // C major
        assertEquals(9, MusicalKey(7, minor = false).camelot) // G major
        assertEquals(1, MusicalKey(11, minor = false).camelot) // B major
        assertEquals(7, MusicalKey(5, minor = false).camelot) // F major
    }

    @Test
    fun `camelot number for minor keys`() {
        assertEquals(8, MusicalKey(9, minor = true).camelot) // A minor
        assertEquals(9, MusicalKey(4, minor = true).camelot) // E minor
        assertEquals(1, MusicalKey(8, minor = true).camelot) // G# minor
        assertEquals(5, MusicalKey(0, minor = true).camelot) // C minor
    }

    @Test
    fun `names use sharps`() {
        assertEquals("C major", MusicalKey(0, false).name)
        assertEquals("A minor", MusicalKey(9, true).name)
        assertEquals("F# minor", MusicalKey(6, true).name)
    }

    @Test
    fun `same key has zero distance`() {
        assertEquals(0, MusicalKey(9, true).harmonicDistance(MusicalKey(9, true)))
    }

    @Test
    fun `relative and adjacent camelot keys are neighbours`() {
        val aMinor = MusicalKey(9, true)
        assertEquals(1, aMinor.harmonicDistance(MusicalKey(0, false))) // C major, 8B
        assertEquals(1, aMinor.harmonicDistance(MusicalKey(4, true))) // E minor, 9A
        assertEquals(1, aMinor.harmonicDistance(MusicalKey(2, true))) // D minor, 7A
    }

    @Test
    fun `distant keys cost two`() {
        assertEquals(2, MusicalKey(9, true).harmonicDistance(MusicalKey(6, true))) // F# minor
    }

    @Test
    fun `wrapping around the wheel stays small`() {
        assertEquals(1, MusicalKey(4, false).harmonicDistance(MusicalKey(11, false))) // 12B to 1B
    }

    @Test
    fun `pitch classes outside the octave are normalised`() {
        assertEquals(MusicalKey(9, true), MusicalKey(21, true))
        assertEquals(MusicalKey(0, false), MusicalKey(-12, false))
    }

    @Test
    fun `valid key check`() {
        assertTrue(MusicalKey(0, false).isValid())
        assertFalse(MusicalKey(12, false).isValid())
        assertFalse(MusicalKey(-1, true).isValid())
    }
}
