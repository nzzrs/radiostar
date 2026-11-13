/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjSchedulePolicyTest {
    @Test
    fun `a short clip starts at the five second floor`() {
        val clipMs = 2_500L
        assertEquals(5_000L, DjSchedulePolicy.leadMs(clipMs))
        assertTrue(DjSchedulePolicy.decide(4_900L, clipMs).start)
        assertFalse(DjSchedulePolicy.decide(6_000L, clipMs).start)
    }

    @Test
    fun `a long clip starts early enough to end at the boundary`() {
        val clipMs = 10_000L
        val decision = DjSchedulePolicy.decide(10_600L, clipMs)
        assertEquals(10_600L, decision.leadMs)
        assertTrue(decision.start)
        assertEquals("start", decision.reason)
    }

    @Test
    fun `a long clip does not start before its lead`() {
        val decision = DjSchedulePolicy.decide(10_700L, 10_000L)
        assertFalse(decision.start)
        assertEquals("before-lead", decision.reason)
    }

    @Test
    fun `the clip ending is computed from clip duration plus fade`() {
        assertEquals(
            DjSchedulePolicy.leadMs(clipDurationMs = 8_000L, fadeMs = 600L),
            8_600L,
        )
        assertTrue(DjSchedulePolicy.decide(8_600L, 8_000L, fadeMs = 600L).start)
        assertFalse(DjSchedulePolicy.decide(8_601L, 8_000L, fadeMs = 600L).start)
    }

    @Test
    fun `never starts before the lookahead window`() {
        val window = 12_000L
        assertFalse(DjSchedulePolicy.decide(12_500L, 15_000L, windowMs = window).start)
        assertTrue(DjSchedulePolicy.decide(12_000L, 15_000L, windowMs = window).start)
        assertEquals("before-window", DjSchedulePolicy.decide(12_500L, 15_000L, windowMs = window).reason)
    }

    @Test
    fun `the window is capped at thirty seconds`() {
        assertEquals(DjSchedulePolicy.MAX_WINDOW_MS, DjSchedulePolicy.window(60_000L))
        val decision = DjSchedulePolicy.decide(29_999L, 40_000L, windowMs = 60_000L)
        assertEquals(30_000L, decision.leadMs)
        assertTrue(decision.start)
        assertFalse(DjSchedulePolicy.decide(30_001L, 40_000L, windowMs = 60_000L).start)
    }

    @Test
    fun `the default window keeps a long clip inside twelve seconds`() {
        assertEquals(DjSchedulePolicy.DEFAULT_WINDOW_MS, DjSchedulePolicy.window(12_000L))
        assertFalse(DjSchedulePolicy.decide(13_000L, 20_000L).start)
        assertTrue(DjSchedulePolicy.decide(12_000L, 20_000L).start)
    }

    @Test
    fun `a clip that is not ready never starts`() {
        val decision = DjSchedulePolicy.decide(8_000L, 8_000L, clipReady = false)
        assertFalse(decision.start)
        assertEquals("clip-not-ready", decision.reason)
    }

    @Test
    fun `a not ready clip is skipped even at the ideal boundary`() {
        val decision = DjSchedulePolicy.decide(8_600L, 8_000L, clipReady = false)
        assertFalse(decision.start)
    }

    @Test
    fun `zero remaining time never starts`() {
        assertFalse(DjSchedulePolicy.decide(0L, 8_000L).start)
        assertFalse(DjSchedulePolicy.decide(-1_000L, 8_000L).start)
        assertEquals("no-remaining", DjSchedulePolicy.decide(0L, 8_000L).reason)
    }

    @Test
    fun `an unknown clip duration never starts`() {
        assertFalse(DjSchedulePolicy.decide(8_000L, 0L).start)
        assertFalse(DjSchedulePolicy.decide(8_000L, -1L).start)
        assertEquals("unknown-clip", DjSchedulePolicy.decide(8_000L, 0L).reason)
    }

    @Test
    fun `a zero or negative window falls back to the minimum lead`() {
        assertEquals(DjSchedulePolicy.MIN_LEAD_MS, DjSchedulePolicy.window(0L))
        assertEquals(DjSchedulePolicy.MIN_LEAD_MS, DjSchedulePolicy.window(-5_000L))
    }

    @Test
    fun `a slower fade pushes the start earlier`() {
        val fast = DjSchedulePolicy.leadMs(8_000L, fadeMs = 300L)
        val slow = DjSchedulePolicy.leadMs(8_000L, fadeMs = 1_500L)
        assertTrue(slow > fast)
    }

    @Test
    fun `the scheduler compensates for tick granularity`() {
        val ideal = DjSchedulePolicy.leadMs(8_000L)
        val compensated = DjSchedulePolicy.leadMs(8_000L, tickMs = 2_000L)
        assertTrue(compensated > ideal)
        assertFalse(DjSchedulePolicy.decide(10_000L, 8_000L).start)
        assertTrue(DjSchedulePolicy.decide(10_000L, 8_000L, tickMs = 2_000L).start)
    }

    @Test
    fun `speech duration is estimated for phone tts`() {
        val short = DjSchedulePolicy.estimateSpeechDurationMs("Up next: One.")
        val long = DjSchedulePolicy.estimateSpeechDurationMs("Up next: One by Two, and here is a much longer link for the listeners.")
        assertTrue(short > 0L)
        assertTrue(long > short)
    }
}
