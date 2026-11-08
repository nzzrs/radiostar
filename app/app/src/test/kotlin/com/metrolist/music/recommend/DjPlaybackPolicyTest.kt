/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjPlaybackPolicyTest {
    @Test
    fun `the song drops to 22 percent while the dj speaks`() {
        assertEquals(0.22f, DjPlaybackPolicy.duckedVolume(1f), 0.0001f)
        assertEquals(0.11f, DjPlaybackPolicy.duckedVolume(0.5f), 0.0001f)
    }

    @Test
    fun `ducked volume never leaves the valid range`() {
        assertEquals(0f, DjPlaybackPolicy.duckedVolume(0f), 0.0001f)
        assertEquals(0.22f, DjPlaybackPolicy.duckedVolume(2f), 0.0001f)
    }

    @Test
    fun `a paused position inside the track is not the end`() {
        assertFalse(DjPlaybackPolicy.isAtTrackEnd(30_000L, 180_000L))
    }

    @Test
    fun `the final moments count as the end of the track`() {
        assertTrue(DjPlaybackPolicy.isAtTrackEnd(179_800L, 180_000L))
        assertTrue(DjPlaybackPolicy.isAtTrackEnd(180_000L, 180_000L))
    }

    @Test
    fun `an unknown duration never reports the end`() {
        assertFalse(DjPlaybackPolicy.isAtTrackEnd(0L, 0L))
        assertFalse(DjPlaybackPolicy.isAtTrackEnd(10_000L, -1L))
    }
}
