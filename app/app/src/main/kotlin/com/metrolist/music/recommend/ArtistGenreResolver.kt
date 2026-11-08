/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Fetches MusicBrainz tags for artists so a genre journey can reject tracks by
 * artists that are known to belong to another genre.
 *
 * The search endpoint accepts `inc=tags`, so one request returns tags for up to
 * 25 artists. Results are cached on disk for 30 days; a network failure is
 * never cached, because "no data" and "no such tag" mean different things.
 */
@Singleton
class ArtistGenreResolver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        data class Lookup(
            val tags: Map<String, Set<String>?>,
            val online: Boolean,
        )

        private data class Cached(
            val tags: Set<String>?,
            val at: Long,
        )

        private val file = File(context.filesDir, FILE_NAME)
        private val mutex = Mutex()
        private var loaded = false
        private val cache = LinkedHashMap<String, Cached>()

        private val http =
            OkHttpClient
                .Builder()
                .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .build()

        suspend fun resolve(artists: List<String>): Lookup =
            withContext(Dispatchers.IO) {
                val requested =
                    artists
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinctBy { GoBeyondFuzzy.normalize(it) }
                if (requested.isEmpty()) return@withContext Lookup(emptyMap(), true)

                val known = HashMap<String, Set<String>?>()
                val missing = ArrayList<String>()
                mutex.withLock {
                    ensureLoaded()
                    val now = System.currentTimeMillis()
                    requested.forEach { artist ->
                        val key = GoBeyondFuzzy.normalize(artist)
                        val item = cache[key]
                        if (item != null && now - item.at <= CACHE_MS) {
                            known[key] = item.tags
                        } else {
                            missing += artist
                        }
                    }
                }

                var online = true
                val chunks = missing.chunked(BATCH)
                chunks.forEachIndexed { index, chunk ->
                    val resolved =
                        runCatching { query(chunk) }
                            .onFailure { Timber.tag(TAG).w(it, "MusicBrainz tag lookup failed") }
                            .getOrNull()
                    if (resolved == null) {
                        online = false
                    } else {
                        mutex.withLock {
                            val now = System.currentTimeMillis()
                            chunk.forEach { artist ->
                                val key = GoBeyondFuzzy.normalize(artist)
                                val tags = resolved[key]
                                known[key] = tags
                                cache[key] = Cached(tags, now)
                                append(key, tags, now)
                            }
                        }
                    }
                    if (index < chunks.size - 1) delay(1_100L)
                }
                Lookup(known, online)
            }

        private fun query(artists: List<String>): Map<String, Set<String>?> {
            val terms =
                artists.joinToString(" OR ") { artist ->
                    "\"" + artist.replace("\"", "") + "\""
                }
            val url =
                "https://musicbrainz.org/ws/2/artist"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("query", "artist:($terms)")
                    .addQueryParameter("fmt", "json")
                    .addQueryParameter("limit", "100")
                    .addQueryParameter("inc", "tags")
                    .build()
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", "RadioStar/0.9.2 (https://github.com/nzzrs/radiostar)")
                    .get()
                    .build()
            return http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("MusicBrainz HTTP ${response.code}")
                val root = JSONObject(response.body.string())
                val array = root.optJSONArray("artists") ?: JSONArray()
                val byName = artists.associateBy { GoBeyondFuzzy.normalize(it) }
                val result = HashMap<String, Set<String>?>()
                for (index in 0 until array.length()) {
                    val objectValue = array.optJSONObject(index) ?: continue
                    val name = objectValue.optString("name")
                    val key =
                        byName.entries
                            .maxByOrNull { (_, value) -> GoBeyondFuzzy.ratio(value, name) }
                            ?.takeIf { (_, value) -> GoBeyondFuzzy.ratio(value, name) >= 0.82 }
                            ?.key
                            ?: continue
                    val tags = LinkedHashSet<String>()
                    val tagArray = objectValue.optJSONArray("tags")
                    if (tagArray != null) {
                        for (tagIndex in 0 until tagArray.length()) {
                            val tag = tagArray.optJSONObject(tagIndex)?.optString("name").orEmpty()
                            if (tag.isNotBlank()) tags += tag
                        }
                    }
                    result[key] = tags
                }
                result
            }
        }

        private fun ensureLoaded() {
            if (loaded) return
            loaded = true
            if (!file.exists()) return
            runCatching {
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                    val key = json.optString("artist").takeIf { it.isNotBlank() } ?: return@forEachLine
                    val tags =
                        json.optJSONArray("tags")?.let { array ->
                            (0 until array.length())
                                .mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                                .toSet()
                        }
                    cache[key] = Cached(tags, json.optLong("at", 0L))
                }
            }
        }

        private fun append(
            artist: String,
            tags: Set<String>?,
            at: Long,
        ) {
            runCatching {
                file.parentFile?.mkdirs()
                val array = JSONArray()
                tags?.forEach { array.put(it) }
                file.appendText(
                    JSONObject()
                        .put("artist", artist)
                        .put("tags", if (tags == null) JSONObject.NULL else array)
                        .put("at", at)
                        .toString() + "\n",
                )
            }
        }

        private companion object {
            const val TAG = "GoBeyondGenres"
            const val FILE_NAME = "radiostar_artist_genres.jsonl"
            const val BATCH = 25
            const val CACHE_MS = 30L * 86_400_000L
        }
    }
