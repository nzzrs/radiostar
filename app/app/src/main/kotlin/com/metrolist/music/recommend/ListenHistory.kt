/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.util.Locale

/**
 * ListenBrainz history in the CSV shape the export tools produce:
 * `Title,Artist,Album,YouTube Video ID`.
 *
 * The app only needs normalised `title|primary artist` keys: they match a
 * candidate without requiring its YouTube id, which the export often lacks.
 */
object ListenHistory {
    data class Entry(
        val title: String,
        val artist: String,
        val album: String? = null,
        val videoId: String? = null,
    )

    fun parseCsv(text: String): List<Entry> {
        val rows = parseRows(text.removePrefix("\uFEFF"))
        if (rows.isEmpty()) return emptyList()

        val header = rows.first()
        val hasHeader =
            header.size >= 2 &&
                header[0].trim().equals("title", ignoreCase = true) &&
                header[1].trim().equals("artist", ignoreCase = true)
        val body = if (hasHeader) rows.drop(1) else rows

        return body.mapNotNull { row ->
            val title = row.getOrNull(0)?.trim().orEmpty()
            val artist = row.getOrNull(1)?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            Entry(
                title = title,
                artist = artist,
                album = row.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() },
                videoId = row.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }

    fun historyKeys(entries: List<Entry>): Set<String> =
        entries.mapTo(LinkedHashSet()) { key(it.title, it.artist) }

    fun keyFor(candidate: Candidate): String =
        key(candidate.title, candidate.artists.firstOrNull().orEmpty())

    fun key(title: String, artist: String): String =
        "${normalise(title)}|${normalise(primaryArtist(artist))}"

    /** Keep only well-formed stored keys; rejects empty or truncated rows. */
    fun fromStored(values: Set<String>): Set<String> =
        values.mapNotNullTo(LinkedHashSet()) { raw ->
            val value = raw.trim()
            if (value.isEmpty() || !value.contains('|')) null else value
        }

    fun primaryArtist(raw: String): String =
        raw.split(ARTIST_SPLIT).firstOrNull()?.trim().orEmpty()

    private fun normalise(value: String): String =
        value.lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("\\s+")
    private val ARTIST_SPLIT = Regex("(?i)\\s+(?:feat\\.?|ft\\.?|featuring|with)\\s+|[,&;]")

    /**
     * Minimal RFC 4180 reader: quoted fields, escaped quotes, CRLF and LF.
     * A malformed trailing quote is treated as plain text instead of throwing.
     */
    private fun parseRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
        }

        fun endRow() {
            endField()
            if (row.any { it.isNotEmpty() }) rows.add(row)
            row = ArrayList()
        }

        while (index < text.length) {
            val char = text[index]
            when {
                quoted -> {
                    if (char == '"') {
                        if (index + 1 < text.length && text[index + 1] == '"') {
                            field.append('"')
                            index += 1
                        } else {
                            quoted = false
                        }
                    } else {
                        field.append(char)
                    }
                }
                char == '"' -> quoted = true
                char == ',' -> endField()
                char == '\n' -> endRow()
                char == '\r' -> Unit
                else -> field.append(char)
            }
            index += 1
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
