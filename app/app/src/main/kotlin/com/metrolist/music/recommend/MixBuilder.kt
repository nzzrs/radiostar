/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import kotlin.math.sqrt

enum class MixKind {
    DISCOVER,
    JAMS,
    EXPLORE,
}

data class Candidate(
    val id: String,
    val title: String,
    val artists: List<String>,
    val vector: DoubleArray,
    val durationSeconds: Int? = null,
    /**
     * Queue tag. The player, the queue screen and the persisted queue read
     * `MediaItem.metadata` from the tag, so a slate item without it crashes
     * `Queue.kt` when the screen opens.
     */
    val tag: com.metrolist.music.models.MediaMetadata? = null,
)

data class SlateEntry(
    val candidate: Candidate,
    val score: Double,
)

/**
 * Turns a candidate pool into a playable slate.
 *
 * Quality is the cosine between the session state and the candidate vector.
 * The DPP then removes redundancy, and an artist cap stops one act from
 * filling the slate. Until tempo and key analysis exists, order is score
 * descending.
 */
object MixBuilder {
    data class Params(
        val slateSize: Int,
        val maxPerArtist: Int,
        val novelty: Double,
        val excludeHistory: Boolean,
        val excludeSeedArtist: Boolean,
    )

    fun params(kind: MixKind): Params =
        when (kind) {
            MixKind.DISCOVER -> Params(30, 2, 0.0, true, true)
            MixKind.JAMS -> Params(30, 3, 0.0, false, false)
            MixKind.EXPLORE -> Params(30, 1, 0.5, true, true)
        }

    fun build(
        session: DoubleArray,
        candidates: List<Candidate>,
        kind: MixKind,
        k: Int = params(kind).slateSize,
        historyIds: Set<String> = emptySet(),
        historyKeys: Set<String> = emptySet(),
        seedArtist: String? = null,
    ): List<SlateEntry> {
        require(session.isNotEmpty()) { "session vector must not be empty" }
        if (candidates.isEmpty() || k <= 0) return emptyList()

        val params = params(kind)
        val pool =
            candidates.filter { candidate ->
                !params.excludeHistory ||
                    (candidate.id !in historyIds && ListenHistory.keyFor(candidate) !in historyKeys)
            }.filter { candidate ->
                !params.excludeSeedArtist ||
                    seedArtist == null ||
                    candidate.artists.none { it.equals(seedArtist, ignoreCase = true) }
            }
        if (pool.isEmpty()) return emptyList()

        val quality =
            DoubleArray(pool.size) { index ->
                val similarity = cosine(session, pool[index].vector)
                (1.0 - params.novelty) * similarity + params.novelty * (1.0 - similarity)
            }

        val vectors = Array(pool.size) { pool[it].vector }
        val ranked = DppKernel.selectSlate(vectors, BooleanArray(pool.size), quality, k = minOf(k, pool.size))

        val chosen = ArrayList<Int>(k)
        val perArtist = HashMap<String, Int>()

        fun accept(index: Int): Boolean {
            val key = artistKey(pool[index])
            val used = perArtist[key] ?: 0
            if (key.isNotEmpty() && used >= params.maxPerArtist) return false
            chosen.add(index)
            if (key.isNotEmpty()) perArtist[key] = used + 1
            return true
        }

        for (index in ranked) {
            if (chosen.size >= k) break
            accept(index)
        }

        if (chosen.size < k) {
            val remaining = pool.indices.filterNot { it in chosen }.sortedByDescending { quality[it] }
            for (index in remaining) {
                if (chosen.size >= k) break
                accept(index)
            }
        }

        return chosen
            .map { SlateEntry(pool[it], quality[it]) }
            .sortedByDescending { it.score }
    }

    private fun artistKey(candidate: Candidate): String =
        candidate.artists.firstOrNull()?.trim()?.lowercase().orEmpty()

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (index in a.indices) {
            dot += a[index] * b[index]
            normA += a[index] * a[index]
            normB += b[index] * b[index]
        }
        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator < 1e-9) 0.0 else dot / denominator
    }
}
