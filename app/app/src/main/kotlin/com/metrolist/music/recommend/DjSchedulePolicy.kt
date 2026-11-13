/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

/**
 * When to start a prepared DJ link so it ends at the track boundary.
 *
 * The link may only begin once the clip is fully ready (text and audio), and
 * never earlier than the lookahead window. Kept free of Android types so the
 * timing rules stay unit-tested.
 */
data class DjScheduleDecision(
    val start: Boolean,
    val leadMs: Long,
    val reason: String,
)

object DjSchedulePolicy {
    const val DEFAULT_WINDOW_MS: Long = 12_000L
    const val MAX_WINDOW_MS: Long = 30_000L
    const val MIN_LEAD_MS: Long = 5_000L
    const val DEFAULT_FADE_MS: Long = 600L
    private const val WORDS_PER_MINUTE: Float = 150f
    private const val MIN_ESTIMATE_MS: Long = 1_200L
    private const val MAX_ESTIMATE_MS: Long = 20_000L

    /** The configured lookahead, never shorter than the floor nor longer than 30s. */
    fun window(windowMs: Long): Long = windowMs.coerceIn(MIN_LEAD_MS, MAX_WINDOW_MS)

    /**
     * How long before the boundary the link starts. The clip plays through,
     * then the fade back to the track volume runs until the boundary. Short
     * clips keep the 5s floor so they are not started at the very last moment.
     * [tickMs] compensates for a scheduler that only checks on an interval.
     */
    fun leadMs(
        clipDurationMs: Long,
        windowMs: Long = DEFAULT_WINDOW_MS,
        fadeMs: Long = DEFAULT_FADE_MS,
        tickMs: Long = 0L,
    ): Long {
        if (clipDurationMs <= 0L) return 0L
        val ideal = clipDurationMs + fadeMs.coerceAtLeast(0L) + tickMs.coerceAtLeast(0L)
        return ideal.coerceAtLeast(MIN_LEAD_MS).coerceAtMost(window(windowMs))
    }

    fun decide(
        remainingMs: Long,
        clipDurationMs: Long,
        windowMs: Long = DEFAULT_WINDOW_MS,
        fadeMs: Long = DEFAULT_FADE_MS,
        clipReady: Boolean = true,
        tickMs: Long = 0L,
    ): DjScheduleDecision {
        if (!clipReady) {
            return DjScheduleDecision(false, leadMs(clipDurationMs, windowMs, fadeMs, tickMs), "clip-not-ready")
        }
        if (remainingMs <= 0L) return DjScheduleDecision(false, 0L, "no-remaining")
        if (clipDurationMs <= 0L) return DjScheduleDecision(false, 0L, "unknown-clip")
        val lead = leadMs(clipDurationMs, windowMs, fadeMs, tickMs)
        if (remainingMs > window(windowMs)) return DjScheduleDecision(false, lead, "before-window")
        if (remainingMs > lead) return DjScheduleDecision(false, lead, "before-lead")
        return DjScheduleDecision(true, lead, "start")
    }

    /** Rough phone TTS length, used when no synthesized clip is available. */
    fun estimateSpeechDurationMs(text: String): Long {
        val words = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        if (words == 0) return MIN_ESTIMATE_MS
        val speechMs = (words / (WORDS_PER_MINUTE / 60f) * 1_000f).toLong()
        return (speechMs + 400L).coerceIn(MIN_ESTIMATE_MS, MAX_ESTIMATE_MS)
    }
}
