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
import com.metrolist.music.constants.RadioStarProfilesKey
import com.metrolist.music.constants.RadioStarServerKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.WeekFields
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Builds the seven RadioStar playlists from the local listening history and
 * stores them as ordinary local playlists.
 *
 * The engine never touches the playback queue. The user opens a playlist and
 * plays it like any other.
 */
@Singleton
class RadioStarPlaylistRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
    ) {
        data class Card(
            val id: String,
            val title: String,
            val horizon: PlaylistHorizon,
            val trackCount: Int,
        )

        private val mutableCards = MutableStateFlow<List<Card>>(emptyList())
        val cards: StateFlow<List<Card>> = mutableCards.asStateFlow()

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutex = Mutex()

        init {
            scope.launch { loadCards() }
        }

        /**
         * Generates whatever is stale. Daily and weekly stamps are independent,
         * so opening Home once a day does not rebuild the weekly lists.
         */
        suspend fun refreshIfDue(force: Boolean = false) {
            mutex.withLock {
                val today = LocalDate.now()
                val prefs = context.dataStore.data.first()
                val dailyDue = force || prefs[DAILY_STAMP] != today.toString()
                val weeklyDue = force || prefs[WEEKLY_STAMP] != isoWeek(today)
                val cherryDue = force || prefs[CHERRY_STAMP] != today.toString()
                if (!dailyDue && !weeklyDue && !cherryDue) {
                    loadCards()
                    return
                }

                val server = serverUrl(prefs)
                if (server.isNullOrBlank()) {
                    Timber.tag(TAG).i("no embedding server; keeping existing playlists")
                    loadCards()
                    return
                }

                try {
                    if (dailyDue) {
                        generateGroup(RadioStarPlaylistSpecs.daily, days = 7, anchors = 5, perAnchor = 18, server)
                    }
                    if (weeklyDue) {
                        generateGroup(RadioStarPlaylistSpecs.weekly, days = 30, anchors = 8, perAnchor = 18, server)
                    }
                    if (cherryDue) {
                        generateGroup(listOf(RadioStarPlaylistSpecs.cherry), days = 14, anchors = 6, perAnchor = 18, server)
                    }
                    context.dataStore.edit { settings ->
                        if (dailyDue) settings[DAILY_STAMP] = today.toString()
                        if (weeklyDue) settings[WEEKLY_STAMP] = isoWeek(today)
                        if (cherryDue) settings[CHERRY_STAMP] = today.toString()
                    }
                    Timber.tag(TAG).i(
                        "generated daily=%s weekly=%s cherry=%s",
                        dailyDue,
                        weeklyDue,
                        cherryDue,
                    )
                } catch (error: Throwable) {
                    Timber.tag(TAG).e(error, "playlist generation failed")
                }
                loadCards()
            }
        }

        private suspend fun generateGroup(
            specs: List<PlaylistSpec>,
            days: Long,
            anchors: Int,
            perAnchor: Int,
            server: String,
        ) {
            val history = history(days, limit = if (days <= 7) 40 else 80)
            if (history.isEmpty()) {
                Timber.tag(TAG).i("no listening history for %d-day playlists", days)
                return
            }
            val heard = history(365, limit = 500)
            val anchorSongs = history.take(anchors)
            val radio = radioItems(anchorSongs, perAnchor)

            val historyPool = history.mapNotNull { it.toPoolSong() }
            val allPool =
                (radio.mapNotNull { it.toPoolSong() } + historyPool)
                    .distinctBy { it.id }
                    .filter { it.title.isNotBlank() && it.artists.any { artist -> artist.isNotBlank() } }
                    .take(160)
            if (allPool.isEmpty()) {
                Timber.tag(TAG).i("empty candidate pool")
                return
            }

            val vectors =
                EmbeddingClient.embed(
                    server,
                    allPool.map { EmbeddingClient.Item(it.artistText, it.title) },
                ) ?: run {
                    Timber.tag(TAG).w("embedding failed for %d pool items", allPool.size)
                    return
                }
            val byId = allPool.zip(vectors).associate { (song, vector) -> song.id to vector }
            val historyVectors = historyPool.mapNotNull { byId[it.id] }
            val centroid = centroid(historyVectors) ?: return

            val profiles = loadProfiles()
            val referenceId = anchorSongs.firstOrNull()?.song?.id
            val heardIds = heard.mapTo(HashSet()) { it.song.id }
            val heardKeys =
                heard.mapTo(HashSet()) { song ->
                    ListenHistory.key(
                        song.song.title,
                        song.artists.firstOrNull()?.name.orEmpty(),
                    )
                }
            val radioIds = radio.mapNotNull { it.id }.toSet()

            for (spec in specs) {
                val pool =
                    if (spec.kind == MixKind.DISCOVER || spec.kind == MixKind.EXPLORE) {
                        allPool.filter { it.id in radioIds }
                    } else {
                        allPool
                    }
                val candidates =
                    pool.mapNotNull { song ->
                        val vector = byId[song.id] ?: return@mapNotNull null
                        Candidate(
                            id = song.id,
                            title = song.title,
                            artists = song.artists,
                            vector = DoubleArray(vector.size) { vector[it].toDouble() },
                            durationSeconds = song.duration,
                            tag = song.tag,
                        )
                    }
                if (candidates.size < 2) continue
                val slate =
                    MixBuilder.build(
                        session = centroid,
                        candidates = candidates,
                        kind = spec.kind,
                        k = spec.size,
                        historyIds = heardIds,
                        historyKeys = heardKeys,
                    )
                if (slate.isEmpty()) continue
                val ordered = orderSlate(slate, profiles, referenceId)
                writePlaylist(spec, ordered)
                Timber.tag(TAG).i(
                    "wrote %s kind=%s tracks=%d",
                    spec.title,
                    spec.kind,
                    slate.size,
                )
            }
        }

        /**
         * Reorders only tracks the phone has already analysed. Unknown
         * candidates keep their DPP position, so a cold playlist is unchanged.
         */
        private fun orderSlate(
            entries: List<SlateEntry>,
            profiles: Map<String, AudioProfile>,
            referenceId: String?,
        ): List<SlateEntry> {
            if (entries.size < 2) return entries
            val known = entries.count { profiles[it.candidate.id] != null }
            if (known >= 2) {
                val reference =
                    referenceId?.let { profiles[it] }
                        ?: entries.firstOrNull()?.candidate?.id?.let { profiles[it] }
                return AudioOrderer.harmonicPath(entries, profiles, reference)
            }
            val rms =
                profiles.mapNotNull { (id, profile) ->
                    profile.rms?.let { id to it }
                }.toMap()
            return EnergyOrderer.orderByEnergyArc(entries, rms)
        }

        private suspend fun loadProfiles(): Map<String, AudioProfile> {
            val text = context.dataStore.data.first()[RadioStarProfilesKey] ?: return emptyMap()
            return runCatching {
                val root = JSONObject(text)
                val result = HashMap<String, AudioProfile>()
                root.keys().forEach { id ->
                    val item = root.getJSONObject(id)
                    result[id] =
                        AudioProfile(
                            bpm = if (item.has("bpm")) item.getDouble("bpm") else null,
                            key =
                                if (item.has("root")) {
                                    MusicalKey(item.getInt("root"), item.optBoolean("minor"))
                                } else {
                                    null
                                },
                            rms = if (item.has("rms")) item.getDouble("rms").toFloat() else null,
                        )
                }
                result
            }.getOrElse {
                Timber.tag(TAG).w(it, "bad profile store")
                emptyMap()
            }
        }

        private suspend fun writePlaylist(
            spec: PlaylistSpec,
            slate: List<SlateEntry>,
        ) {
            val now = LocalDateTime.now()
            database.withTransaction {
                val existing = database.playlistEntity(spec.id)
                val entity =
                    (existing ?: PlaylistEntity(id = spec.id, name = spec.title, createdAt = now))
                        .copy(
                            name = spec.title,
                            isEditable = false,
                            bookmarkedAt = existing?.bookmarkedAt ?: now,
                            lastUpdateTime = now,
                            thumbnailUrl = slate.firstOrNull()?.candidate?.tag?.thumbnailUrl,
                        )
                if (existing == null) database.insert(entity) else database.update(entity)
                database.clearPlaylist(spec.id)

                slate.forEachIndexed { position, entry ->
                    val tag = entry.candidate.tag ?: return@forEachIndexed
                    database.insert(tag.toSongEntity())
                    tag.artists.forEachIndexed { index, artist ->
                        val artistId =
                            artist.id?.takeIf { it.isNotBlank() }
                                ?: "RS_${abs(artist.name.hashCode())}"
                        database.insert(ArtistEntity(id = artistId, name = artist.name))
                        database.insert(SongArtistMap(songId = tag.id, artistId = artistId, position = index))
                    }
                    database.insert(
                        PlaylistSongMap(
                            playlistId = spec.id,
                            songId = tag.id,
                            position = position,
                        ),
                    )
                }
            }
        }

        private suspend fun loadCards() {
            val loaded =
                RadioStarPlaylistSpecs.all.mapNotNull { spec ->
                    val playlist = database.playlist(spec.id).first() ?: return@mapNotNull null
                    if (playlist.songCount == 0) return@mapNotNull null
                    Card(spec.id, spec.title, spec.horizon, playlist.songCount)
                }
            mutableCards.value = loaded
            Timber.tag(TAG).i("cards=%d", loaded.size)
        }

        private suspend fun history(days: Long, limit: Int): List<Song> {
            val now = LocalDateTime.now()
            val recent =
                database
                    .mostPlayedSongs(fromTimeStamp = now.minusDays(days), limit = limit, toTimeStamp = now)
                    .first()
                    .filter { !it.song.isVideo }
            if (recent.isNotEmpty()) return recent
            return database
                .mostPlayedSongs(
                    fromTimeStamp = LocalDateTime.of(1970, 1, 1, 0, 0),
                    limit = limit,
                    toTimeStamp = now,
                ).first()
                .filter { !it.song.isVideo }
        }

        private suspend fun radioItems(
            anchors: List<Song>,
            perAnchor: Int,
        ): List<SongItem> {
            val merged = LinkedHashMap<String, SongItem>()
            for (anchor in anchors) {
                val result =
                    runCatching {
                        YouTube.next(WatchEndpoint(videoId = anchor.song.id)).getOrNull()
                    }.getOrNull() ?: continue
                result.items
                    .asSequence()
                    .filter { !it.isEpisode }
                    .take(perAnchor)
                    .forEach { merged.putIfAbsent(it.id, it) }
            }
            return merged.values.toList()
        }

        private fun Song.toPoolSong(): PoolSong? {
            if (song.title.isBlank() || song.id.isBlank()) return null
            val tag = toMediaMetadata()
            if (tag.artists.none { it.name.isNotBlank() }) return null
            return PoolSong(
                id = song.id,
                title = song.title,
                artists = tag.artists.map { it.name },
                duration = song.duration,
                tag = tag,
            )
        }

        private fun SongItem.toPoolSong(): PoolSong? {
            if (id.isBlank() || title.isBlank()) return null
            val tag = toMediaMetadata()
            if (tag.artists.none { it.name.isNotBlank() }) return null
            return PoolSong(
                id = id,
                title = title,
                artists = tag.artists.map { it.name },
                duration = duration,
                tag = tag,
            )
        }

        private fun centroid(vectors: List<FloatArray>): DoubleArray? {
            val usable = vectors.filter { it.isNotEmpty() }
            if (usable.isEmpty()) return null
            val sum = DoubleArray(usable.first().size)
            usable.forEach { vector ->
                if (vector.size != sum.size) return@forEach
                for (index in sum.indices) sum[index] += vector[index]
            }
            var norm = 0.0
            for (value in sum) norm += value * value
            norm = sqrt(norm)
            if (norm < 1e-9) return null
            for (index in sum.indices) sum[index] /= norm
            return sum
        }

        private fun serverUrl(prefs: androidx.datastore.preferences.core.Preferences): String? =
            prefs[RadioStarServerKey]?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
                ?: Settings.Global
                    .getString(context.contentResolver, "radiostar_embed_server")
                    ?.trim()
                    ?.trimEnd('/')
                    ?.takeIf { it.isNotEmpty() }

        private fun isoWeek(date: LocalDate): String {
            val fields = WeekFields.ISO
            return "${date.get(fields.weekBasedYear())}-W${date.get(fields.weekOfWeekBasedYear())}"
        }

        private data class PoolSong(
            val id: String,
            val title: String,
            val artists: List<String>,
            val duration: Int?,
            val tag: MediaMetadata,
        ) {
            val artistText: String
                get() = artists.joinToString(", ")
        }

        private companion object {
            const val TAG = "RadioStarPlaylists"

            val DAILY_STAMP = stringPreferencesKey("radioStarDailyStamp")
            val WEEKLY_STAMP = stringPreferencesKey("radioStarWeeklyStamp")
            val CHERRY_STAMP = stringPreferencesKey("radioStarCherryStamp")
        }
    }
