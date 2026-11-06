/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

enum class PlaylistHorizon {
    DAILY,
    WEEKLY,
    SPECIAL,
}

/**
 * A generated playlist the user can open and play like any other playlist.
 *
 * The ids are stable so a refresh replaces the same rows instead of creating
 * duplicates in the library.
 */
data class PlaylistSpec(
    val id: String,
    val title: String,
    val kind: MixKind,
    val horizon: PlaylistHorizon,
    val size: Int,
    val historyDays: Long,
)

object RadioStarPlaylistSpecs {
    const val DAILY_DISCOVER_ID = "LP_RS_DAILY_DISCOVER"
    const val DAILY_JAMS_ID = "LP_RS_DAILY_JAMS"
    const val DAILY_EXPLORE_ID = "LP_RS_DAILY_EXPLORE"
    const val WEEKLY_DISCOVER_ID = "LP_RS_WEEKLY_DISCOVER"
    const val WEEKLY_JAMS_ID = "LP_RS_WEEKLY_JAMS"
    const val WEEKLY_EXPLORE_ID = "LP_RS_WEEKLY_EXPLORE"
    const val CHERRY_ID = "LP_RS_CHERRY"

    val daily =
        listOf(
            PlaylistSpec(DAILY_DISCOVER_ID, "Daily Discover", MixKind.DISCOVER, PlaylistHorizon.DAILY, 30, 7),
            PlaylistSpec(DAILY_JAMS_ID, "Daily Jam", MixKind.JAMS, PlaylistHorizon.DAILY, 30, 7),
            PlaylistSpec(DAILY_EXPLORE_ID, "Daily Explore", MixKind.EXPLORE, PlaylistHorizon.DAILY, 30, 7),
        )

    val weekly =
        listOf(
            PlaylistSpec(WEEKLY_DISCOVER_ID, "Weekly Discover", MixKind.DISCOVER, PlaylistHorizon.WEEKLY, 30, 30),
            PlaylistSpec(WEEKLY_JAMS_ID, "Weekly Jam", MixKind.JAMS, PlaylistHorizon.WEEKLY, 30, 30),
            PlaylistSpec(WEEKLY_EXPLORE_ID, "Weekly Explore", MixKind.EXPLORE, PlaylistHorizon.WEEKLY, 30, 30),
        )

    val cherry =
        PlaylistSpec(CHERRY_ID, "DJ Cherry", MixKind.CHERRY, PlaylistHorizon.SPECIAL, 20, 14)

    val all: List<PlaylistSpec> = daily + weekly + listOf(cherry)

    fun byId(id: String): PlaylistSpec? = all.firstOrNull { it.id == id }
}
