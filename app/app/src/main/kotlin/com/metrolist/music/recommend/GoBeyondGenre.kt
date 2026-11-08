/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

/**
 * Strict genre membership for a generated journey.
 *
 * YouTube search returns plenty of tracks that merely mention a genre, so the
 * repository asks MusicBrainz for an artist's tags and keeps only artists whose
 * tags actually contain the requested genre. Artists without any tag data stay
 * unknown and may fill a short playlist, but an artist tagged as another genre
 * is dropped.
 */
object GoBeyondGenre {
    data class FilterResult<T>(
        val accepted: List<T>,
        val rejected: Int,
        val verified: Int,
        val unknown: Int,
    )

    fun tagsFor(genre: String): List<String> {
        val raw = genre.trim()
        if (raw.isBlank()) return emptyList()
        val canonical = GoBeyondResolver.resolveGenre(raw) ?: raw
        val entry =
            GoBeyondCatalog.genres.firstOrNull {
                it.title.equals(canonical, ignoreCase = true)
            }
        val parent =
            if (entry == null) {
                canonical
                    .split(' ')
                    .lastOrNull()
                    ?.let { token ->
                        GoBeyondCatalog.genres.firstOrNull {
                            it.title.equals(token, ignoreCase = true)
                        }
                    }
            } else {
                null
            }
        val values =
            listOfNotNull(entry?.title, parent?.title) +
                entry?.aliases.orEmpty() +
                parent?.aliases.orEmpty() +
                canonical
        return values
            .map { GoBeyondFuzzy.normalize(it) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun matches(
        genre: String,
        tags: Collection<String>,
    ): Boolean {
        val wanted = tagsFor(genre)
        if (wanted.isEmpty() || tags.isEmpty()) return false
        val actual = tags.map { GoBeyondFuzzy.normalize(it) }.filter { it.isNotBlank() }
        return wanted.any { term ->
            actual.any { tag -> tag == term || containsPhrase(tag, term) }
        }
    }

    /** Unknown tags (null or empty) cannot exclude a track. */
    fun accepts(
        genre: String,
        tags: Collection<String>?,
    ): Boolean = tags.isNullOrEmpty() || matches(genre, tags)

    /**
     * Keeps every verified match, drops artists known to belong to a different
     * genre and adds unknown artists only until [fillTo] tracks exist.
     */
    fun <T> filter(
        genre: String,
        items: List<T>,
        tagsOf: (T) -> Collection<String>?,
        fillTo: Int = 8,
    ): FilterResult<T> {
        if (items.isEmpty()) return FilterResult(emptyList(), 0, 0, 0)
        val verified = ArrayList<T>(items.size)
        val unknown = ArrayList<T>(items.size)
        var rejected = 0
        items.forEach { item ->
            val tags = tagsOf(item)
            when {
                tags.isNullOrEmpty() -> unknown += item
                matches(genre, tags) -> verified += item
                else -> rejected++
            }
        }
        val filler = unknown.take((fillTo - verified.size).coerceAtLeast(0))
        return FilterResult(
            accepted = verified + filler,
            rejected = rejected,
            verified = verified.size,
            unknown = unknown.size,
        )
    }

    private fun containsPhrase(
        value: String,
        phrase: String,
    ): Boolean {
        val valueTokens = value.split(' ').filter { it.isNotBlank() }
        val phraseTokens = phrase.split(' ').filter { it.isNotBlank() }
        if (phraseTokens.isEmpty() || phraseTokens.size > valueTokens.size) return false
        return (0..valueTokens.size - phraseTokens.size).any { start ->
            phraseTokens.indices.all { offset ->
                valueTokens[start + offset] == phraseTokens[offset]
            }
        }
    }
}
