package com.metrolist.music.recommend

import kotlin.math.sqrt

/**
 * PSD-safe DPP kernel over a mixed audio / text candidate set.
 *
 * Candidate embeddings share one CLAP space, so a single Gram matrix describes
 * every pair. Cross-modal pairs are discounted by [rho]; the diagonal is left
 * untouched so cold candidates carry no quality penalty.
 */
object DppKernel {
    const val DEFAULT_RHO = 0.7
    const val DEFAULT_JITTER_REL = 1e-6
    private const val SELECT_EPS = 1e-12
    private const val QUALITY_EPS = 1e-9
    private const val FALLBACK_JITTER = 1e-9

    /**
     * Build the DPP kernel `L = diag(q) S diag(q) + lambda I`.
     *
     * `u` is `(n, d)`, [quality] is `(n,)` and non-negative, [isAudio] is `(n,)`.
     */
    fun build(
        u: Array<DoubleArray>,
        isAudio: BooleanArray,
        quality: DoubleArray,
        rho: Double = DEFAULT_RHO,
        beta: Double = 0.0,
        jitterRel: Double = DEFAULT_JITTER_REL,
    ): Array<DoubleArray> {
        require(rho in -1.0..1.0) { "rho must lie in [-1, 1], got $rho" }
        require(beta >= 0.0) { "beta must be non-negative, got $beta" }
        val n = u.size
        require(quality.size == n) { "quality must have length $n, got ${quality.size}" }
        require(isAudio.size == n) { "isAudio must have length $n, got ${isAudio.size}" }
        require(quality.all { it >= 0.0 }) { "quality must be non-negative" }
        if (n == 0) return emptyArray()

        val dimension = u[0].size
        require(u.all { it.size == dimension }) { "u must be a rectangular matrix" }

        val kernel = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in 0 until n) {
                var dot = 0.0
                for (d in 0 until dimension) {
                    dot += u[i][d] * u[j][d]
                }
                val modality = if (isAudio[i] == isAudio[j]) 1.0 else rho
                val similarity = modality * dot + beta
                kernel[i][j] = quality[i] * quality[j] * similarity
            }
        }

        var diagonalSum = 0.0
        for (i in 0 until n) {
            diagonalSum += kernel[i][i]
        }
        val rawJitter = jitterRel * (diagonalSum / n)
        val jitter = if (!rawJitter.isFinite() || rawJitter <= 0.0) FALLBACK_JITTER else rawJitter
        for (i in 0 until n) {
            kernel[i][i] += jitter
        }
        return kernel
    }

    /**
     * Fast greedy MAP for a DPP using an incremental Cholesky factorisation.
     *
     * Returns at most `k` unique indices. Selection stops early when the best
     * remaining gain is not positive.
     */
    fun select(kernel: Array<DoubleArray>, k: Int, eps: Double = SELECT_EPS): List<Int> {
        val n = kernel.size
        require(kernel.all { it.size == n }) { "kernel must be square" }
        if (n == 0 || k <= 0) return emptyList()

        val limit = minOf(k, n)
        val remainingGain = DoubleArray(n) { kernel[it][it] }
        val choleskyRows = Array(limit) { DoubleArray(n) }
        val selected = ArrayList<Int>(limit)

        var next = argMax(remainingGain)
        for (i in 0 until limit) {
            val pivot = remainingGain[next]
            if (pivot <= eps) break
            selected.add(next)

            val row = DoubleArray(n)
            for (column in 0 until n) {
                var projection = 0.0
                for (t in 0 until i) {
                    projection += choleskyRows[t][next] * choleskyRows[t][column]
                }
                row[column] = kernel[next][column] - projection
            }

            val inverseRoot = 1.0 / sqrt(pivot)
            for (column in 0 until n) {
                val value = row[column] * inverseRoot
                choleskyRows[i][column] = value
                remainingGain[column] -= value * value
            }
            for (index in selected) {
                remainingGain[index] = Double.NEGATIVE_INFINITY
            }
            next = argMax(remainingGain)
        }
        return selected
    }

    /**
     * Normalise raw relevance scores into `[1e-9, 1]` and run the greedy DPP.
     */
    fun selectSlate(
        u: Array<DoubleArray>,
        isAudio: BooleanArray,
        scores: DoubleArray,
        k: Int,
        rho: Double = DEFAULT_RHO,
        beta: Double = 0.0,
        jitterRel: Double = DEFAULT_JITTER_REL,
    ): List<Int> {
        val quality = normaliseQuality(scores)
        val kernel = build(u, isAudio, quality, rho = rho, beta = beta, jitterRel = jitterRel)
        return select(kernel, k)
    }

    /**
     * Min-max scores into `[1e-9, 1]`. A constant slate maps to all ones.
     */
    fun normaliseQuality(scores: DoubleArray): DoubleArray {
        if (scores.isEmpty()) return DoubleArray(0)
        var low = scores[0]
        var high = scores[0]
        for (score in scores) {
            if (score < low) low = score
            if (score > high) high = score
        }
        if (high - low < QUALITY_EPS) return DoubleArray(scores.size) { 1.0 }
        val range = high - low
        return DoubleArray(scores.size) { index ->
            ((scores[index] - low) / range).coerceAtLeast(QUALITY_EPS)
        }
    }

    private fun argMax(values: DoubleArray): Int {
        var best = 0
        for (index in 1 until values.size) {
            if (values[index] > values[best]) best = index
        }
        return best
    }
}
