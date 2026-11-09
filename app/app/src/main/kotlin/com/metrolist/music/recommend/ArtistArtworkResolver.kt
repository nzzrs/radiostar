/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.content.Context
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistItem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import timber.log.Timber

/**
 * Resolves a real artist image for Go Beyond artist cards from YouTube Music.
 *
 * The artist page immersive header exposes a wide banner, which is the best
 * card artwork. When it cannot be read, the square profile thumbnail from the
 * artist search result stands in. Answers are cached on disk, including misses,
 * so repeated card renders stay offline and cheap.
 */
@Singleton
class ArtistArtworkResolver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private data class Cached(
            val url: String?,
            val at: Long,
        )

        /** A lookup is cacheable unless it failed before the search succeeded. */
        private data class Lookup(
            val cacheable: Boolean,
            val url: String?,
        )

        private val file = File(context.filesDir, FILE_NAME)
        private val mutex = Mutex()
        private val permits = Semaphore(MAX_CONCURRENT)
        private var loaded = false
        private val cache = LinkedHashMap<String, Cached>()

        /** Cached URLs only, no network. Names map keyed by the exact input name. */
        suspend fun cached(names: List<String>): Map<String, String?> =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    ensureLoaded()
                    val now = System.currentTimeMillis()
                    val result = HashMap<String, String?>()
                    names.forEach { name ->
                        val key = GoBeyondFuzzy.normalize(name)
                        if (key.isBlank()) return@forEach
                        val item = cache[key]?.takeIf { now - it.at <= CACHE_MS } ?: return@forEach
                        result[name] = item.url
                    }
                    result
                }
            }

        /** Resolve missing names from YouTube Music with limited concurrency and cache. */
        suspend fun resolve(names: List<String>): Map<String, String?> =
            withContext(Dispatchers.IO) {
                val requested =
                    names
                        .filter { GoBeyondFuzzy.normalize(it).isNotBlank() }
                        .distinctBy { GoBeyondFuzzy.normalize(it) }
                if (requested.isEmpty()) return@withContext emptyMap()

                val missing = ArrayList<String>()
                mutex.withLock {
                    ensureLoaded()
                    val now = System.currentTimeMillis()
                    requested.forEach { name ->
                        val item = cache[GoBeyondFuzzy.normalize(name)]
                        if (item == null || now - item.at > CACHE_MS) missing += name
                    }
                }

                if (missing.isNotEmpty()) {
                    coroutineScope {
                        missing.map { name ->
                            async { fetch(name) }
                        }.awaitAll()
                    }
                }

                mutex.withLock {
                    ensureLoaded()
                    val now = System.currentTimeMillis()
                    val result = HashMap<String, String?>()
                    requested.forEach { name ->
                        val item = cache[GoBeyondFuzzy.normalize(name)] ?: return@forEach
                        if (now - item.at <= CACHE_MS) result[name] = item.url
                    }
                    result
                }
            }

        private suspend fun fetch(name: String) {
            permits.withPermit {
                val lookup =
                    withTimeoutOrNull(ARTIST_TIMEOUT_MS) { resolveOne(name) }
                        ?: return@withPermit
                if (!lookup.cacheable) return@withPermit
                remember(name, lookup.url)
            }
        }

        private suspend fun resolveOne(name: String): Lookup {
            val search =
                YouTube
                    .search(name, YouTube.SearchFilter.FILTER_ARTIST)
                    .getOrNull()
                    ?: return Lookup(cacheable = false, url = null)

            val candidate = exactMatch(name, search.items.filterIsInstance<ArtistItem>())
                ?: return Lookup(cacheable = true, url = null)

            val page =
                YouTube
                    .artist(candidate.id)
                    .onFailure { Timber.tag(TAG).w(it, "Artist banner lookup failed for %s", name) }
                    .getOrNull()

            val url = page?.artist?.thumbnail ?: candidate.thumbnail
            return when {
                url != null -> Lookup(cacheable = true, url = url)
                page != null -> Lookup(cacheable = true, url = null)
                else -> Lookup(cacheable = false, url = null)
            }
        }

        private fun exactMatch(
            name: String,
            candidates: List<ArtistItem>,
        ): ArtistItem? {
            if (candidates.isEmpty()) return null
            val key = GoBeyondFuzzy.normalize(name)
            if (key.isBlank()) return null

            candidates
                .firstOrNull { GoBeyondFuzzy.normalize(it.title) == key }
                ?.let { return it }

            val best =
                candidates
                    .map { it to GoBeyondFuzzy.ratio(name, it.title) }
                    .maxByOrNull { (_, score) -> score }
                    ?: return null
            return best.first.takeIf { best.second >= MATCH_THRESHOLD }
        }

        private suspend fun remember(
            name: String,
            url: String?,
        ) {
            val key = GoBeyondFuzzy.normalize(name)
            if (key.isBlank()) return
            mutex.withLock {
                ensureLoaded()
                val at = System.currentTimeMillis()
                cache[key] = Cached(url = url, at = at)
                append(key, url, at)
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
                    cache[key] =
                        Cached(
                            url = json.optString("url").takeIf { it.isNotBlank() },
                            at = json.optLong("at", 0L),
                        )
                }
            }
        }

        private fun append(
            artist: String,
            url: String?,
            at: Long,
        ) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(
                    JSONObject()
                        .put("artist", artist)
                        .put("url", url ?: JSONObject.NULL)
                        .put("at", at)
                        .toString() + "\n",
                )
            }
        }

        private companion object {
            const val TAG = "GoBeyondArtwork"
            const val FILE_NAME = "radiostar_artist_art.jsonl"
            const val MAX_CONCURRENT = 8
            const val ARTIST_TIMEOUT_MS = 5_000L
            const val MATCH_THRESHOLD = 0.82
            const val CACHE_MS = 7L * 86_400_000L
        }
    }
