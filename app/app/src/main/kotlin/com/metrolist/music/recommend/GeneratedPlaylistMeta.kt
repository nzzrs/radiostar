/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.time.LocalDateTime
import org.json.JSONObject

/**
 * Generation bookkeeping for a RadioStar playlist. It lives in DataStore so
 * the playlist row schema stays untouched; the entity keeps the user visible
 * created and last update dates.
 *
 * [version] counts how many times the repository actually rewrote the list.
 * Reading or opening a playlist never changes it.
 */
data class GeneratedPlaylistMeta(
    val version: Int = 0,
    val generatedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime? = null,
    val stamp: String? = null,
    val trackCount: Int = 0,
) {
    fun afterRewrite(
        now: LocalDateTime,
        stamp: String?,
        trackCount: Int,
    ): GeneratedPlaylistMeta =
        copy(
            version = version + 1,
            generatedAt = now,
            createdAt = createdAt ?: now,
            stamp = stamp,
            trackCount = trackCount,
        )

    fun toJson(): JSONObject =
        JSONObject()
            .put("version", version)
            .put("generatedAt", generatedAt?.toString() ?: JSONObject.NULL)
            .put("createdAt", createdAt?.toString() ?: JSONObject.NULL)
            .put("stamp", stamp ?: JSONObject.NULL)
            .put("trackCount", trackCount)

    companion object {
        fun fromJson(json: JSONObject?): GeneratedPlaylistMeta? {
            if (json == null) return null
            return runCatching {
                GeneratedPlaylistMeta(
                    version = json.optInt("version", 0),
                    generatedAt = json.readDate("generatedAt"),
                    createdAt = json.readDate("createdAt"),
                    stamp = json.readStringOrNull("stamp"),
                    trackCount = json.optInt("trackCount", 0),
                )
            }.getOrNull()
        }

        private fun JSONObject.readDate(key: String): LocalDateTime? =
            readStringOrNull(key)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

        private fun JSONObject.readStringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return getString(key).takeIf { it.isNotBlank() }
        }
    }
}

/** Encodes the per-playlist metadata as one JSON object keyed by playlist id. */
object GeneratedPlaylistMetaCodec {
    fun decode(raw: String?): Map<String, GeneratedPlaylistMeta> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            buildMap {
                root.keys().forEach { id ->
                    GeneratedPlaylistMeta.fromJson(root.optJSONObject(id))?.let { put(id, it) }
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun encode(metas: Map<String, GeneratedPlaylistMeta>): String {
        val root = JSONObject()
        metas.forEach { (id, meta) -> root.put(id, meta.toJson()) }
        return root.toString()
    }

    fun entry(
        raw: String?,
        id: String,
    ): GeneratedPlaylistMeta? = decode(raw)[id]
}
