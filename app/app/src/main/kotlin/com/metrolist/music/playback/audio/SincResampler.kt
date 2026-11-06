/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * One-shot rational resampler for the analysis tap.
 *
 * YouTube Music delivers 44.1 kHz or 48 kHz; CLAP's feature extractor expects
 * 48 kHz. Resampling happens once per 10 s window on the worker thread, so a
 * straightforward Hann-windowed sinc is cheap enough and keeps the code small.
 *
 * Linear interpolation would be cheaper but folds high frequencies back into
 * the mel bands, which is exactly what the embedding is measuring.
 */
object SincResampler {
    private const val TAPS = 32
    private const val HALF = TAPS / 2

    fun resample(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        require(inRate > 0 && outRate > 0) { "sample rates must be positive" }
        if (input.isEmpty() || inRate == outRate) return input.copyOf()

        val divisor = gcd(inRate, outRate)
        val up = outRate / divisor
        val down = inRate / divisor
        val outLength = ((input.size.toLong() * up) / down).toInt().coerceAtLeast(1)
        val kernel = buildKernel(up)
        val last = input.size - 1

        val output = FloatArray(outLength)
        for (n in 0 until outLength) {
            val position = n.toLong() * down
            val base = (position / up).toInt()
            val phase = (position % up).toInt()
            val coefficients = kernel[phase]

            var accumulator = 0f
            var index = base - HALF + 1
            var tap = 0

            // Left edge: clamp to the first sample.
            while (tap < TAPS && index < 0) {
                accumulator += coefficients[tap] * input[0]
                index += 1
                tap += 1
            }
            // Body: the hot path, no bounds checks.
            while (tap < TAPS && index <= last) {
                accumulator += coefficients[tap] * input[index]
                index += 1
                tap += 1
            }
            // Right edge: clamp to the last sample.
            while (tap < TAPS) {
                accumulator += coefficients[tap] * input[last]
                tap += 1
            }

            output[n] = accumulator
        }
        return output
    }

    private fun buildKernel(up: Int): Array<FloatArray> = Array(up) { phase ->
        val fraction = phase.toDouble() / up
        val raw = DoubleArray(TAPS)
        var sum = 0.0
        for (k in 0 until TAPS) {
            val distance = fraction + (HALF - 1 - k)
            val sinc = if (abs(distance) < 1e-9) 1.0 else sin(PI * distance) / (PI * distance)
            val window = if (abs(distance) <= HALF) 0.5 + 0.5 * cos(PI * distance / HALF) else 0.0
            raw[k] = sinc * window
            sum += raw[k]
        }
        val normaliser = if (abs(sum) < 1e-12) 1.0 else 1.0 / sum
        FloatArray(TAPS) { (raw[it] * normaliser).toFloat() }
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val remainder = x % y
            x = y
            y = remainder
        }
        return x
    }
}
