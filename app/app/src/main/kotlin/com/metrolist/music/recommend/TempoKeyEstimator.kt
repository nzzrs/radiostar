/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class AudioFeatures(
    val bpm: Double?,
    val tempoConfidence: Double,
    val key: MusicalKey?,
    val keyConfidence: Double,
    val analysisMs: Long,
)

/**
 * Estimates tempo and key from one 10 s mono window, without native code.
 *
 * Tempo: spectral-flux onset envelope, autocorrelation over 70-180 BPM with a
 * 120 BPM prior and a comb term that keeps the beat from collapsing to half
 * time.
 *
 * Key: FFT chroma with a bass boost, correlated against the Krumhansl-Kessler
 * major and minor profiles for all 12 roots.
 *
 * Both estimates are deliberately cheap: one pass of 1024-point FFTs for the
 * envelope and one pass of 4096-point FFTs for the chroma, about 6 M multiply
 * accumulates for 10 s of 48 kHz audio.
 */
object TempoKeyEstimator {
    private const val TEMPO_FRAME = 1024
    private const val TEMPO_HOP = 512
    // 16k bins give 2.9 Hz resolution at 48 kHz, enough to place a 110 Hz bass
    // note on A instead of the neighbouring semitone.
    private const val KEY_FRAME = 16384
    private const val KEY_HOP = KEY_FRAME
    private const val MIN_BPM = 70.0
    private const val MAX_BPM = 180.0
    private const val BPM_PRIOR = 120.0
    private const val BPM_PRIOR_OCTAVES = 0.9
    private const val MIN_KEY_CORRELATION = 0.45

    fun estimate(samples: FloatArray, sampleRate: Int): AudioFeatures {
        val started = System.nanoTime()
        if (sampleRate < 8_000 || samples.size < KEY_FRAME || rms(samples) < 1e-5f) {
            return AudioFeatures(null, 0.0, null, 0.0, elapsedMs(started))
        }

        val tempo = estimateTempo(samples, sampleRate)
        val key = estimateKey(samples, sampleRate)
        return AudioFeatures(
            bpm = tempo?.first,
            tempoConfidence = tempo?.second ?: 0.0,
            key = key?.first,
            keyConfidence = key?.second ?: 0.0,
            analysisMs = elapsedMs(started),
        )
    }

    /** Returns bpm and confidence, or null when the envelope is flat. */
    private fun estimateTempo(samples: FloatArray, sampleRate: Int): Pair<Double, Double>? {
        val envelope = onsetEnvelope(samples, sampleRate) ?: return null
        val envRate = sampleRate.toDouble() / TEMPO_HOP
        val minLag = max(1, (60.0 * envRate / MAX_BPM).roundToInt())
        val maxLag = min(envelope.size / 2, (60.0 * envRate / MIN_BPM).roundToInt())
        if (maxLag <= minLag) return null

        val acf = DoubleArray(maxLag * 2 + 1) { lag -> autocorrelation(envelope, lag) }
        val zero = acf[0].coerceAtLeast(1e-12)
        var bestScore = -1.0
        var bestLag = 0
        for (lag in minLag..maxLag) {
            val bpm = 60.0 * envRate / lag
            val prior = exp(-0.5 * square(ln(bpm / BPM_PRIOR) / ln(2.0) / BPM_PRIOR_OCTAVES))
            val harmonic = if (lag * 2 < acf.size) acf[lag * 2] else 0.0
            val score = (acf[lag] + 0.5 * harmonic) * prior
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag == 0) return null

        val confidence = (acf[bestLag] / zero).coerceIn(0.0, 1.0)
        if (confidence < 0.08) return null

        val refined = refineLag(acf, bestLag)
        val bpm = 60.0 * envRate / refined
        return bpm to confidence
    }

