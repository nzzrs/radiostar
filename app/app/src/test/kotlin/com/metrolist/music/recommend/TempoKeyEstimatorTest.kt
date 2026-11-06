/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TempoKeyEstimatorTest {
    private val rate = 48_000

    @Test
    fun `detects 90 bpm from a click track`() {
        val features = TempoKeyEstimator.estimate(clickTrack(90.0), rate)

        assertNotNull(features.bpm)
        assertEquals(90.0, features.bpm!!, 2.5)
        assertTrue("confidence ${features.tempoConfidence}", features.tempoConfidence > 0.15)
    }

    @Test
    fun `detects 120 bpm from a click track`() {
        val features = TempoKeyEstimator.estimate(clickTrack(120.0), rate)

        assertEquals(120.0, features.bpm!!, 2.5)
    }

    @Test
    fun `detects 140 bpm from a click track`() {
        val features = TempoKeyEstimator.estimate(clickTrack(140.0), rate)

        assertEquals(140.0, features.bpm!!, 2.5)
    }

    @Test
    fun `detects a minor from an A minor seventh chord`() {
        val features = TempoKeyEstimator.estimate(chord(listOf(110.0, 261.63, 329.63, 392.0)), rate)

        assertEquals(
            "detected ${features.key} confidence ${features.keyConfidence}",
            MusicalKey(9, minor = true),
            features.key,
        )
        assertTrue("confidence ${features.keyConfidence}", features.keyConfidence > 0.02)
    }

    @Test
    fun `detects c major from a C major seventh chord`() {
        val features = TempoKeyEstimator.estimate(chord(listOf(130.81, 329.63, 392.0, 493.88)), rate)

        assertEquals(MusicalKey(0, minor = false), features.key)
    }

    @Test
    fun `detects e minor from an E minor seventh chord`() {
        val features = TempoKeyEstimator.estimate(chord(listOf(82.41, 246.94, 329.63, 392.0)), rate)

        assertEquals(MusicalKey(4, minor = true), features.key)
    }

    @Test
    fun `silence gives no tempo and no key`() {
        val features = TempoKeyEstimator.estimate(FloatArray(rate * 3), rate)

        assertNull(features.bpm)
        assertNull(features.key)
    }

    @Test
    fun `white noise is not a confident key`() {
        val random = java.util.Random(7)
        val noise = FloatArray(rate * 3) { (random.nextFloat() - 0.5f) * 0.4f }

        val features = TempoKeyEstimator.estimate(noise, rate)

        assertTrue("key ${features.key} confidence ${features.keyConfidence}", features.keyConfidence < 0.35)
    }

    @Test
    fun `short input is rejected without throwing`() {
        val features = TempoKeyEstimator.estimate(FloatArray(100), rate)

        assertNull(features.bpm)
        assertNull(features.key)
    }

    @Test
    fun `analysis time is reported`() {
        val features = TempoKeyEstimator.estimate(clickTrack(120.0), rate)

        assertTrue(features.analysisMs >= 0L)
    }

    private fun clickTrack(bpm: Double, seconds: Double = 10.0): FloatArray {
        val samples = FloatArray((seconds * rate).toInt())
        val period = (60.0 / bpm * rate).toInt()
        var onset = 0
        while (onset < samples.size) {
            val length = minOf(2_400, samples.size - onset)
            for (index in 0 until length) {
                val time = index.toDouble() / rate
                samples[onset + index] =
                    (0.8 * exp(-time * 45.0) * sin(2.0 * PI * 1_200.0 * time)).toFloat()
            }
            onset += period
        }
        return samples
    }

    private fun chord(frequencies: List<Double>, seconds: Double = 6.0): FloatArray {
        val samples = FloatArray((seconds * rate).toInt())
        for (index in samples.indices) {
            val time = index.toDouble() / rate
            var value = 0.0
            frequencies.forEachIndexed { position, frequency ->
                val amplitude = if (position == 0) 0.35 else 0.2
                value += amplitude * sin(2.0 * PI * frequency * time)
            }
            samples[index] = value.toFloat()
        }
        return samples
    }
}
