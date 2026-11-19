package com.metrolist.music.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSinkStallPolicyTest {
    private val policy = AudioSinkStallPolicy(stallTimeoutMs = 4_000L, recoveryCooldownMs = 30_000L)

    private fun evaluate(
        nowMs: Long,
        frames: Long,
        track: String? = "track-a",
        eligible: Boolean = true,
    ) = policy.evaluate(
        nowMs = nowMs,
        eligible = eligible,
        framesDelivered = frames,
        trackKey = track,
    )

    @Test
    fun `advancing frames never trigger recovery`() {
        assertEquals(AudioSinkStallPolicy.Decision.NONE, evaluate(0L, 0L))
        assertEquals(AudioSinkStallPolicy.Decision.NONE, evaluate(1_000L, 10L))
        assertEquals(AudioSinkStallPolicy.Decision.NONE, evaluate(2_000L, 20L))
        assertEquals(AudioSinkStallPolicy.Decision.NONE, evaluate(9_000L, 30L))
    }

    @Test
    fun `stalled frames recover after the timeout`() {
        assertEquals(AudioSinkStallPolicy.Decision.NONE, evaluate(0L, 100L))
        assertEquals(AudioSinkStallPolicy.Decision.NONE, evaluate(3_999L, 100L))
        assertEquals(AudioSinkStallPolicy.Decision.RECOVER, evaluate(4_000L, 100L))
    }

    @Test
    fun `recovery happens at most once per track`() {
        evaluate(0L, 100L)
        assertEquals(AudioSinkStallPolicy.Decision.RECOVER, evaluate(4_000L, 100L))
        assertEquals(AudioSinkStallPolicy.Decision.NONE, evaluate(60_000L, 100L))
        assertEquals(AudioSinkStallPolicy.Decision.NONE, evaluate(120_000L, 100L))
    }

    @Test
    fun `recovery for a later track waits for the cooldown`() {
        evaluate(0L, 100L)
        assertEquals(AudioSinkStallPolicy.Decision.RECOVER, evaluate(4_000L, 100L))

        assertEquals(AudioSinkStallPolicy.Decision.NONE, evaluate(10_000L, 0L, track = "track-b"))
        assertEquals(AudioSinkStallPolicy.Decision.NONE, evaluate(14_000L, 0L, track = "track-b"))
        assertEquals(AudioSinkStallPolicy.Decision.RECOVER, evaluate(34_000L, 0L, track = "track-b"))
        assertEquals(AudioSinkStallPolicy.Decision.NONE, evaluate(44_000L, 0L, track = "track-b"))
    }

    @Test
    fun `pausing rebaselines instead of recovering on resume`() {
        evaluate(0L, 100L)
        assertEquals(AudioSinkStallPolicy.Decision.NONE, evaluate(3_000L, 100L, eligible = false))
        assertEquals(AudioSinkStallPolicy.Decision.NONE, evaluate(10_000L, 100L))
        assertEquals(AudioSinkStallPolicy.Decision.RECOVER, evaluate(14_000L, 100L))
    }

    @Test
    fun `reset re-arms recovery for the same track`() {
        evaluate(0L, 100L)
        assertEquals(AudioSinkStallPolicy.Decision.RECOVER, evaluate(4_000L, 100L))

        policy.reset()

        assertEquals(AudioSinkStallPolicy.Decision.NONE, evaluate(60_000L, 100L))
        assertEquals(AudioSinkStallPolicy.Decision.RECOVER, evaluate(64_000L, 100L))
    }

    @Test
    fun `a new track restarts the frame baseline`() {
        evaluate(0L, 100L)
        assertEquals(AudioSinkStallPolicy.Decision.NONE, evaluate(2_000L, 100L, track = "track-b"))
        assertEquals(AudioSinkStallPolicy.Decision.NONE, evaluate(5_000L, 200L, track = "track-b"))
        assertEquals(AudioSinkStallPolicy.Decision.RECOVER, evaluate(9_000L, 200L, track = "track-b"))
    }
}
