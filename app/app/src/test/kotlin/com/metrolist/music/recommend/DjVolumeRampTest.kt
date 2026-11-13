/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import com.metrolist.music.playback.DjVolumeRamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DjVolumeRampTest {
    @Test
    fun `the ramp starts at the current volume and ends at the ducked volume`() {
        val steps = DjVolumeRamp.stepValues(1f, DjVolumeRamp.duckedTarget(1f))
        assertEquals(1f, steps.first(), 0.0001f)
        assertEquals(0.22f, steps.last(), 0.0001f)
    }

    @Test
    fun `ducking moves monotonically down in equal steps`() {
        val steps = DjVolumeRamp.stepValues(1f, 0.22f)
        assertEquals(DjVolumeRamp.STEPS + 1, steps.size)
        for (index in 1 until steps.size) {
            assertTrue("step $index must not rise", steps[index] <= steps[index - 1])
            assertEquals(0.78f / DjVolumeRamp.STEPS, steps[index - 1] - steps[index], 0.0001f)
        }
    }

    @Test
    fun `restoring moves monotonically back up to the base volume`() {
        val steps = DjVolumeRamp.stepValues(0.22f, 0.8f)
        assertEquals(0.22f, steps.first(), 0.0001f)
        assertEquals(0.8f, steps.last(), 0.0001f)
        for (index in 1 until steps.size) {
            assertTrue("step $index must not fall", steps[index] >= steps[index - 1])
        }
    }

    @Test
    fun `every step stays inside the player volume range`() {
        val steps = DjVolumeRamp.stepValues(-2f, 3f)
        steps.forEach { step ->
            assertTrue("volume $step out of range", step in 0f..1f)
        }
    }

    @Test
    fun `a restore never leaves the music quiet`() {
        val ducked = DjVolumeRamp.duckedTarget(0.65f)
        val restored = DjVolumeRamp.stepValues(ducked, 0.65f)
        assertEquals(0.65f, restored.last(), 0.0001f)
    }

    @Test
    fun `a missing fade still lands on the target`() {
        val steps = DjVolumeRamp.stepValues(1f, 0.22f, steps = 1)
        assertEquals(listOf(1f, 0.22f), steps)
    }

    @Test
    fun `zero steps snap to the target`() {
        val steps = DjVolumeRamp.stepValues(1f, 0.22f, steps = 0)
        assertEquals(listOf(0.22f), steps)
    }

    @Test
    fun `the duck target follows the playback policy factor`() {
        assertEquals(DjPlaybackPolicy.duckedVolume(1f), DjVolumeRamp.duckedTarget(1f), 0.0001f)
        assertEquals(DjPlaybackPolicy.duckedVolume(0.5f), DjVolumeRamp.duckedTarget(0.5f), 0.0001f)
    }

    @Test
    fun `the fade lasts about six hundred milliseconds`() {
        assertEquals(600L, DjVolumeRamp.DURATION_MS)
    }
}
