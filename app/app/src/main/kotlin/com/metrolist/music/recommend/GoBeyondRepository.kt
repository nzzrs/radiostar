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
import com.metrolist.innertube.models.PlaylistItem
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
import kotlinx.coroutines.withTimeoutOrNull
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
    val art: GoBeyondArt = GoBeyondArt.Abstract(accentHue),
)

data class GoBeyondFeed(
    val sections: List<GoBeyondSectionFeed>,
    val recommended: List<GoBeyondRecommendation>,
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
        private val originResolver: ArtistOriginResolver,
        private val genreResolver: ArtistGenreResolver,
        private val artResolver: ArtistArtworkResolver,
    ) {
        suspend fun feed(): GoBeyondFeed =
            withContext(Dispatchers.IO) {
                val events = runCatching { TelemetryStore.load(context) }.getOrDefault(emptyList())
                val imports = runCatching { ListenStore(context).load() }.getOrDefault(emptyList())
                val insights = TelemetryInsights.fromEvents(events)
                val artistNames = topArtistNames(insights, imports)
                val originCodes = originResolver.cached(artistNames.take(10))
                val countryHints =
                    originCodes.values
                        .filterNotNull()
                        .groupingBy { it }
                        .eachCount()
                        .mapNotNull { (code, count) ->
                            GoBeyondGeography.country(code)?.name?.let { name ->
                                name to (count * 0.8).coerceAtMost(1.6)
                            }
                        }
                        .toMap()
                val enrichedInsights =
                    if (countryHints.isEmpty()) {
                        insights
                    } else {
                        insights.copy(gatewayAffinity = insights.gatewayAffinity + countryHints)
                    }
                val cachedPhotos = artResolver.cached(artistNames)
                val missingPhotos =
                    artistNames
                        .filter { it !in cachedPhotos }
                        .take(ARTWORK_ARTISTS)
                val fetchedPhotos =
                    if (missingPhotos.isEmpty()) {
                        emptyMap()
                    } else {
                        withTimeoutOrNull(ARTWORK_BUDGET_MS) {
                            artResolver.resolve(missingPhotos)
                        }.orEmpty()
                    }
                val networkPhotos = cachedPhotos + fetchedPhotos
                val photoUrls =
                    artistNames.associateWith { name ->
                        networkPhotos[name]?.takeIf { it.isNotBlank() } ?: artistArtwork(name)
                    }
                val artistCards = GoBeyondCatalog.artistCards(artistNames, photoUrls)
                val orderedEvents = events.sortedByDescending { it.at }.take(200)
                val recentImports = imports.sortedByDescending { it.listenedAt }.take(200)
                val taste =
                    GoBeyondTasteProfile(
                        insights = enrichedInsights,
                        topArtists = artistNames.take(10),
                        recentTitles = orderedEvents.mapNotNull { it.title }.take(8),
                        recentAlbums = orderedEvents.mapNotNull { it.album }.take(4),
                        recentArtists = orderedEvents.mapNotNull { it.artist }.take(8),
                        importedTitles = recentImports.map { it.title }.take(6),
                        importedArtists = recentImports.map { it.artist }.take(6),
                        importedAlbums = recentImports.mapNotNull { it.album }.take(3),
                    )
                val countryCandidates =
                    GoBeyondCatalog
                        .cards(GoBeyondSection.COUNTRY)
                        .sortedWith(
                            compareByDescending<GoBeyondCard> { enrichedInsights.affinity(it.title) }
                                .thenBy { Math.floorMod(it.id.hashCode(), 10_000) },
                        )
                        .take(COUNTRY_POOL)
                val genreCandidates =
                    GoBeyondCatalog
                        .cards(GoBeyondSection.GENRE)
                        .sortedWith(
                            compareByDescending<GoBeyondCard> { insights.affinity(it.title) }
                                .thenBy { Math.floorMod(it.id.hashCode(), 10_000) },
                        )
                        .take(GENRE_POOL)
                val countryCards =
                    GoBeyondPersonalize
                        .orderCards(countryCandidates, taste)
                        .take(SECTION_CARDS)
                val genreCards =
                    GoBeyondPersonalize
                        .orderCards(genreCandidates, taste)
                        .take(SECTION_CARDS)
                val periodCards =
                    GoBeyondPersonalize
                        .orderCards(GoBeyondCatalog.cards(GoBeyondSection.PERIOD), taste)
                        .take(SECTION_CARDS)
                val colorCards =
                    GoBeyondPersonalize
                        .orderCards(GoBeyondCatalog.cards(GoBeyondSection.COLOR), taste)
                        .take(SECTION_CARDS)
                val moodCards =
                    GoBeyondPersonalize
                        .orderCards(GoBeyondCatalog.cards(GoBeyondSection.MOOD), taste)
                        .take(SECTION_CARDS)
                val allCards =
                    countryCards +
                        genreCards +
                        artistCards +
                        periodCards +
                        colorCards +
                        moodCards
                val recommendations =
                    GoBeyondPersonalize
                        .suggestions(allCards, taste, limit = RECOMMENDATION_CARDS)
                        .map { suggestion ->
                            GoBeyondRecommendation(
                                selection = suggestion.card.selection,
                                title = suggestion.card.title,
                                reason = suggestion.reason,
                                accentHue = suggestion.card.accentHue,
                                art = suggestion.card.art,
                            )
                        }

                val sections =
                    listOf(
                        sectionFeed(
                            GoBeyondSection.COUNTRY,
                            "Start with a place",
                            "Pick a country and keep the songs that fit your taste.",
                            countryCards,
                        ),
                        sectionFeed(
                            GoBeyondSection.GENRE,
                            "Start with a sound",
                            "A genre is a doorway. Familiar records stay close, new ones lead.",
                            genreCards,
                        ),
                        sectionFeed(
                            GoBeyondSection.ARTIST,
                            "Go deeper with an artist",
                            "Their catalogue, from the artists you actually play.",
                            artistCards,
                        ),
                        sectionFeed(
                            GoBeyondSection.PERIOD,
                            "Start with a time",
                            "A decade or your own year range, filtered through your listening.",
                            periodCards,
                        ),
                        sectionFeed(
                            GoBeyondSection.COLOR,
                            "Start with a colour",
                            "Music that sounds purple, silver or warm gold, not songs named after a colour.",
                            colorCards,
                        ),
                        sectionFeed(
                            GoBeyondSection.MOOD,
                            "Start with a feeling",
                            "Choose the emotional tone and let the playlist do the rest.",
                            moodCards,
                        ),
                    )

                GoBeyondFeed(
                    sections = sections,
                    recommended = recommendations,
                    insights = enrichedInsights,
                )
            }

        /**
         * Builds and stores an ordinary local playlist. The stable id means a
         * second tap refreshes the same journey instead of duplicating it.
         */
        suspend fun generate(rawSelection: GoBeyondSelection): String =
            withContext(Dispatchers.IO) {
                val selection = GoBeyondResolver.resolve(rawSelection)
                require(!selection.isBlank()) { "empty Go Beyond selection" }
                val playlistId = selection.stableId()
                val foundPool = search(selection)
                if (foundPool.isEmpty()) {
                    error("No tracks found for ${selection.displayTitle()}")
                }
                val found = foundPool.map { it.song }

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
                            targetScore = foundPool[index].targetScore,
                        )
                    }
                val rankContext =
                    GoBeyondRankContext(
                        bias = TelemetryTaste.fromEvents(events),
                        insights = insights,
                        promptVector = promptVector,
                        promptTerms = GoBeyondResolver.colorPromptTerms(selection.color),
                        literalTerms = GoBeyondResolver.literalColorTerms(selection.color),
                        heardIds = heardIds,
                        recentIds = recentIds,
                        skippedIds = skippedIds,
                        likedIds = likedIds,
                        maxPerArtist = if (selection.artist != null) 0 else MAX_PER_ARTIST,
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

        private suspend fun search(selection: GoBeyondSelection): List<PooledSong> {
            val merged = LinkedHashMap<String, PooledSong>()
            val artist = selection.artist?.trim()?.takeIf(String::isNotBlank)
            if (artist != null) {
                for (query in selection.artistSearchQueries().take(ARTIST_QUERIES)) {
                    addSongResults(
                        query = query,
                        targetScore = 1.0,
                        merged = merged,
                        artistFilter = artist,
                    )
                }
                return merged.values.take(MAX_CANDIDATES)
            }

            if (selection.country != null) {
                val playlistPool = LinkedHashMap<String, PooledSong>()
                for (query in selection.countrySearchQueries().take(COUNTRY_PLAYLIST_QUERIES)) {
                    addPlaylistResults(
                        query = query,
                        targetScore = 1.0,
                        merged = playlistPool,
                        requiredGenre = selection.genre,
                    )
                }
                val songPool = LinkedHashMap<String, PooledSong>()
                for (query in selection.countrySearchQueries().take(COUNTRY_SONG_QUERIES)) {
                    addSongResults(query = query, targetScore = 0.75, merged = songPool)
                }

                // A curated country playlist is only trustworthy when its
                // artists also show up in country-targeted song search. This
                // removes international tracks that ride along in a playlist
                // titled "Afghan music".
                val countryArtists =
                    songPool.values
                        .mapNotNull { pooled ->
                            pooled.song.artists.firstOrNull()?.name?.let(::normalizeName)
                        }
                        .filter { it.isNotBlank() }
                        .toSet()
                val verifiedPlaylist =
                    playlistPool.values.filter { pooled ->
                        val key = pooled.song.artists.firstOrNull()?.name?.let(::normalizeName).orEmpty()
                        key.isNotBlank() && key in countryArtists
                    }
                val candidatePool =
                    when {
                        verifiedPlaylist.size >= COUNTRY_CURATED_MIN -> verifiedPlaylist
                        songPool.isNotEmpty() -> songPool.values.toList()
                        else -> playlistPool.values.toList()
                    }
                val targetCode = GoBeyondGeography.byName(selection.country)?.code
                val originFiltered =
                    if (targetCode == null) {
                        candidatePool
                    } else {
                        val uniqueArtists =
                            candidatePool
                                .mapNotNull { it.song.artists.firstOrNull()?.name }
                                .distinct()
                                .take(ORIGIN_ARTISTS)
                        val origins = originResolver.resolve(uniqueArtists)
                        candidatePool.filter { pooled ->
                            val key =
                                pooled.song.artists.firstOrNull()?.name
                                    ?.let(::normalizeName)
                                    .orEmpty()
                            val origin = origins[key]
                            origin == null || origin == targetCode
                        }
                    }
                return constrainToGenre(selection, originFiltered.ifEmpty { candidatePool })
                    .take(MAX_CANDIDATES)
            }

            for (query in selection.searchQueries().take(GENERAL_QUERIES)) {
                addSongResults(query = query, targetScore = 0.60, merged = merged)
            }
            if (merged.size < RADIO_TRIGGER) {
                merged.values.take(RADIO_SEEDS).forEach { seed ->
                    val result =
                        runCatching {
                            YouTube.next(WatchEndpoint(videoId = seed.song.id)).getOrNull()
                        }.getOrNull() ?: return@forEach
                    result.items
                        .asSequence()
                        .filterIsInstance<SongItem>()
                        .filter(::isUsableSong)
                        .take(40)
                        .forEach { song -> merge(merged, song, 0.35) }
                }
            }
            return constrainToGenre(selection, merged.values.toList()).take(MAX_CANDIDATES)
        }

        /**
         * Keeps only artists whose MusicBrainz tags contain the requested genre.
         * Artists without tag data remain eligible as filler, so a sparse genre
         * still returns something without letting known cumbia into a rock list.
         */
        private suspend fun constrainToGenre(
            selection: GoBeyondSelection,
            pool: List<PooledSong>,
        ): List<PooledSong> {
            val genre = selection.genre?.trim()?.takeIf(String::isNotBlank) ?: return pool
            if (pool.isEmpty()) return pool
            val names =
                pool.mapNotNull { it.song.artists.firstOrNull()?.name }
                    .distinct()
                    .take(GENRE_ARTISTS)
            val lookup = genreResolver.resolve(names)
            if (!lookup.online && lookup.tags.values.all { it.isNullOrEmpty() }) {
                Timber.tag(TAG).w("genre=%s skipped: MusicBrainz unavailable", genre)
                return pool
            }
            val result =
                GoBeyondGenre.filter(
                    genre = genre,
                    items = pool,
                    tagsOf = { pooled ->
                        pooled.song.artists.firstOrNull()?.name
                            ?.let(::normalizeName)
                            ?.let(lookup.tags::get)
                    },
                    fillTo = GENRE_FILL,
                )
            Timber.tag(TAG).i(
                "genre=%s verified=%d unknown=%d rejected=%d kept=%d",
                genre,
                result.verified,
                result.unknown,
                result.rejected,
                result.accepted.size,
            )
            if (result.verified >= GENRE_MIN_VERIFIED) {
                return pool.filter { pooled ->
                    val tags =
                        pooled.song.artists.firstOrNull()?.name
                            ?.let(::normalizeName)
                            ?.let(lookup.tags::get)
                    !tags.isNullOrEmpty() && GoBeyondGenre.matches(genre, tags)
                }
            }
            return result.accepted.ifEmpty { pool }
        }

        private suspend fun addSongResults(
            query: String,
            targetScore: Double,
            merged: LinkedHashMap<String, PooledSong>,
            artistFilter: String? = null,
        ) {
            val result =
                runCatching {
                    YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                }.getOrNull() ?: return
            result.items
                .asSequence()
                .filterIsInstance<SongItem>()
                .filter(::isUsableSong)
                .filter { song ->
                    artistFilter == null || song.artists.any { matchesArtist(it.name, artistFilter) }
                }
                .filter { song ->
                    artistFilter == null || !looksLikeCover(song.title)
                }
                .take(40)
                .forEach { song -> merge(merged, song, targetScore) }
        }

        private suspend fun addPlaylistResults(
            query: String,
            targetScore: Double,
            merged: LinkedHashMap<String, PooledSong>,
            requiredGenre: String? = null,
        ) {
            val playlists =
                runCatching {
                    YouTube.search(query, YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST).getOrNull()
                }.getOrNull()
                    ?.items
                    ?.filterIsInstance<PlaylistItem>()
                    ?.filter { playlist ->
                        requiredGenre.isNullOrBlank() ||
                            GoBeyondGenre.matches(requiredGenre, listOf(playlist.title))
                    }
                    ?.take(1)
                    .orEmpty()
            for (playlist in playlists) {
                val page = runCatching { YouTube.playlist(playlist.id).getOrNull() }.getOrNull() ?: continue
                page.songs
                    .asSequence()
                    .filter(::isUsableSong)
                    .take(60)
                    .forEach { song -> merge(merged, song, targetScore) }
            }
        }

        private fun isUsableSong(song: SongItem): Boolean =
            !song.isEpisode &&
                !song.isVideoSong &&
                song.id.isNotBlank() &&
                song.title.isNotBlank() &&
                song.artists.any { it.name.isNotBlank() }

        private fun merge(
            merged: LinkedHashMap<String, PooledSong>,
            song: SongItem,
            targetScore: Double,
        ) {
            val existing = merged[song.id]
            if (existing == null || targetScore > existing.targetScore) {
                merged[song.id] = PooledSong(song, targetScore)
            }
        }

        private fun matchesArtist(
            candidate: String,
            target: String,
        ): Boolean {
            val a = normalizeName(candidate)
            val b = normalizeName(target)
            if (a.isBlank() || b.isBlank()) return false
            return a == b ||
                a.startsWith("$b ") ||
                b.startsWith("$a ") ||
                GoBeyondFuzzy.ratio(a, b) >= 0.82
        }

        private fun looksLikeCover(title: String): Boolean {
            val value = title.lowercase(Locale.ROOT)
            return value.contains("tribute") ||
                value.contains("in the style of") ||
                value.contains("cover") ||
                value.contains("karaoke")
        }

        private fun normalizeName(value: String): String =
            java.text.Normalizer
                .normalize(value.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()

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
        ) = GoBeyondSectionFeed(
            section = section,
            title = title,
            subtitle = subtitle,
            cards = cards,
        )

        private suspend fun artistArtwork(name: String): String? {
            val artist = runCatching { database.artistByName(name) }.getOrNull() ?: return null
            artist.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { return it }
            val now = LocalDateTime.now()
            return runCatching {
                database
                    .mostPlayedSongsByArtist(
                        artistId = artist.id,
                        fromTimeStamp = LocalDateTime.of(1970, 1, 1, 0, 0),
                        toTimeStamp = now,
                    ).first()
                    .firstOrNull()
                    ?.song
                    ?.thumbnailUrl
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
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

        private data class PooledSong(
            val song: SongItem,
            val targetScore: Double,
        )

        private fun selectionKey(playlistId: String) = stringPreferencesKey("radioStarGoBeyondSelection_$playlistId")

        companion object {
            const val ID_PREFIX = "LP_RS_GO_"
            private const val TAG = "GoBeyond"
            const val SLATE_SIZE = 30
            const val MAX_CANDIDATES = 120
            const val RADIO_TRIGGER = 60
            const val RADIO_SEEDS = 2
            const val ARTIST_QUERIES = 6
            const val COUNTRY_PLAYLIST_QUERIES = 2
            const val COUNTRY_SONG_QUERIES = 5
            const val COUNTRY_CURATED_MIN = 15
            const val ORIGIN_ARTISTS = 200
            const val GENRE_ARTISTS = 120
            const val GENRE_FILL = 10
            const val GENRE_MIN_VERIFIED = 4
            const val GENERAL_QUERIES = 4
            const val MAX_ARTIST_CARDS = 12
            const val ARTWORK_ARTISTS = 8
            const val ARTWORK_BUDGET_MS = 3_000L
            const val SECTION_CARDS = 16
            const val COUNTRY_POOL = 40
            const val GENRE_POOL = 32
            const val RECOMMENDATION_CARDS = 8
            const val MAX_PER_ARTIST = 2
            const val RECENT_MS = 30L * 86_400_000L
            const val SKIP_MEMORY_MS = 90L * 86_400_000L
        }
    }

private fun FloatArray.toDoubles(): DoubleArray = DoubleArray(size) { this[it].toDouble() }
