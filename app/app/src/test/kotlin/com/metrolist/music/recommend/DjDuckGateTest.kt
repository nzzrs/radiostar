/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjDuckGateTest {
    @Test
    fun `nothing is ducked before the audio starts`() {
        val gate = DjDuckGate()
        assertFalse(gate.ducked)
        assertFalse(gate.onPlaybackStopped())
    }

    @Test
    fun `the first audible frame requests the duck`() {
        val gate = DjDuckGate()
        assertTrue(gate.onPlaybackStarted())
        assertTrue(gate.ducked)
    }

    @Test
    fun `a second start does not duck twice`() {
        val gate = DjDuckGate()
        assertTrue(gate.onPlaybackStarted())
        assertFalse(gate.onPlaybackStarted())
        assertTrue(gate.ducked)
    }

    @Test
    fun `finishing restores the music once`() {
        val gate = DjDuckGate()
        gate.onPlaybackStarted()
        assertTrue(gate.onPlaybackStopped())
        assertFalse(gate.ducked)
        assertFalse(gate.onPlaybackStopped())
    }

    @Test
    fun `an attempt that never spoke leaves the music alone`() {
        val gate = DjDuckGate()
        assertFalse(gate.onPlaybackStopped())
        assertFalse(gate.ducked)
    }

    @Test
    fun `a session reset clears a pending duck`() {
        val gate = DjDuckGate()
        gate.onPlaybackStarted()
        gate.reset()
        assertFalse(gate.ducked)
        assertFalse(gate.onPlaybackStopped())
    }
}
