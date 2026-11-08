/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import kotlin.math.abs

/** What the DSP learned about one played track. Missing fields stay null. */
data class AudioProfile(
    val bpm: Double? = null,
    val key: MusicalKey? = null,
    val rms: Float? = null,
)

/**
 * Orders the part of the slate that already has audio profiles as a smooth
 * harmonic path, starting from the track that is playing.
 *
 * Candidates that have never played have no profile, so they keep their score
 * position. This is the honest limit of on-device analysis: we know the audio
 * of tracks we have heard, not of the candidates.
 */
object AudioOrderer {
    private const val TEMPO_WEIGHT = 0.6
    private const val KEY_WEIGHT = 1.2
    private const val RMS_WEIGHT = 0.1

    fun harmonicPath(
        entries: List<SlateEntry>,
        profiles: Map<String, AudioProfile>,
        reference: AudioProfile?,
    ): List<SlateEntry> {
        val known = entries.filter { profiles[it.candidate.id] != null }
        if (known.size < 2) return entries

        val remaining = known.toMutableList()
        val path = ArrayList<SlateEntry>(known.size)
        var current = reference

        if (current == null) {
            val first =
                remaining.minWith(
                    compareBy(
                        { profiles[it.candidate.id]?.rms ?: Float.MAX_VALUE },
                        { profiles[it.candidate.id]?.bpm ?: Double.MAX_VALUE },
                    ),
                )
            path.add(first)
            remaining.remove(first)
            current = profiles[first.candidate.id]
        }

        while (remaining.isNotEmpty()) {
            val next =
                remaining.minWith(
                    compareBy { transitionCost(current, profiles[it.candidate.id]) },
                )
            path.add(next)
            remaining.remove(next)
            current = profiles[next.candidate.id]
        }

        val iterator = path.iterator()
        return entries.map { entry ->
            if (profiles[entry.candidate.id] != null) iterator.next() else entry
        }
    }

    /** Lower is a smoother transition. Missing features are ignored. */
    fun transitionCost(from: AudioProfile?, to: AudioProfile?): Double {
        if (from == null || to == null) return 1.0
        var cost = 0.0
        var weight = 0.0

        if (from.bpm != null && to.bpm != null) {
            val average = (from.bpm + to.bpm) / 2.0
            val relative = abs(from.bpm - to.bpm) / (0.08 * average).coerceAtLeast(1.0)
            cost += TEMPO_WEIGHT * relative.coerceIn(0.0, 1.0)
            weight += TEMPO_WEIGHT
        }
        if (from.key != null && to.key != null) {
            cost += KEY_WEIGHT * (from.key.harmonicDistance(to.key) / 2.0)
            weight += KEY_WEIGHT
        }
        if (from.rms != null && to.rms != null) {
            cost += RMS_WEIGHT * abs(from.rms - to.rms)
            weight += RMS_WEIGHT
        }

        return if (weight < 1e-9) 1.0 else cost / weight
    }
}
