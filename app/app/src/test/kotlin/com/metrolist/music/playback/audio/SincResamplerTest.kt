/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SincResamplerTest {
    @Test
    fun `length follows the rate ratio`() {
        val input = FloatArray(44_100)
        val output = SincResampler.resample(input, 44_100, 48_000)
        assertEquals(48_000, output.size)
    }

    @Test
    fun `constant signal keeps its amplitude`() {
        val input = FloatArray(4_410) { 0.5f }
        val output = SincResampler.resample(input, 44_100, 48_000)
        for (sample in output) {
            assertTrue("DC gain drifted: $sample", abs(sample - 0.5f) < 1e-3f)
        }
    }

    @Test
    fun `tone keeps its frequency`() {
        val inRate = 44_100
        val frequency = 1_000.0
        val input = FloatArray(4_410) { index ->
            sin(2.0 * PI * frequency * index / inRate).toFloat()
        }

        val output = SincResampler.resample(input, inRate, 48_000)

        var crossings = 0
        for (index in 480 until 4_320) {
            if (output[index] < 0f && output[index + 1] >= 0f) crossings += 1
        }
        // 1 kHz over the 0.08 s measured span gives ~80 negative-going crossings.
        assertTrue(
            "expected ~80 negative-going crossings, got $crossings",
            abs(crossings - 80) <= 2,
        )
    }

    @Test
    fun `equal rates return a copy`() {
        val input = FloatArray(128) { it.toFloat() }
        val output = SincResampler.resample(input, 48_000, 48_000)
        assertArrayEquals(input, output, 0f)
        assertNotSame(input, output)
    }
}
