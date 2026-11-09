/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjSessionStateTest {
    @After
    fun tearDown() {
        DjSessionState.idle()
    }

    @Test
    fun `dj session is active while speaking`() {
        DjSessionState.speaking(DjPersona.CHERRY)

        val state = DjSessionState.state.value
        assertTrue(state.active)
        assertTrue(state.speaking)
        assertFalse(state.queueEditable)
        assertFalse(state.transportControlsAvailable)
    }

    @Test
    fun `dj session stays active after the voice stops`() {
        DjSessionState.speaking(DjPersona.LEMON)
        DjSessionState.playing(DjPersona.LEMON)

        val state = DjSessionState.state.value
        assertTrue(state.active)
        assertFalse(state.speaking)
        assertFalse(state.queueEditable)
        assertFalse(state.transportControlsAvailable)
    }

    @Test
    fun `dismissing the speaking view keeps the dj session active`() {
        DjSessionState.speaking(DjPersona.BERRY)
        DjSessionState.dismissSpeaking()

        val state = DjSessionState.state.value
        assertTrue(state.active)
        assertFalse(state.speaking)
    }

    @Test
    fun `idle restores normal playback controls`() {
        DjSessionState.speaking(DjPersona.CHERRY)
        DjSessionState.idle()

        val state = DjSessionState.state.value
        assertFalse(state.active)
        assertTrue(state.queueEditable)
        assertTrue(state.transportControlsAvailable)
    }
}