    private fun onsetEnvelope(samples: FloatArray, sampleRate: Int): DoubleArray? {
        val frames = (samples.size - TEMPO_FRAME) / TEMPO_HOP + 1
        if (frames < 8) return null
        val bins = min(TEMPO_FRAME / 2, (8_000.0 * TEMPO_FRAME / sampleRate).toInt())
        var previous = FloatArray(bins)
        val envelope = DoubleArray(frames)

        for (frame in 0 until frames) {
            val offset = frame * TEMPO_HOP
            val magnitude = Fft.magnitude(samples, offset, TEMPO_FRAME)
            var flux = 0.0
            for (bin in 1 until bins) {
                val difference = magnitude[bin] - previous[bin]
                if (difference > 0f) flux += difference
            }
            envelope[frame] = flux
            previous = magnitude
        }

        // Remove the local mean so steady loud sections do not dominate, then
        // normalise by the envelope's own deviation.
        val local = 23
        var mean = 0.0
        for (value in envelope) mean += value
        mean /= envelope.size
        var variance = 0.0
        for (index in envelope.indices) {
            var sum = 0.0
            var count = 0
            for (neighbour in max(0, index - local)..min(envelope.size - 1, index + local)) {
                sum += envelope[neighbour]
                count += 1
            }
            envelope[index] = max(0.0, envelope[index] - sum / count)
            variance += envelope[index] * envelope[index]
        }
        val deviation = sqrt(variance / envelope.size)
        if (deviation < 1e-9) return null
        for (index in envelope.indices) envelope[index] /= deviation
        return envelope
    }

    private fun autocorrelation(envelope: DoubleArray, lag: Int): Double {
        if (lag <= 0) {
            var energy = 0.0
            for (value in envelope) energy += value * value
            return energy / envelope.size
        }
        if (lag >= envelope.size) return 0.0
        var sum = 0.0
        for (index in 0 until envelope.size - lag) sum += envelope[index] * envelope[index + lag]
        return sum / (envelope.size - lag)
    }

    private fun refineLag(acf: DoubleArray, lag: Int): Double {
        if (lag <= 0 || lag + 1 >= acf.size) return lag.toDouble()
        val left = acf[lag - 1]
        val centre = acf[lag]
        val right = acf[lag + 1]
        val denominator = left - 2.0 * centre + right
        if (abs(denominator) < 1e-9) return lag.toDouble()
        val delta = 0.5 * (left - right) / denominator
        return lag + delta.coerceIn(-0.5, 0.5)
    }

    private fun estimateKey(samples: FloatArray, sampleRate: Int): Pair<MusicalKey, Double>? {
        val frames = (samples.size - KEY_FRAME) / KEY_HOP + 1
        if (frames < 1) return null
        val chroma = DoubleArray(12)
        val minBin = max(1, (55.0 * KEY_FRAME / sampleRate).roundToInt())
        val maxBin = min(KEY_FRAME / 2, (2_000.0 * KEY_FRAME / sampleRate).roundToInt())

        for (frame in 0 until frames) {
            val offset = frame * KEY_HOP
            val magnitude = Fft.magnitude(samples, offset, KEY_FRAME)
            for (bin in minBin until maxBin) {
                val frequency = bin.toDouble() * sampleRate / KEY_FRAME
                val midi = 69.0 + 12.0 * log2(frequency / 440.0)
                val pitchClass = Math.floorMod(midi.roundToInt(), 12)
                val bassBoost = if (frequency < 260.0) 1.8 else 1.0
                chroma[pitchClass] += magnitude[bin] * bassBoost
            }
        }

        var total = 0.0
        for (value in chroma) total += value
        if (total < 1e-9) return null
        for (index in chroma.indices) chroma[index] /= total

        var bestRoot = 0
        var bestMinor = false
        var best = -1.0
        var second = -1.0
        for (minor in booleanArrayOf(false, true)) {
            val profile = if (minor) MINOR_PROFILE else MAJOR_PROFILE
            for (root in 0..11) {
                val rotated = DoubleArray(12) { profile[Math.floorMod(it - root, 12)] }
                val correlation = correlation(chroma, rotated)
                if (correlation > best) {
                    second = best
                    best = correlation
                    bestRoot = root
                    bestMinor = minor
                } else if (correlation > second) {
                    second = correlation
                }
            }
        }

        if (best < MIN_KEY_CORRELATION) return null
        val confidence = (best - second).coerceIn(0.0, 1.0)
        return MusicalKey(bestRoot, bestMinor) to confidence
    }

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        var meanA = 0.0
        var meanB = 0.0
        for (index in a.indices) {
            meanA += a[index]
            meanB += b[index]
        }
        meanA /= a.size
        meanB /= b.size
        var numerator = 0.0
        var squareA = 0.0
        var squareB = 0.0
        for (index in a.indices) {
            val da = a[index] - meanA
            val db = b[index] - meanB
            numerator += da * db
            squareA += da * da
            squareB += db * db
        }
        val denominator = sqrt(squareA * squareB)
        return if (denominator < 1e-12) 0.0 else numerator / denominator
    }

    private fun rms(samples: FloatArray): Float {
        var energy = 0.0
        for (sample in samples) energy += sample * sample
        return sqrt(energy / samples.size).toFloat()
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    private fun square(value: Double): Double = value * value

    private val MAJOR_PROFILE =
        doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR_PROFILE =
        doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
}

