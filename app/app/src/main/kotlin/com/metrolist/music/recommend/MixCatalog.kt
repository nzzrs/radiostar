/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

data class MixTrack(
    val id: String,
    val title: String,
    val artists: String,
)

/** Lightweight model the Home shelf renders. */
data class MixCard(
    val kind: MixKind,
    val seedTitle: String?,
    val tracks: List<MixTrack>,
)

/**
 * Turns the three built slates into the cards shown on Home.
 *
 * Empty mixes are omitted: before the first analysis there is nothing honest
 * to show. The order is always Discover, Jams, Explore.
 */
object MixCatalog {
    const val PREVIEW_TRACKS = 3

    fun build(
        slates: Map<MixKind, List<SlateEntry>>,
        seedTitle: String?,
    ): List<MixCard> =
        MixKind.values().mapNotNull { kind ->
            val entries = slates[kind].orEmpty()
            if (entries.isEmpty()) {
                null
            } else {
                MixCard(
                    kind = kind,
                    seedTitle = seedTitle,
                    tracks =
                        entries.take(PREVIEW_TRACKS).map { entry ->
                            MixTrack(
                                id = entry.candidate.id,
                                title = entry.candidate.title,
                                artists = entry.candidate.artists.joinToString(", "),
                            )
                        },
                )
            }
        }
}
