/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

/**
 * Orders a slate using the RMS energy the audio tap already measures.
 *
 * Candidates that have not played yet have no profile, so they keep their
 * score position. Played candidates fill those slots from quiet to loud, which
 * gives the slate a warm-up arc instead of a random jump between extremes.
 * Tempo and key stay out until a DSP analysis exists.
 */
object EnergyOrderer {
    fun orderByEnergyArc(
        entries: List<SlateEntry>,
        rmsByMediaId: Map<String, Float>,
    ): List<SlateEntry> {
        if (entries.size < 2) return entries

        val known =
            entries
                .filter { rmsByMediaId[it.candidate.id] != null }
                .sortedBy { rmsByMediaId.getValue(it.candidate.id) }
        if (known.size < 2) return entries

        val next = known.iterator()
        return entries.map { entry ->
            if (rmsByMediaId[entry.candidate.id] != null) next.next() else entry
        }
    }
}