/** In-place radix-2 FFT magnitude with a Hann window. */
internal object Fft {
    private val windowCache = java.util.concurrent.ConcurrentHashMap<Int, FloatArray>()
    private val permutationCache = java.util.concurrent.ConcurrentHashMap<Int, IntArray>()

    fun magnitude(samples: FloatArray, offset: Int, size: Int): FloatArray {
        require(size > 0 && size and (size - 1) == 0) { "FFT size must be a power of two" }
        require(offset >= 0 && offset + size <= samples.size) { "window outside samples" }

        val window = windowCache.getOrPut(size) { hann(size) }
        val permutation = permutationCache.getOrPut(size) { permutation(size) }
        val real = FloatArray(size)
        val imaginary = FloatArray(size)
        for (index in 0 until size) real[index] = samples[offset + index] * window[index]

        for (index in 1 until size) {
            val reversed = permutation[index]
            if (index < reversed) {
                val tempReal = real[index]
                real[index] = real[reversed]
                real[reversed] = tempReal
                val tempImaginary = imaginary[index]
                imaginary[index] = imaginary[reversed]
                imaginary[reversed] = tempImaginary
            }
        }

        var length = 2
        while (length <= size) {
            val angle = -2.0 * PI / length
            val stepReal = cos(angle)
            val stepImaginary = sin(angle)
            var start = 0
            while (start < size) {
                var currentReal = 1.0
                var currentImaginary = 0.0
                val half = length / 2
                for (index in 0 until half) {
                    val even = start + index
                    val odd = even + half
                    val oddReal = real[odd] * currentReal - imaginary[odd] * currentImaginary
                    val oddImaginary = real[odd] * currentImaginary + imaginary[odd] * currentReal
                    real[odd] = real[even] - oddReal.toFloat()
                    imaginary[odd] = imaginary[even] - oddImaginary.toFloat()
                    real[even] = real[even] + oddReal.toFloat()
                    imaginary[even] = imaginary[even] + oddImaginary.toFloat()
                    val nextReal = currentReal * stepReal - currentImaginary * stepImaginary
                    currentImaginary = currentReal * stepImaginary + currentImaginary * stepReal
                    currentReal = nextReal
                }
                start += length
            }
            length = length shl 1
        }

        return FloatArray(size / 2) { bin ->
            sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
        }
    }

    private fun hann(size: Int): FloatArray =
        FloatArray(size) { index ->
            (0.5 - 0.5 * cos(2.0 * PI * index / (size - 1))).toFloat()
        }

    private fun permutation(size: Int): IntArray {
        val result = IntArray(size)
        var reversed = 0
        for (index in 1 until size) {
            var bit = size shr 1
            while (reversed and bit != 0) {
                reversed = reversed xor bit
                bit = bit shr 1
            }
            reversed = reversed xor bit
            result[index] = reversed
        }
        return result
    }
}
