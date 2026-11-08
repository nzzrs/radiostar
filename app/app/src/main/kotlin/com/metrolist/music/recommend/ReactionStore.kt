/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A like (1), dislike (-1) or cleared (0) track reaction. */
data class StoredReaction(
    val key: String,
    val videoId: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSeconds: Int?,
    val rating: Int,
    val updatedAtMs: Long,
    val localSeq: Long,
) {
    val deleted: Boolean get() = rating == 0
}

/**
 * Append-only reaction log, keyed by YouTube video id (or title/artist for
 * tracks that only exist on a scrobbler). Room already owns `song.liked`; this
 * file adds dislikes and the local sequence needed for durable sync retries
 * without migrating the database.
 */
class ReactionStore(
    private val context: Context,
) {
    private val file = File(context.filesDir, FILE_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loaded = false
    private var current: LinkedHashMap<String, StoredReaction> = LinkedHashMap()
    private var maxLocalSeq = 0L

    @Synchronized
    fun load(): List<StoredReaction> {
        ensureLoaded()
        return current.values.toList()
    }

    @Synchronized
    fun get(key: String): StoredReaction? {
        ensureLoaded()
        return current[key]
    }

    @Synchronized
    fun pending(sinceLocalSeq: Long): List<StoredReaction> {
        ensureLoaded()
        return current.values.filter { it.localSeq > sinceLocalSeq }
    }

    @Synchronized
    fun recordLocal(
        videoId: String?,
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
        rating: Int,
    ): StoredReaction {
        ensureLoaded()
        val key = SyncConfigCodec.reactionKey(videoId, title, artist)
        val reaction =
            StoredReaction(
                key = key,
                videoId = videoId?.takeIf { it.isNotBlank() },
                title = title,
                artist = artist,
                album = album,
                durationSeconds = durationSeconds,
                rating = rating.coerceIn(-1, 1),
                updatedAtMs = System.currentTimeMillis(),
                localSeq = ++maxLocalSeq,
            )
        append(reaction)
        current[key] = reaction
        if (rating != 0) {
            val type =
                if (rating > 0) TelemetryEventType.LIKE else TelemetryEventType.DISLIKE
            scope.launch {
                TelemetryStore.record(
                    context,
                    TelemetryStore.Event(
                        type = type,
                        songId = videoId,
                        title = title,
                        artist = artist,
                        playlist = null,
                        seed = null,
                        positionMs = null,
                        durationMs = durationSeconds?.toLong()?.times(1000),
                        completed = false,
                        at = reaction.updatedAtMs,
                        album = album,
                        session = TelemetrySession.id,
                    ),
                )
            }
        }
        return reaction
    }

    @Synchronized
    fun applyRemote(
        videoId: String?,
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
        rating: Int,
        updatedAtMs: Long,
        key: String? = null,
    ): StoredReaction? {
        ensureLoaded()
        val resolvedKey = key ?: SyncConfigCodec.reactionKey(videoId, title, artist)
        val known = current[resolvedKey]
        if (known != null && known.updatedAtMs > updatedAtMs) return known
        val reaction =
            StoredReaction(
                key = resolvedKey,
                videoId = videoId?.takeIf { it.isNotBlank() } ?: known?.videoId,
                title = title.ifBlank { known?.title.orEmpty() },
                artist = artist.ifBlank { known?.artist.orEmpty() },
                album = album ?: known?.album,
                durationSeconds = durationSeconds ?: known?.durationSeconds,
                rating = rating.coerceIn(-1, 1),
                updatedAtMs = updatedAtMs,
                localSeq = 0L,
            )
        append(reaction)
        current[resolvedKey] = reaction
        return reaction
    }

    @Synchronized
    fun clear() {
        file.delete()
        current = LinkedHashMap()
        maxLocalSeq = 0L
        loaded = true
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        runCatching {
            file.forEachLine { line ->
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                val reaction = json.toReaction() ?: return@forEachLine
                current[reaction.key] = reaction
                if (reaction.localSeq > maxLocalSeq) maxLocalSeq = reaction.localSeq
            }
        }
    }

    private fun append(reaction: StoredReaction) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(reaction.toJson().toString() + "\n")
        }
    }

    private fun StoredReaction.toJson(): JSONObject =
        JSONObject()
            .put("key", key)
            .put("videoId", videoId ?: JSONObject.NULL)
            .put("title", title)
            .put("artist", artist)
            .put("album", album ?: JSONObject.NULL)
            .put("duration", durationSeconds ?: JSONObject.NULL)
            .put("rating", rating)
            .put("updatedAt", updatedAtMs)
            .put("localSeq", localSeq)

    private fun JSONObject.toReaction(): StoredReaction? {
        val key = optString("key").takeIf { it.isNotBlank() } ?: return null
        return StoredReaction(
            key = key,
            videoId = if (isNull("videoId")) null else optString("videoId").takeIf { it.isNotBlank() },
            title = optString("title"),
            artist = optString("artist"),
            album = if (isNull("album")) null else optString("album").takeIf { it.isNotBlank() },
            durationSeconds = if (isNull("duration")) null else optInt("duration"),
            rating = optInt("rating", 0).coerceIn(-1, 1),
            updatedAtMs = optLong("updatedAt", 0L),
            localSeq = optLong("localSeq", 0L),
        )
    }

    private companion object {
        const val FILE_NAME = "radiostar_reactions.jsonl"
    }
}
