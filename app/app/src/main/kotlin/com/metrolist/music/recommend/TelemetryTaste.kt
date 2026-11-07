/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.content.Context
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

private fun artistKey(name: String): String = name.trim().lowercase(Locale.ROOT)

/**
 * Turns the local telemetry log into a light taste signal.
 *
 * The signal is deliberately bounded: it nudges the DPP quality and the DJ
 * queue order, but it never overrides the harmony and novelty rules. A track
 * needs several positive events before its bonus approaches one.
 */
data class TelemetryBias(
    val songScores: Map<String, Double> = emptyMap(),
    val artistScores: Map<String, Double> = emptyMap(),
) {
    fun score(songId: String?, artists: List<String>): Double {
        val song = songId?.let { songScores[it] }
        val artist =
            artists
                .map { artistKey(it) }
                .mapNotNull { artistScores[it] }
                .takeIf { it.isNotEmpty() }
                ?.average()
        return when {
            song != null && artist != null -> 0.75 * song + 0.25 * artist
            song != null -> song
            artist != null -> artist
            else -> 0.0
        }.coerceIn(-1.0, 1.0)
    }

    companion object {
        val EMPTY = TelemetryBias()
    }
}

object TelemetryTaste {
    /** A listen older than this contributes half as much. */
    const val HALF_LIFE_DAYS: Double = 21.0

    /** Damping keeps a single event from feeling like a strong preference. */
    private const val PRIOR_WEIGHT: Double = 2.0

    private data class Aggregate(
        var weighted: Double = 0.0,
        var weight: Double = 0.0,
    ) {
        fun add(value: Double, weight: Double) {
            weighted += value * weight
            this.weight += weight
        }

        fun score(): Double =
            (weighted / (weight + PRIOR_WEIGHT)).coerceIn(-1.0, 1.0)
    }

    suspend fun load(context: Context): TelemetryBias =
        fromEvents(TelemetryStore.load(context))

    fun fromEvents(
        events: List<TelemetryStore.Event>,
        nowMs: Long = System.currentTimeMillis(),
    ): TelemetryBias {
        if (events.isEmpty()) return TelemetryBias.EMPTY
        val songs = HashMap<String, Aggregate>()
        val artists = HashMap<String, Aggregate>()

        for (event in events) {
            val contribution = contribution(event) ?: continue
            val ageDays =
                if (event.at > 0L) {
                    ((nowMs - event.at).coerceAtLeast(0L) / 86_400_000.0)
                } else {
                    0.0
                }
            val decay = exp(-ageDays / HALF_LIFE_DAYS)
            val weight = decay.coerceIn(0.05, 1.0)

            event.songId?.let { id ->
                songs.getOrPut(id) { Aggregate() }.add(contribution, weight)
            }
            event.artist
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.forEach { name ->
                    artists.getOrPut(artistKey(name)) { Aggregate() }.add(contribution, weight)
                }
        }

        return TelemetryBias(
            songScores =
                songs
                    .mapValues { (_, aggregate) -> aggregate.score() }
                    .filterValues { abs(it) >= 0.05 },
            artistScores =
                artists
                    .mapValues { (_, aggregate) -> aggregate.score() }
                    .filterValues { abs(it) >= 0.05 },
        )
    }

    /**
     * Positive values mean the listener finishes the track; negative values
     * mean they skip it. A pause or a plain play carries no preference.
     */
    private fun contribution(event: TelemetryStore.Event): Double? =
        when (event.type) {
            TelemetryEventType.COMPLETE -> 1.0
            TelemetryEventType.SKIP -> {
                val ratio =
                    if (event.durationMs != null && event.durationMs > 0L &&
                        event.positionMs != null
                    ) {
                        (event.positionMs.toDouble() / event.durationMs.toDouble())
                            .coerceIn(0.0, 1.0)
                    } else {
                        0.5
                    }
                // Early skips are the strongest signal; late skips are weaker.
                -(1.2 - 0.7 * ratio)
            }
            TelemetryEventType.SEEK -> {
                val from = event.fromMs
                val to = event.toMs
                when {
                    from == null || to == null -> null
                    to > from -> 0.15
                    to < from -> -0.25
                    else -> null
                }
            }
            TelemetryEventType.PLAY,
            TelemetryEventType.PAUSE,
            -> null
        }
}
