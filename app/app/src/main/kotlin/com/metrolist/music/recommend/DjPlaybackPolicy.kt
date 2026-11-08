/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

/**
 * Playback rules around a spoken DJ link. The current song keeps playing while
 * the DJ talks; if the song ends before the utterance does, the next track
 * waits. Kept free of Android types so the numbers stay unit-tested.
 */
object DjPlaybackPolicy {
    const val DUCK_FACTOR: Float = 0.22f
    const val END_OF_TRACK_TOLERANCE_MS: Long = 400L

    fun duckedVolume(baseVolume: Float): Float = (baseVolume.coerceIn(0f, 1f) * DUCK_FACTOR).coerceIn(0f, 1f)

    fun isAtTrackEnd(
        positionMs: Long,
        durationMs: Long,
        toleranceMs: Long = END_OF_TRACK_TOLERANCE_MS,
    ): Boolean = durationMs > 0L && positionMs >= durationMs - toleranceMs
}
