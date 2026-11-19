package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusPolicyTest {
    private val policy = AudioFocusPolicy(duckMultiplier = 0.2f, maxDuckMs = 25_000L)

    @Test
    fun `duck lowers volume and retains focus`() {
        policy.onEvent(AudioFocusEvent.GAIN, 0L)
        policy.onEvent(AudioFocusEvent.LOSS_TRANSIENT_CAN_DUCK, 1_000L)

        assertTrue(policy.hasFocus)
        assertTrue(policy.isDucking)
        assertEquals(0.2f, policy.volumeMultiplier, 0.0001f)
    }

    @Test
    fun `duck expires after the bound even when gain is never delivered`() {
        policy.onEvent(AudioFocusEvent.LOSS_TRANSIENT_CAN_DUCK, 1_000L)

        assertFalse(policy.expireDuck(25_999L))
        assertEquals(0.2f, policy.volumeMultiplier, 0.0001f)

        assertTrue(policy.expireDuck(26_000L))
        assertFalse(policy.isDucking)
        assertEquals(1f, policy.volumeMultiplier, 0.0001f)
        assertTrue(policy.hasFocus)
    }

    @Test
    fun `gain before the timeout restores immediately`() {
        policy.onEvent(AudioFocusEvent.LOSS_TRANSIENT_CAN_DUCK, 0L)
        policy.onEvent(AudioFocusEvent.GAIN, 5_000L)

        assertFalse(policy.isDucking)
        assertEquals(1f, policy.volumeMultiplier, 0.0001f)
        assertFalse(policy.expireDuck(30_000L))
    }

    @Test
    fun `loss events clear a stale duck multiplier`() {
        policy.onEvent(AudioFocusEvent.LOSS_TRANSIENT_CAN_DUCK, 0L)
        policy.onEvent(AudioFocusEvent.LOSS_TRANSIENT, 2_000L)

        assertFalse(policy.hasFocus)
        assertFalse(policy.isDucking)
        assertEquals(1f, policy.volumeMultiplier, 0.0001f)
    }

    @Test
    fun `focus loss clears focus without ducking`() {
        policy.onEvent(AudioFocusEvent.GAIN, 0L)
        policy.onEvent(AudioFocusEvent.LOSS, 1_000L)

        assertFalse(policy.hasFocus)
        assertFalse(policy.isDucking)
        assertEquals(1f, policy.volumeMultiplier, 0.0001f)
    }

    @Test
    fun `a repeated duck event restarts the watchdog window`() {
        policy.onEvent(AudioFocusEvent.LOSS_TRANSIENT_CAN_DUCK, 0L)
        policy.onEvent(AudioFocusEvent.LOSS_TRANSIENT_CAN_DUCK, 20_000L)

        assertFalse(policy.expireDuck(25_000L))
        assertTrue(policy.expireDuck(45_000L))
        assertEquals(1f, policy.volumeMultiplier, 0.0001f)
    }

    @Test
    fun `fresh focus grant clears a duck left by a lost gain event`() {
        policy.onEvent(AudioFocusEvent.LOSS_TRANSIENT_CAN_DUCK, 0L)

        policy.onFocusRequestResult(granted = true)

        assertTrue(policy.hasFocus)
        assertFalse(policy.isDucking)
        assertEquals(1f, policy.volumeMultiplier, 0.0001f)
    }

    @Test
    fun `abandoning focus resets volume`() {
        policy.onEvent(AudioFocusEvent.LOSS_TRANSIENT_CAN_DUCK, 0L)

        policy.onAbandoned()

        assertFalse(policy.hasFocus)
        assertFalse(policy.isDucking)
        assertEquals(1f, policy.volumeMultiplier, 0.0001f)
    }
}
