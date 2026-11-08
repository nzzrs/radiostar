/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
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
import org.json.JSONObject
import timber.log.Timber

/**
 * Resolves an artist's country with MusicBrainz, with a local cache.
 *
 * Country journeys need to know where an artist is from; YouTube metadata does
 * not carry it. MusicBrainz is an open, keyless source. Requests are batched
 * and rate-limited, unknown answers are cached too, and a network failure
 * never blocks playlist generation: the caller simply falls back to the
 * search/playlist provenance it already has.
 */
@Singleton
class ArtistOriginResolver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private data class Cached(
            val country: String?,
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
                .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .build()

        /** Cache-only lookup used by the fast landing view. */
        suspend fun cached(artists: List<String>): Map<String, String?> =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    ensureLoaded()
                    artists
                        .map { GoBeyondFuzzy.normalize(it) }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .associateWith { key ->
                            cache[key]
                                ?.takeIf { System.currentTimeMillis() - it.at <= CACHE_MS }
                                ?.country
                        }
                }
            }

        /** Resolve unknown artists in small batches and remember the answers. */
        suspend fun resolve(artists: List<String>): Map<String, String?> =
            withContext(Dispatchers.IO) {
                val requested =
                    artists
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinctBy { GoBeyondFuzzy.normalize(it) }
                if (requested.isEmpty()) return@withContext emptyMap()

                val known = HashMap<String, String?>()
                val missing = ArrayList<String>()
                mutex.withLock {
                    ensureLoaded()
                    val now = System.currentTimeMillis()
                    requested.forEach { artist ->
                        val key = GoBeyondFuzzy.normalize(artist)
                        val item = cache[key]
                        if (item != null && now - item.at <= CACHE_MS) {
                            known[key] = item.country
                        } else {
                            missing += artist
                        }
                    }
                }

                val chunks = missing.chunked(BATCH)
                chunks.forEachIndexed { index, chunk ->
                    val resolved = query(chunk)
                    mutex.withLock {
                        val now = System.currentTimeMillis()
                        chunk.forEach { artist ->
                            val key = GoBeyondFuzzy.normalize(artist)
                            val country = resolved[key]
                            known[key] = country
                            cache[key] = Cached(country, now)
                            append(key, country, now)
                        }
                    }
                    if (index < chunks.size - 1) {
                        delay(1_100L)
                    }
                }
                known
            }

        private fun query(artists: List<String>): Map<String, String?> {
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
                    .build()
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", "RadioStar/0.9.2 (https://github.com/nzzrs/radiostar)")
                    .get()
                    .build()
            return runCatching {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyMap()
                    val root = JSONObject(response.body.string())
                    val array = root.optJSONArray("artists") ?: return@use emptyMap()
                    val byName =
                        artists.associateBy { GoBeyondFuzzy.normalize(it) }
                    val result = HashMap<String, String?>()
                    for (index in 0 until array.length()) {
                        val objectValue = array.optJSONObject(index) ?: continue
                        val name = objectValue.optString("name")
                        val key =
                            byName.entries
                                .maxByOrNull { (_, value) -> GoBeyondFuzzy.ratio(value, name) }
                                ?.takeIf { (_, value) -> GoBeyondFuzzy.ratio(value, name) >= 0.82 }
                                ?.key
                                ?: continue
                        val code =
                            objectValue.optString("country")
                                .takeIf { it.length == 2 }
                                ?: objectValue
                                    .optJSONObject("area")
                                    ?.optJSONArray("iso-3166-1-codes")
                                    ?.optString(0)
                                    ?.takeIf { it.length == 2 }
                        if (code != null) result[key] = code.uppercase(Locale.ROOT)
                    }
                    result
                }
            }.onFailure { Timber.tag(TAG).w(it, "MusicBrainz origin lookup failed") }
                .getOrDefault(emptyMap())
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
                    cache[key] =
                        Cached(
                            country = json.optString("country").takeIf { it.length == 2 },
                            at = json.optLong("at", 0L),
                        )
                }
            }
        }

        private fun append(
            artist: String,
            country: String?,
            at: Long,
        ) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(
                    JSONObject()
                        .put("artist", artist)
                        .put("country", country ?: JSONObject.NULL)
                        .put("at", at)
                        .toString() + "\n",
                )
            }
        }

        private companion object {
            const val TAG = "GoBeyondOrigins"
            const val FILE_NAME = "radiostar_artist_origins.jsonl"
            const val BATCH = 25
            const val CACHE_MS = 30L * 86_400_000L
        }
    }
