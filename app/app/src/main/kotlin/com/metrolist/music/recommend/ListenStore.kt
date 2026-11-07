/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.content.Context
import java.io.File
import java.util.Locale
import org.json.JSONObject

data class ImportedListen(
    val service: String,
    val title: String,
    val artist: String,
    val album: String?,
    val listenedAt: Long,
    val videoId: String? = null,
)

/**
 * Append-only local store for external listens.
 *
 * JSONL survives crashes better than a rewritten JSON array: a truncated last
 * line is skipped, earlier lines stay readable. Duplicates are rejected by a
 * normalised fingerprint, not by page position.
 */
class ListenStore(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)

    fun load(): List<ImportedListen> {
        if (!file.exists()) return emptyList()
        val seen = HashSet<String>()
        val result = ArrayList<ImportedListen>()
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            runCatching {
                val json = JSONObject(line)
                val listen =
                    ImportedListen(
                        service = json.getString("service"),
                        title = json.getString("title"),
                        artist = json.getString("artist"),
                        album = json.optString("album").takeIf { it.isNotBlank() },
                        listenedAt = json.getLong("ts"),
                        videoId = json.optString("videoId").takeIf { it.isNotBlank() },
                    )
                if (valid(listen) && seen.add(fingerprint(listen))) result.add(listen)
            }
        }
        return result
    }

    /** Fast line count; does not parse the JSON records. */
    fun count(): Int {
        if (!file.exists()) return 0
        return runCatching {
            file.useLines { lines -> lines.count { it.isNotBlank() } }
        }.getOrDefault(0)
    }

    fun append(records: List<ImportedListen>): Int {
        if (records.isEmpty()) return 0
        val existing = load().mapTo(HashSet()) { fingerprint(it) }
        val fresh = records.filter { valid(it) && existing.add(fingerprint(it)) }
        if (fresh.isEmpty()) return 0
        file.parentFile?.mkdirs()
        file.appendText(
            fresh.joinToString(separator = "\n", postfix = "\n") { it.toJson().toString() },
            Charsets.UTF_8,
        )
        return fresh.size
    }

    private fun valid(listen: ImportedListen): Boolean =
        listen.title.isNotBlank() && listen.artist.isNotBlank() && listen.listenedAt > 0L

    private fun fingerprint(listen: ImportedListen): String =
        listOf(
            listen.service.lowercase(Locale.ROOT),
            listen.artist.trim().lowercase(Locale.ROOT),
            listen.title.trim().lowercase(Locale.ROOT),
            listen.listenedAt.toString(),
        ).joinToString("|")

    private fun ImportedListen.toJson(): JSONObject =
        JSONObject().apply {
            put("service", service)
            put("title", title)
            put("artist", artist)
            album?.let { put("album", it) }
            put("ts", listenedAt)
            videoId?.let { put("videoId", it) }
        }

    private companion object {
        const val FILE_NAME = "radiostar_listens.jsonl"
    }
}
