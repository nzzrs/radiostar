/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioOrdererTest {
    private fun entry(id: String, score: Double = 0.5) =
        SlateEntry(
            candidate =
                Candidate(
                    id = id,
                    title = "title-$id",
                    artists = listOf("artist-$id"),
                    vector = doubleArrayOf(1.0),
                ),
            score = score,
        )

    private fun profile(
        bpm: Double? = null,
        key: MusicalKey? = null,
        rms: Float? = null,
    ) = AudioProfile(bpm = bpm, key = key, rms = rms)

    @Test
    fun `greedy path follows the closest tempo`() {
        val entries = listOf(entry("a"), entry("b"), entry("c"), entry("d"))
        val profiles =
            mapOf(
                "a" to profile(bpm = 120.0),
                "b" to profile(bpm = 90.0),
                "c" to profile(bpm = 128.0),
                "d" to profile(bpm = 125.0),
            )
        val reference = profile(bpm = 120.0)

        val ordered = AudioOrderer.harmonicPath(entries, profiles, reference)

        assertEquals(listOf("a", "d", "c", "b"), ordered.map { it.candidate.id })
    }

    @Test
    fun `harmonically close keys beat a close tempo`() {
        val entries = listOf(entry("nearTempo"), entry("sameKey"))
        val profiles =
            mapOf(
                "nearTempo" to profile(bpm = 121.0, key = MusicalKey(6, true)),
                "sameKey" to profile(bpm = 132.0, key = MusicalKey(9, true)),
            )
        val reference = profile(bpm = 120.0, key = MusicalKey(9, true))

        val ordered = AudioOrderer.harmonicPath(entries, profiles, reference)

        assertEquals(listOf("sameKey", "nearTempo"), ordered.map { it.candidate.id })
    }

    @Test
    fun `unknown candidates keep their slots`() {
        val entries = listOf(entry("x"), entry("a"), entry("y"), entry("b"))
        val profiles =
            mapOf(
                "a" to profile(bpm = 128.0),
                "b" to profile(bpm = 100.0),
            )
        val reference = profile(bpm = 120.0)

        val ordered = AudioOrderer.harmonicPath(entries, profiles, reference)

        assertEquals(listOf("x", "a", "y", "b"), ordered.map { it.candidate.id })
    }

    @Test
    fun `without profiles the slate is unchanged`() {
        val entries = listOf(entry("a"), entry("b"))

        assertEquals(entries, AudioOrderer.harmonicPath(entries, emptyMap(), null))
    }

    @Test
    fun `without a reference the path starts from the quietest track`() {
        val entries = listOf(entry("a"), entry("b"), entry("c"))
        val profiles =
            mapOf(
                "a" to profile(rms = 0.6f, bpm = 120.0),
                "b" to profile(rms = 0.2f, bpm = 125.0),
                "c" to profile(rms = 0.4f, bpm = 118.0),
            )

        val ordered = AudioOrderer.harmonicPath(entries, profiles, null)

        assertEquals("b", ordered.first().candidate.id)
    }

    @Test
    fun `one known track is left in place`() {
        val entries = listOf(entry("a"), entry("b"))
        val profiles = mapOf("a" to profile(bpm = 130.0))

        assertEquals(entries, AudioOrderer.harmonicPath(entries, profiles, null))
    }

    @Test
    fun `tempo distance does not depend on direction`() {
        val reference = profile(bpm = 120.0)
        val slower = AudioOrderer.transitionCost(reference, profile(bpm = 110.0))
        val faster = AudioOrderer.transitionCost(reference, profile(bpm = 130.0))

        assertEquals(slower, faster, 1e-9)
    }
}
