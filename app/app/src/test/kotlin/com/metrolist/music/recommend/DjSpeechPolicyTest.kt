/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjSpeechPolicyTest {
    @Test
    fun `never speaks before a track has played`() {
        val decision = DjSpeechPolicy.decide(0, 0L, 0.0, true, 0, 0.0)
        assertFalse(decision.speak)
    }

    @Test
    fun `speaks after three tracks at the latest`() {
        val decision = DjSpeechPolicy.decide(3, 0L, 0.5, false, 1, 1.0)
        assertTrue(decision.speak)
        assertTrue(decision.reason == "cadence")
    }

    @Test
    fun `speaks after two tracks`() {
        val decision = DjSpeechPolicy.decide(2, 0L, 0.5, false, 1, 1.0)
        assertTrue(decision.speak)
    }

    @Test
    fun `often stays quiet after one familiar track`() {
        val decision = DjSpeechPolicy.decide(1, 30_000L, 0.5, false, 2, 0.9)
        assertFalse(decision.speak)
    }

    @Test
    fun `warns about a likely skip risk after one track`() {
        val decision = DjSpeechPolicy.decide(1, 30_000L, -0.8, false, 2, 0.9)
        assertTrue(decision.speak)
        assertTrue(decision.reason == "skip-risk")
        assertFalse(decision.useFact)
    }

    @Test
    fun `a long silence forces a link`() {
        val decision =
            DjSpeechPolicy.decide(
                tracksSinceLastSpeech = 1,
                elapsedSinceLastSpeechMs = DjSpeechPolicy.LONG_GAP_MS,
                nextTrackBias = 0.5,
                nextArtistChanged = false,
                linksSpoken = 2,
                roll = 0.9,
            )
        assertTrue(decision.speak)
        assertTrue(decision.reason == "long-gap")
    }

    @Test
    fun `a session fact appears occasionally, not on every link`() {
        val noFact = DjSpeechPolicy.decide(2, 0L, 0.5, false, linksSpoken = 1, roll = 0.0)
        val withFact = DjSpeechPolicy.decide(2, 0L, 0.5, false, linksSpoken = 3, roll = 0.0)

        assertFalse(noFact.useFact)
        assertTrue(withFact.useFact)
    }

    @Test
    fun `an artist change can earn an early link`() {
        val decision = DjSpeechPolicy.decide(1, 0L, 0.5, true, 1, 0.1)
        assertTrue(decision.speak)
        assertTrue(decision.reason == "artist-change")
    }
}
