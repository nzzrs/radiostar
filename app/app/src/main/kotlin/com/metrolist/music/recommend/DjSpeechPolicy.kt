/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

/**
 * Decides whether the DJ should speak between two tracks.
 *
 * A link after every song gets tiring. This policy keeps the normal cadence
 * between one and three tracks, speaks earlier when the next track looks like
 * a skip risk, and leaves room for a true session observation instead of a
 * made-up fact.
 */
data class DjSpeechDecision(
    val speak: Boolean,
    val useFact: Boolean,
    val reason: String,
)

object DjSpeechPolicy {
    const val MAX_TRACKS_BETWEEN_LINKS: Int = 3
    const val SESSION_FACT_EVERY_LINKS: Int = 3
    const val LONG_GAP_MS: Long = 5 * 60_000L
    const val RISK_THRESHOLD: Double = -0.35

    fun decide(
        tracksSinceLastSpeech: Int,
        elapsedSinceLastSpeechMs: Long,
        nextTrackBias: Double,
        nextArtistChanged: Boolean,
        linksSpoken: Int,
        roll: Double,
    ): DjSpeechDecision {
        if (tracksSinceLastSpeech <= 0) {
            return DjSpeechDecision(false, false, "no-track-yet")
        }
        val earlyRisk = nextTrackBias <= RISK_THRESHOLD
        val longGap = elapsedSinceLastSpeechMs >= LONG_GAP_MS
        val atCadenceLimit = tracksSinceLastSpeech >= MAX_TRACKS_BETWEEN_LINKS

        val speak =
            when {
                atCadenceLimit -> true
                longGap -> true
                earlyRisk -> true
                tracksSinceLastSpeech == 2 -> true
                nextArtistChanged && roll < 0.35 -> true
                else -> false
            }
        if (!speak) return DjSpeechDecision(false, false, "quiet")

        val useFact =
            !earlyRisk &&
                linksSpoken > 0 &&
                (linksSpoken % SESSION_FACT_EVERY_LINKS == 0 || longGap)
        val reason =
            when {
                atCadenceLimit -> "cadence"
                longGap -> "long-gap"
                earlyRisk -> "skip-risk"
                tracksSinceLastSpeech == 2 -> "two-tracks"
                else -> "artist-change"
            }
        return DjSpeechDecision(true, useFact, reason)
    }
}
