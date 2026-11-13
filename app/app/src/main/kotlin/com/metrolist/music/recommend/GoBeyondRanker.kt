/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import kotlin.math.sqrt

data class GoBeyondCandidate(
    val id: String,
    val title: String,
    val artists: List<String>,
    val durationSeconds: Int? = null,
    val vector: DoubleArray? = null,
    /**
     * How strongly the candidate belongs to the requested target. Curated
     * country playlists score 1.0; a loose search result scores less.
     */
    val targetScore: Double = 0.0,
)

data class GoBeyondRankContext(
    val bias: TelemetryBias = TelemetryBias.EMPTY,
    val insights: TelemetryInsights = TelemetryInsights.EMPTY,
    /**
     * CLAP text embedding of the semantic prompt. Supplying it makes colour
     * and mood cards rank by meaning instead of by search position.
     */
    val promptVector: DoubleArray? = null,
    /**
     * Prompt words used for a lexical fallback when no embedding server is
     * configured. Tracks whose title or artist contains these words rise.
     */
    val promptTerms: Set<String> = emptySet(),
    /**
     * Literal colour words. A song named "Purple Rain" should not win a
     * request for music that *sounds* purple, so these get a hard penalty.
     */
    val literalTerms: Set<String> = emptySet(),
    val literalPenalty: Double = 0.9,
    /**
     * The mood title, aliases and prompt descriptors. They are a hard,
     * embedding-proof penalty: a track called "Euphoria" is not euphoric music.
     */
    val literalMoodTerms: Set<String> = emptySet(),
    val literalMoodPenalty: Double = 1.1,
    /**
     * Local BPM/RMS/key per track id. Only tracks the phone has played have a
     * profile; missing entries stay neutral and keep their semantic score.
     */
    val profiles: Map<String, AudioProfile> = emptyMap(),
    /** Sound the card is asking for, when it has one. */
    val sonicTarget: SonicTarget? = null,
    val heardIds: Set<String> = emptySet(),
    val recentIds: Set<String> = emptySet(),
    val skippedIds: Set<String> = emptySet(),
    val likedIds: Set<String> = emptySet(),
    val maxPerArtist: Int = 2,
    val limit: Int = 30,
)

data class GoBeyondScore(
    val candidate: GoBeyondCandidate,
    val score: Double,
)

/**
 * Ranks search candidates for a Go Beyond journey.
 *
 * The ranker is intentionally bounded: telemetry lifts or lowers a track, but
 * the semantic prompt, skip memory and artist cap stay in control. Tracks with
 * no embedding yet keep a neutral score and can still enter the playlist.
 */
object GoBeyondRanker {
    private const val TASTE_WEIGHT = 0.32
    private const val LIKED_BONUS = 0.12
    private const val SKIP_PENALTY = 0.75
    private const val RECENT_PENALTY = 0.22
    private const val FAMILIAR_PENALTY = 0.10
    private const val COMFORT_BONUS = 0.06
    private const val NOVELTY_BONUS = 0.10
    private const val TARGET_WEIGHT = 0.35

    fun rank(
        candidates: List<GoBeyondCandidate>,
        context: GoBeyondRankContext,
    ): List<GoBeyondScore> {
        if (candidates.isEmpty() || context.limit <= 0) return emptyList()

        val seenIds = HashSet<String>()
        val seenKeys = HashSet<String>()
        val unique =
            candidates
                .asSequence()
                .filter { it.id.isNotBlank() }
                .filter { seenIds.add(it.id) }
                .filter { seenKeys.add(dedupeKey(it)) }
                .toList()

        val scored =
            unique.map { candidate ->
                GoBeyondScore(candidate, score(candidate, context))
            }.sortedWith(
                compareByDescending<GoBeyondScore> { it.score }
                    .thenBy { it.candidate.title.lowercase() },
            )

        val perArtist = HashMap<String, Int>()
        val result = ArrayList<GoBeyondScore>(context.limit)
        for (entry in scored) {
            val artist = normalizeArtist(entry.candidate.artists.firstOrNull().orEmpty())
            if (context.maxPerArtist > 0 && artist.isNotEmpty()) {
                val used = perArtist[artist] ?: 0
                if (used >= context.maxPerArtist) continue
                perArtist[artist] = used + 1
            }
            result += entry
            if (result.size >= context.limit) break
        }
        return result
    }

    internal fun score(
        candidate: GoBeyondCandidate,
        context: GoBeyondRankContext,
    ): Double {
        val semantic =
            context.promptVector
                ?.let { prompt -> candidate.vector?.let { vector -> cosine(prompt, vector) } }
                ?.coerceIn(0.0, 1.0)

        var score = if (semantic != null) 0.50 + 0.35 * semantic else 0.50
        val text = normalizeText(candidate.title + " " + candidate.artists.joinToString(" "))
        if (context.literalTerms.isNotEmpty()) {
            val literalHits =
                context.literalTerms.count { term ->
                    val normalized = normalizeText(term)
                    normalized.isNotBlank() && text.contains(normalized)
                }
            if (literalHits > 0) {
                score -= context.literalPenalty * literalHits.coerceAtMost(2)
            }
        }
        if (context.literalMoodTerms.isNotEmpty()) {
            val moodHits =
                context.literalMoodTerms.count { term ->
                    matchesLiteral(text, normalizeText(term))
                }
            if (moodHits > 0) {
                score -= context.literalMoodPenalty * moodHits.coerceAtMost(2)
            }
        }
        val target = context.sonicTarget
        if (target != null) {
            GoBeyondSonic.score(context.profiles[candidate.id], target)?.let { fit ->
                score += target.weight * (2.0 * fit - 1.0)
            }
        }
        val promptHits =
            context.promptTerms.count { term ->
                val normalized = normalizeText(term)
                normalized.length >= 3 && text.contains(normalized)
            }
        score += (0.06 * promptHits).coerceAtMost(0.18)
        score += TARGET_WEIGHT * candidate.targetScore.coerceIn(0.0, 1.0)
        val taste = context.bias.score(candidate.id, candidate.artists)
        score += TASTE_WEIGHT * taste
        if (candidate.id in context.likedIds) score += LIKED_BONUS
        if (candidate.id in context.skippedIds) score -= SKIP_PENALTY
        if (candidate.id in context.recentIds) score -= RECENT_PENALTY

        val familiar = candidate.id in context.heardIds
        val novelty = context.insights.noveltyAppetite
        score +=
            if (familiar) {
                if (taste > 0.25) COMFORT_BONUS else 0.0
            } else {
                NOVELTY_BONUS * novelty
            }
        if (familiar) score -= FAMILIAR_PENALTY * (0.5 + novelty)
        return score
    }

    private fun normalizeText(value: String): String =
        java.text.Normalizer
            .normalize(value.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun matchesLiteral(
        text: String,
        term: String,
    ): Boolean {
        if (term.isBlank()) return false
        if (term.contains(' ')) return text.contains(term)
        if (term.length >= 5) {
            val stem = term.take(5)
            return text.split(' ').any { word -> word.length >= 5 && word.take(5) == stem }
        }
        return text.split(' ').any { word -> word == term }
    }

    private fun dedupeKey(candidate: GoBeyondCandidate): String =
        buildString {
            append(candidate.title.trim().lowercase())
            append('|')
            append(normalizeArtist(candidate.artists.firstOrNull().orEmpty()))
        }

    private fun cosine(
        a: DoubleArray,
        b: DoubleArray,
    ): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
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
