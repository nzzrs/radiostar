/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.content.Context
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.constants.RadioStarServerKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

data class GoBeyondSectionFeed(
    val section: GoBeyondSection,
    val title: String,
    val subtitle: String,
    val cards: List<GoBeyondCard>,
)

data class GoBeyondRecommendation(
    val selection: GoBeyondSelection,
    val title: String,
    val reason: String,
    val accentHue: Int,
)

data class GoBeyondFeed(
    val sections: List<GoBeyondSectionFeed>,
    val recommended: GoBeyondRecommendation?,
    val insights: TelemetryInsights,
)

/**
 * Builds "Go Beyond" journeys: short playlists that start from a country,
 * genre, artist, period, colour, mood, or a custom combination.
 *
 * Candidates come from YouTube Music search. The local telemetry log supplies
 * taste, skip memory and exploration appetite, while the optional laptop text
 * tower embeds the category prompt so colour and mood cards rank by meaning.
 * Nothing leaves the phone except the artist/title and category prompt sent to
 * the user's own embedding server.
 */
@Singleton
class GoBeyondRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        private val auth: ServerAuthStore,
    ) {
        suspend fun feed(): GoBeyondFeed =
            withContext(Dispatchers.IO) {
                val events = runCatching { TelemetryStore.load(context) }.getOrDefault(emptyList())
                val imports = runCatching { ListenStore(context).load() }.getOrDefault(emptyList())
                val insights = TelemetryInsights.fromEvents(events)
                val artistNames = topArtistNames(insights, imports)
                val artistCards =
                    if (artistNames.isEmpty()) {
                        GoBeyondCatalog.artistCards(GoBeyondCatalog.defaultArtists)
                    } else {
                        GoBeyondCatalog.artistCards(artistNames)
                    }

                val sections =
                    listOf(
                        sectionFeed(
                            GoBeyondSection.COUNTRY,
                            "Start with a place",
                            "Pick a country and keep the songs that fit your taste.",
                            GoBeyondCatalog.cards(GoBeyondSection.COUNTRY),
                            insights,
                        ),
                        sectionFeed(
                            GoBeyondSection.GENRE,
                            "Start with a sound",
                            "A genre is a doorway. Familiar records stay close, new ones lead.",
                            GoBeyondCatalog.cards(GoBeyondSection.GENRE),
                            insights,
                        ),
                        sectionFeed(
                            GoBeyondSection.ARTIST,
                            "Go deeper with an artist",
                            "Their catalogue and the artists around them, beyond what you already play.",
                            artistCards,
                            insights,
                        ),
                        sectionFeed(
                            GoBeyondSection.PERIOD,
                            "Start with a time",
                            "A decade or your own year range, filtered through your listening.",
                            GoBeyondCatalog.cards(GoBeyondSection.PERIOD),
                            insights,
                        ),
                        sectionFeed(
                            GoBeyondSection.COLOR,
                            "Start with a colour",
                            "Music can sound purple, silver or warm gold. Start from an atmosphere.",
                            GoBeyondCatalog.cards(GoBeyondSection.COLOR),
                            insights,
                        ),
                        sectionFeed(
                            GoBeyondSection.MOOD,
                            "Start with a feeling",
                            "Choose the emotional tone and let the playlist do the rest.",
                            GoBeyondCatalog.cards(GoBeyondSection.MOOD),
                            insights,
                        ),
                    )

                GoBeyondFeed(
                    sections = sections,
                    recommended = recommendation(insights, artistCards),
                    insights = insights,
                )
            }

        /**
         * Builds and stores an ordinary local playlist. The stable id means a
         * second tap refreshes the same journey instead of duplicating it.
         */
        suspend fun generate(selection: GoBeyondSelection): String =
            withContext(Dispatchers.IO) {
                require(!selection.isBlank()) { "empty Go Beyond selection" }
                val playlistId = selection.stableId()
                val found = search(selection)
                if (found.isEmpty()) {
                    error("No tracks found for ${selection.displayTitle()}")
                }

                val events = runCatching { TelemetryStore.load(context) }.getOrDefault(emptyList())
                val insights = TelemetryInsights.fromEvents(events)
                val now = System.currentTimeMillis()
                val terminal = events.filter { it.songId != null }
                val heardIds = terminal.mapNotNull { it.songId }.toSet()
                val recentIds =
                    terminal
                        .filter { it.at >= now - RECENT_MS }
                        .mapNotNull { it.songId }
                        .toSet()
                val skippedIds =
                    terminal
                        .filter {
                            it.at >= now - SKIP_MEMORY_MS &&
                                (it.type == TelemetryEventType.SKIP || it.type == TelemetryEventType.DISLIKE)
                        }
                        .mapNotNull { it.songId }
                        .toSet()
                val likedIds =
                    terminal
                        .filter { it.type == TelemetryEventType.LIKE }
                        .mapNotNull { it.songId }
                        .toSet()

                var promptVector: DoubleArray? = null
                var vectors: List<FloatArray>? = null
                val server = configuredServer()
                if (!server.isNullOrBlank()) {
                    promptVector =
                        runCatching {
                            EmbeddingClient
                                .embedText(server, listOf(selection.semanticPrompt()), auth)
                                ?.firstOrNull()
                                ?.toDoubles()
                        }.onFailure { Timber.tag(TAG).w(it, "prompt embedding failed") }
                            .getOrNull()
                    vectors =
                        runCatching {
                            EmbeddingClient.embed(
                                server,
                                found.map { EmbeddingClient.Item(it.artists.joinToString { artist -> artist.name }, it.title) },
                                auth,
                            )
                        }.onFailure { Timber.tag(TAG).w(it, "candidate embedding failed") }
                            .getOrNull()
                }

                val byId = found.associateBy { it.id }
                val candidates =
                    found.mapIndexed { index, song ->
                        GoBeyondCandidate(
                            id = song.id,
                            title = song.title,
                            artists = song.artists.map { it.name },
                            durationSeconds = song.duration,
                            vector = vectors?.getOrNull(index)?.toDoubles(),
                        )
                    }
                val rankContext =
                    GoBeyondRankContext(
                        bias = TelemetryTaste.fromEvents(events),
                        insights = insights,
                        promptVector = promptVector,
                        heardIds = heardIds,
                        recentIds = recentIds,
                        skippedIds = skippedIds,
                        likedIds = likedIds,
                        maxPerArtist = MAX_PER_ARTIST,
                        limit = SLATE_SIZE,
                    )
                val chosen =
                    GoBeyondRanker
                        .rank(candidates, rankContext)
                        .mapNotNull { byId[it.candidate.id] }
                if (chosen.isEmpty()) error("No tracks survived ranking for ${selection.displayTitle()}")

                writePlaylist(playlistId, selection, chosen)
                rememberSelection(playlistId, selection)
                Timber.tag(TAG).i(
                    "generated %s tracks=%d events=%d",
                    selection.displayTitle(),
                    chosen.size,
                    events.size,
                )
                playlistId
            }

        suspend fun refresh(playlistId: String): Boolean =
            withContext(Dispatchers.IO) {
                if (!playlistId.startsWith(ID_PREFIX)) return@withContext false
                val stored =
                    runCatching {
                        context.dataStore.data.first()[selectionKey(playlistId)]
                    }.getOrNull()
                val selection = GoBeyondSelectionCodec.decode(stored) ?: return@withContext false
                runCatching { generate(selection) }
                    .onFailure { Timber.tag(TAG).w(it, "refresh failed for %s", playlistId) }
                    .isSuccess
            }

        fun isGoBeyondPlaylist(playlistId: String): Boolean = playlistId.startsWith(ID_PREFIX)

        private suspend fun search(selection: GoBeyondSelection): List<SongItem> {
            val merged = LinkedHashMap<String, SongItem>()
            for (query in selection.searchQueries().take(4)) {
                val result =
                    runCatching {
                        YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    }.getOrNull() ?: continue
                result.items
                    .asSequence()
                    .filterIsInstance<SongItem>()
                    .filter { !it.isEpisode }
                    .filter { !it.isVideoSong }
                    .filter { it.id.isNotBlank() && it.title.isNotBlank() }
                    .filter { it.artists.any { artist -> artist.name.isNotBlank() } }
                    .take(40)
                    .forEach { merged.putIfAbsent(it.id, it) }
                if (merged.size >= MAX_CANDIDATES) break
            }
            if (merged.size < RADIO_TRIGGER) {
                merged.values.take(RADIO_SEEDS).forEach { seed ->
                    val result =
                        runCatching {
                            YouTube.next(WatchEndpoint(videoId = seed.id)).getOrNull()
                        }.getOrNull() ?: return@forEach
                    result.items
                        .asSequence()
                        .filter { !it.isEpisode }
                        .filter { it.id.isNotBlank() && it.title.isNotBlank() }
                        .filter { it.artists.any { artist -> artist.name.isNotBlank() } }
                        .take(40)
                        .forEach { merged.putIfAbsent(it.id, it) }
                }
            }
            return merged.values.take(MAX_CANDIDATES)
        }

        private suspend fun writePlaylist(
            playlistId: String,
            selection: GoBeyondSelection,
            songs: List<SongItem>,
        ) {
            val now = LocalDateTime.now()
            database.withTransaction {
                val existing = database.playlistEntity(playlistId)
                val entity =
                    (existing ?: PlaylistEntity(id = playlistId, name = selection.playlistName(), createdAt = now))
                        .copy(
                            name = selection.playlistName(),
                            isEditable = false,
                            bookmarkedAt = existing?.bookmarkedAt ?: now,
                            lastUpdateTime = now,
                            thumbnailUrl = songs.firstOrNull()?.thumbnail,
                        )
                if (existing == null) database.insert(entity) else database.update(entity)
                database.clearPlaylist(playlistId)

                songs.forEachIndexed { position, item ->
                    val tag = item.toMediaMetadata()
                    database.insert(tag.toSongEntity())
                    tag.artists.forEachIndexed { index, artist ->
                        val artistId =
                            artist.id?.takeIf { it.isNotBlank() }
                                ?: "RS_GO_${abs(artist.name.hashCode())}"
                        database.insert(ArtistEntity(id = artistId, name = artist.name))
                        database.insert(
                            SongArtistMap(
                                songId = tag.id,
                                artistId = artistId,
                                position = index,
                            ),
                        )
                    }
                    database.insert(
                        PlaylistSongMap(
                            playlistId = playlistId,
                            songId = tag.id,
                            position = position,
                        ),
                    )
                }
            }
        }

        private suspend fun rememberSelection(
            playlistId: String,
            selection: GoBeyondSelection,
        ) {
            runCatching {
                context.dataStore.edit { settings ->
                    settings[selectionKey(playlistId)] = GoBeyondSelectionCodec.encode(selection)
                }
            }
        }

        private suspend fun configuredServer(): String? {
            val prefs = runCatching { context.dataStore.data.first() }.getOrNull()
            return prefs?.get(RadioStarServerKey)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
                ?: Settings.Global
                    .getString(context.contentResolver, "radiostar_embed_server")
                    ?.trim()
                    ?.trimEnd('/')
                    ?.takeIf { it.isNotEmpty() }
        }

        private fun sectionFeed(
            section: GoBeyondSection,
            title: String,
            subtitle: String,
            cards: List<GoBeyondCard>,
            insights: TelemetryInsights,
        ) = GoBeyondSectionFeed(
            section = section,
            title = title,
            subtitle = subtitle,
            cards =
                cards.sortedWith(
                    compareByDescending<GoBeyondCard> { insights.affinity(it.title) },
                ),
        )

        private fun recommendation(
            insights: TelemetryInsights,
            artistCards: List<GoBeyondCard>,
        ): GoBeyondRecommendation? {
            val bestFacet =
                insights.gatewayAffinity
                    .filterValues { it > 0.12 }
                    .maxByOrNull { it.value }
                    ?.key
            val bestCard = bestFacet?.let { GoBeyondCatalog.cardForValue(it) }
            if (bestCard != null) {
                return GoBeyondRecommendation(
                    selection = bestCard.selection,
                    title = bestCard.title,
                    reason = "Because you keep coming back to ${bestCard.title}.",
                    accentHue = bestCard.accentHue,
                )
            }

            artistCards.firstOrNull()?.let { card ->
                return GoBeyondRecommendation(
                    selection = card.selection,
                    title = card.title,
                    reason = "The artist you play most. Go deeper than the familiar tracks.",
                    accentHue = card.accentHue,
                )
            }

            val mood = suggestedMood(insights)
            val moodCard = GoBeyondCatalog.cards(GoBeyondSection.MOOD).firstOrNull { it.title == mood }
            return moodCard?.let {
                GoBeyondRecommendation(
                    selection = it.selection,
                    title = it.title,
                    reason = "Your listening leans ${it.title.lowercase(Locale.ROOT)} around this hour.",
                    accentHue = it.accentHue,
                )
            }
        }

        private fun suggestedMood(insights: TelemetryInsights): String {
            val hour = insights.bestHour
            return when {
                hour == null -> "Dreamy"
                hour in 5..11 && insights.noveltyAppetite >= 0.5 -> "Euphoric"
                hour in 5..11 -> "Focus"
                hour in 12..16 && insights.noveltyAppetite >= 0.5 -> "Playful"
                hour in 12..16 -> "Nostalgic"
                hour in 17..21 && insights.noveltyAppetite >= 0.5 -> "Dreamy"
                hour in 17..21 -> "Romantic"
                insights.skipRate >= 0.4 -> "Calm"
                else -> "Melancholic"
            }
        }

        private fun topArtistNames(
            insights: TelemetryInsights,
            imports: List<ImportedListen>,
        ): List<String> {
            val counts = LinkedHashMap<String, Pair<String, Int>>()
            insights.topArtists.forEach { artist ->
                counts[artist.name] = artist.displayName to artist.listens
            }
            imports.forEach { listen ->
                listen.artist
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { name ->
                        val key = normalizeArtist(name)
                        val existing = counts[key]
                        counts[key] = (existing?.first ?: name) to ((existing?.second ?: 0) + 1)
                    }
            }
            return counts.values
                .sortedByDescending { it.second }
                .map { it.first }
                .take(MAX_ARTIST_CARDS)
        }

        private fun selectionKey(playlistId: String) = stringPreferencesKey("radioStarGoBeyondSelection_$playlistId")

        companion object {
            const val ID_PREFIX = "LP_RS_GO_"
            private const val TAG = "GoBeyond"
            const val SLATE_SIZE = 30
            const val MAX_CANDIDATES = 120
            const val RADIO_TRIGGER = 60
            const val RADIO_SEEDS = 2
            const val MAX_ARTIST_CARDS = 12
            const val MAX_PER_ARTIST = 2
            const val RECENT_MS = 30L * 86_400_000L
            const val SKIP_MEMORY_MS = 90L * 86_400_000L
        }
    }

private fun FloatArray.toDoubles(): DoubleArray = DoubleArray(size) { this[it].toDouble() }
