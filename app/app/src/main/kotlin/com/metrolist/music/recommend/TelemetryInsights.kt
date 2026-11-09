/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.time.Instant
import java.time.ZoneId
import kotlin.math.exp

data class ArtistInsight(
    /** Normalised key, matching [TelemetryBias.artistScores]. */
    val name: String,
    val displayName: String,
    val listens: Int,
    val score: Double,
)

data class SongInsight(
    val songId: String,
    val title: String,
    val artist: String,
    val listens: Int,
    val score: Double,
)

/**
 * Aggregate view of the local telemetry log.
 *
 * Nothing here is uploaded. The report is used by the settings screen and by
 * the Go Beyond recommender to choose an area the listener is likely to enjoy
 * and to keep familiar tracks from crowding out discovery.
 */
data class TelemetryInsights(
    val events: Int = 0,
    val plays: Int = 0,
    val completes: Int = 0,
    val skips: Int = 0,
    val earlySkips: Int = 0,
    val seeksForward: Int = 0,
    val seeksBackward: Int = 0,
    val reactions: Int = 0,
    val distinctSongs: Int = 0,
    val distinctArtists: Int = 0,
    val sessions: Int = 0,
    val listenedMs: Long = 0L,
    val activeHours: Map<Int, Int> = emptyMap(),
    val topArtists: List<ArtistInsight> = emptyList(),
    val topSongs: List<SongInsight> = emptyList(),
    /** Facet value (Brazil, Shoegaze, Purple, 1980s) to bounded affinity. */
    val gatewayAffinity: Map<String, Double> = emptyMap(),
) {
    val terminalEvents: Int
        get() = completes + skips

    val completionRate: Double
        get() = if (terminalEvents == 0) 0.0 else completes.toDouble() / terminalEvents

    val skipRate: Double
        get() = if (terminalEvents == 0) 0.0 else skips.toDouble() / terminalEvents

    val earlySkipRate: Double
        get() = if (skips == 0) 0.0 else earlySkips.toDouble() / skips

    val discoveryRate: Double
        get() = if (plays == 0) 0.0 else (distinctSongs.toDouble() / plays).coerceIn(0.0, 1.0)

    /** 0..1. High means the listener finishes and seeks out unfamiliar tracks. */
    val noveltyAppetite: Double
        get() {
            if (terminalEvents == 0) return 0.0
            return (0.65 * completionRate + 0.35 * (1.0 - earlySkipRate)).coerceIn(0.0, 1.0)
        }

    val bestHour: Int?
        get() = activeHours.maxByOrNull { it.value }?.key

    fun affinity(value: String): Double = gatewayAffinity[value] ?: 0.0

    companion object {
        val EMPTY = TelemetryInsights()

        private const val SESSION_GAP_MS = 30 * 60 * 1000L
        private const val AFFINITY_PRIOR = 1.5

        fun fromEvents(
            events: List<TelemetryStore.Event>,
            nowMs: Long = System.currentTimeMillis(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): TelemetryInsights {
            if (events.isEmpty()) return EMPTY

            val songs = LinkedHashMap<String, MutableSong>()
            val artists = LinkedHashMap<String, MutableArtist>()
            val activeHours = HashMap<Int, Int>()
            val facets = LinkedHashMap<String, Aggregate>()

            var plays = 0
            var completes = 0
            var skips = 0
            var earlySkips = 0
            var seeksForward = 0
            var seeksBackward = 0
            var reactions = 0
            var listenedMs = 0L

            for (event in events) {
                val hour =
                    runCatching {
                        Instant.ofEpochMilli(event.at).atZone(zone).hour
                    }.getOrNull()
                if (event.type == TelemetryEventType.PLAY && hour != null) {
                    activeHours[hour] = (activeHours[hour] ?: 0) + 1
                }

                when (event.type) {
                    TelemetryEventType.PLAY -> plays += 1
                    TelemetryEventType.COMPLETE, TelemetryEventType.REPLAY -> completes += 1
                    TelemetryEventType.LIKE, TelemetryEventType.DISLIKE -> reactions += 1
                    TelemetryEventType.SKIP -> {
                        skips += 1
                        if (isEarlySkip(event)) earlySkips += 1
                    }
                    TelemetryEventType.SEEK -> {
                        val from = event.fromMs
                        val to = event.toMs
                        when {
                            from == null || to == null -> Unit
                            to > from -> seeksForward += 1
                            to < from -> seeksBackward += 1
                        }
                    }
                    TelemetryEventType.PAUSE -> Unit
                }

                if (event.type == TelemetryEventType.COMPLETE ||
                    event.type == TelemetryEventType.REPLAY ||
                    event.type == TelemetryEventType.SKIP
                ) {
                    listenedMs += event.positionMs ?: event.durationMs ?: 0L
                }

                val contribution = TelemetryTaste.contribution(event) ?: continue
                val ageDays =
                    if (event.at > 0L) {
                        ((nowMs - event.at).coerceAtLeast(0L) / 86_400_000.0)
                    } else {
                        0.0
                    }
                val weight = exp(-ageDays / TelemetryTaste.HALF_LIFE_DAYS).coerceIn(0.05, 1.0)

                event.songId?.let { id ->
                    val song =
                        songs.getOrPut(id) {
                            MutableSong(
                                title = event.title.orEmpty(),
                                artist = event.artist.orEmpty(),
                            )
                        }
                    song.listens += 1
                }

                event.artist
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.forEach { name ->
                        val key = normalizeArtist(name)
                        val artist =
                            artists.getOrPut(key) {
                                MutableArtist(displayName = name)
                            }
                        artist.listens += 1
                    }

                val eventFacets =
                    event.facets.ifEmpty { GoBeyondGateway.facets(event.playlist) }
                eventFacets.forEach { facet ->
                    facets.getOrPut(facet) { Aggregate() }.add(contribution, weight)
                }
            }

            val bias = TelemetryTaste.fromEvents(events, nowMs)

            return TelemetryInsights(
                events = events.size,
                plays = plays,
                completes = completes,
                skips = skips,
                earlySkips = earlySkips,
                seeksForward = seeksForward,
                seeksBackward = seeksBackward,
                reactions = reactions,
                distinctSongs = songs.size,
                distinctArtists = artists.size,
                sessions = countSessions(events),
                listenedMs = listenedMs,
                activeHours = activeHours.toSortedMap(),
                topArtists =
                    artists
                        .map { (key, value) ->
                            ArtistInsight(
                                name = key,
                                displayName = value.displayName,
                                listens = value.listens,
                                score = bias.artistScores[key] ?: 0.0,
                            )
                        }
                        .sortedWith(compareByDescending<ArtistInsight> { it.listens }.thenByDescending { it.score })
                        .take(12),
                topSongs =
                    songs
                        .map { (id, value) ->
                            SongInsight(
                                songId = id,
                                title = value.title,
                                artist = value.artist,
                                listens = value.listens,
                                score = bias.songScores[id] ?: 0.0,
                            )
                        }
                        .sortedWith(compareByDescending<SongInsight> { it.listens }.thenByDescending { it.score })
                        .take(12),
                gatewayAffinity =
                    facets
                        .mapValues { (_, aggregate) -> aggregate.score().coerceIn(-1.0, 1.0) }
                        .filterValues { it != 0.0 },
            )
        }

        private fun isEarlySkip(event: TelemetryStore.Event): Boolean {
            val duration = event.durationMs ?: return false
            if (duration <= 0L) return false
            val position = event.positionMs ?: return false
            return position.toDouble() / duration.toDouble() < 0.30
        }

        private fun countSessions(events: List<TelemetryStore.Event>): Int {
            val named = events.mapNotNull { it.session?.takeIf(String::isNotBlank) }.toSet().size
            var gapSessions = 0
            var previous = Long.MIN_VALUE
            events
                .filter { it.session.isNullOrBlank() }
                .sortedBy { it.at }
                .forEach { event ->
                    if (previous == Long.MIN_VALUE || event.at - previous > SESSION_GAP_MS) {
                        gapSessions += 1
                    }
                    previous = event.at
                }
            return named + gapSessions
        }

        private data class Aggregate(
            var weighted: Double = 0.0,
            var weight: Double = 0.0,
        ) {
            fun add(value: Double, weight: Double) {
                weighted += value * weight
                this.weight += weight
            }

            fun score(): Double = weighted / (weight + AFFINITY_PRIOR)
        }

        private data class MutableSong(
            val title: String,
            val artist: String,
            var listens: Int = 0,
        )

        private data class MutableArtist(
            val displayName: String,
            var listens: Int = 0,
        )
    }
}
