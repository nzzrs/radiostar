/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.constants.RadioStarResolvedListensKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.utils.dataStore
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

data class TopListen(
    val title: String,
    val artist: String,
    val count: Int,
)

/**
 * Counts imported listens per track and resolves the most listened ones to
 * YouTube songs so the upstream Weekly/Monthly Most playlists can include
 * history that was imported from another service.
 *
 * Resolution is cached by title+artist, so a weekly rebuild does not repeat
 * the same searches. A failed lookup is retried on the next rebuild; a wrong
 * match can be corrected by clearing the cache in a future settings action.
 */
object ImportedMostSongs {
    private const val TAG = "RadioStarMost"
    private const val MAX_RESOLVE = 24

    fun topListens(
        listens: List<ImportedListen>,
        sinceEpochSeconds: Long,
        limit: Int,
    ): List<TopListen> {
        if (limit <= 0) return emptyList()
        val buckets = LinkedHashMap<String, Pair<ImportedListen, Int>>()
        listens
            .asSequence()
            .filter { it.listenedAt >= sinceEpochSeconds }
            .forEach { listen ->
                val key =
                    listen.artist.trim().lowercase(Locale.ROOT) +
                        "|" +
                        listen.title.trim().lowercase(Locale.ROOT)
                val previous = buckets[key]
                buckets[key] =
                    if (previous == null) {
                        listen to 1
                    } else {
                        // Keep the newest spelling of the title/artist.
                        val newest =
                            if (listen.listenedAt >= previous.first.listenedAt) listen else previous.first
                        newest to previous.second + 1
                    }
            }
        return buckets.values
            .sortedByDescending { it.second }
            .take(limit)
            .map { (listen, count) -> TopListen(listen.title, listen.artist, count) }
    }

    /** Resolves and inserts the top imported tracks, returning their song ids. */
    suspend fun resolve(
        context: Context,
        database: MusicDatabase,
        sinceEpochSeconds: Long,
        limit: Int,
    ): List<String> =
        withContext(Dispatchers.IO) {
            val listens = ListenStore(context).load()
            val top = topListens(listens, sinceEpochSeconds, limit.coerceAtMost(MAX_RESOLVE))
            if (top.isEmpty()) return@withContext emptyList()
            val cache = loadCache(context)
            val resolved = ArrayList<String>()
            for (item in top) {
                val key = key(item.title, item.artist)
                val cached = cache.optString(key).takeIf { it.isNotBlank() }
                if (cached != null && database.songEntity(cached) != null) {
                    resolved.add(cached)
                    continue
                }
                val found = search(item)
                if (found == null) {
                    Timber.tag(TAG).i("no match for %s - %s", item.title, item.artist)
                    continue
                }
                insert(database, found)
                cache.put(key, found.id)
                resolved.add(found.id)
            }
            if (resolved.isNotEmpty()) {
                context.dataStore.edit { it[RadioStarResolvedListensKey] = cache.toString() }
            }
            resolved.distinct()
        }

    private suspend fun search(item: TopListen): SongItem? =
        runCatching {
            YouTube.search("${item.title} ${item.artist}", YouTube.SearchFilter.FILTER_SONG)
                .getOrNull()
                ?.items
                ?.filterIsInstance<SongItem>()
                ?.firstOrNull()
        }.getOrNull()

    private suspend fun insert(
        database: MusicDatabase,
        song: SongItem,
    ) {
        val tag = song.toMediaMetadata()
        database.withTransaction {
            database.insert(tag.toSongEntity())
            tag.artists.forEachIndexed { index, artist ->
                val artistId =
                    artist.id?.takeIf { it.isNotBlank() }
                        ?: "RS_${abs(artist.name.hashCode())}"
                database.insert(ArtistEntity(id = artistId, name = artist.name))
                database.insert(SongArtistMap(songId = tag.id, artistId = artistId, position = index))
            }
        }
    }

    private suspend fun loadCache(context: Context): JSONObject =
        runCatching {
            JSONObject(context.dataStore.data.first()[RadioStarResolvedListensKey].orEmpty())
        }.getOrDefault(JSONObject())

    private fun key(
        title: String,
        artist: String,
    ): String =
        artist.trim().lowercase(Locale.ROOT) + "|" + title.trim().lowercase(Locale.ROOT)
}
