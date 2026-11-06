/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import kotlin.math.exp
import kotlin.math.ln

/**
 * Time-decayed listening state.
 *
 * Each played track is an attractor, each skip a repeller, and older
 * contributions fade with a half-life measured in wall-clock time, not in
 * event count. The state is always unit length so it can be compared with
 * candidate vectors directly.
 */
class SessionState(
    private val halfLifeMs: Double = 600_000.0,
) {
    private var vector: DoubleArray? = null
    private var lastUpdateMs: Long = 0L

    val isPrimed: Boolean
        get() = vector != null

    fun current(): DoubleArray? = vector?.copyOf()

    fun update(embedding: FloatArray, atMs: Long, positive: Boolean = true): DoubleArray {
        require(halfLifeMs > 0.0) { "half-life must be positive" }
        val unit = DoubleArray(embedding.size) { embedding[it].toDouble() }
        require(normalise(unit) > 1e-9) { "embedding must not be zero" }

        val previous = vector
        val elapsed = (atMs - lastUpdateMs).coerceAtLeast(0L)
        val alpha =
            if (previous == null) {
                1.0
            } else {
                1.0 - exp(-ln(2.0) * elapsed / halfLifeMs)
            }

        val sign = if (positive) 1.0 else -1.0
        val next = DoubleArray(unit.size)
        if (previous != null) {
            for (index in next.indices) next[index] = (1.0 - alpha) * previous[index]
        }
        for (index in next.indices) next[index] += alpha * sign * unit[index]

        if (normalise(next) < 1e-9) {
            // A skip that cancels the whole state leaves it empty rather than
            // pointing at a numerically arbitrary direction.
            vector = null
            lastUpdateMs = atMs
            return DoubleArray(0)
        }

        vector = next
        lastUpdateMs = atMs
        return next.copyOf()
    }

    fun reset() {
        vector = null
        lastUpdateMs = 0L
    }

    private fun normalise(values: DoubleArray): Double {
        var energy = 0.0
        for (value in values) energy += value * value
        val norm = kotlin.math.sqrt(energy)
        if (norm < 1e-9) return 0.0
        for (index in values.indices) values[index] /= norm
        return norm
    }
}
