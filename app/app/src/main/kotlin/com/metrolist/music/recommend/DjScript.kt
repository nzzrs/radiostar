/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

/**
 * Text and timing for the DJ announcement that runs before the next track.
 *
 * Kept free of Android types so the wording and the lookahead window are
 * unit-tested; [com.metrolist.music.playback.DjAnnouncer] only speaks it.
 */
object DjScript {
    const val LEAD_MS = 8_000L
    const val MIN_LEAD_MS = 2_500L
    private const val MAX_CHARS = 180

    fun shouldAnnounce(
        positionMs: Long,
        durationMs: Long,
        alreadyAnnounced: Boolean,
        hasNext: Boolean,
        leadMs: Long = LEAD_MS,
        minLeadMs: Long = MIN_LEAD_MS,
    ): Boolean {
        if (alreadyAnnounced || !hasNext) return false
        if (positionMs < 0L || durationMs <= 0L) return false
        val remaining = durationMs - positionMs
        return remaining in minLeadMs..leadMs
    }

    fun intro(
        persona: DjPersona,
        firstTitle: String,
        firstArtist: String,
        language: String,
    ): String {
        val title = clean(firstTitle)
        if (title.isEmpty()) return ""
        val artist = clean(firstArtist)
        val track = if (artist.isEmpty()) title else "$title de $artist"
        val trackEn = if (artist.isEmpty()) title else "$title by $artist"
        val name = persona.shortName
        val variants =
            if (language.lowercase().startsWith("es")) {
                listOf(
                    "Soy $name. Abrimos con $track.",
                    "Aquí $name. Primera parada: $track.",
                    "$name al habla. Arrancamos con $track.",
                )
            } else {
                listOf(
                    "I'm $name. We start with $trackEn.",
                    "$name here. First stop: $trackEn.",
                    "$name on the mic. Opening with $trackEn.",
                )
            }
        return variants[(System.currentTimeMillis() / 60_000L % variants.size).toInt()]
            .take(MAX_CHARS)
    }

    fun announcement(
        title: String,
        artist: String,
        language: String,
    ): String {
        val cleanTitle = clean(title)
        if (cleanTitle.isEmpty()) return ""
        val cleanArtist = clean(artist)
        val text =
            if (language.lowercase().startsWith("es")) {
                if (cleanArtist.isEmpty()) {
                    "A continuación: $cleanTitle."
                } else {
                    "A continuación: $cleanTitle, de $cleanArtist."
                }
            } else {
                if (cleanArtist.isEmpty()) {
                    "Up next: $cleanTitle."
                } else {
                    "Up next: $cleanTitle by $cleanArtist."
                }
            }
        return text.take(MAX_CHARS)
    }

    /**
     * A true, session-specific observation. It never invents facts about the
     * artist, so it is safe to speak when the AI is unavailable.
     */
    fun observation(
        persona: DjPersona,
        trackNumber: Int,
        elapsedMinutes: Long,
        distinctArtists: Int,
        language: String,
    ): String {
        val tracks = trackNumber.coerceAtLeast(1)
        val minutes = elapsedMinutes.coerceAtLeast(1L)
        val artists = distinctArtists.coerceAtLeast(1)
        val name = persona.shortName
        val variants =
            if (language.lowercase().startsWith("es")) {
                listOf(
                    "Dato de sesión: ya van $tracks canciones y $artists artistas distintos. $name sigue al mando.",
                    "Curiosidad de la sesión: llevamos $minutes minutos y $tracks canciones. $name no se piensa mover.",
                    "Dato: $tracks canciones después, y todavía quedan historias por sonar. $name lo sabe.",
                )
            } else {
                listOf(
                    "Session fact: that is $tracks tracks and $artists different artists so far. $name is still in charge.",
                    "Session curiosity: we are $minutes minutes and $tracks tracks in. $name is not going anywhere.",
                    "Session fact: $tracks tracks in, and there are still stories left to play. $name knows it.",
                )
            }
        return variants[(tracks + artists) % variants.size].take(MAX_CHARS)
    }

    private fun clean(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()
}
