/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.util.Locale

/**
 * Turns anything the user typed into a canonical facet. Typos, accents,
 * aliases and reordered words are accepted; nonsense that cannot be resolved
 * is either kept as a sanitized custom value or dropped.
 */
object GoBeyondResolver {
    private const val GENRE_THRESHOLD = 0.70
    private const val COLOR_THRESHOLD = 0.70
    private const val MOOD_THRESHOLD = 0.68

    fun resolve(selection: GoBeyondSelection): GoBeyondSelection {
        val freeText = GoBeyondFuzzy.sanitizeUserText(selection.freeText.orEmpty())
        val artist = GoBeyondFuzzy.sanitizeUserText(selection.artist.orEmpty())
        return selection.copy(
            country = resolveCountry(selection.country),
            genre = resolveGenre(selection.genre),
            artist = artist.takeIf { it.isNotBlank() },
            color = resolveColor(selection.color),
            mood = resolveMood(selection.mood),
            freeText = freeText.takeIf { it.isNotBlank() },
        )
    }

    fun resolveCountry(value: String?): String? {
        val input = GoBeyondFuzzy.sanitizeUserText(value.orEmpty())
        if (input.isBlank()) return null
        return GoBeyondGeography.byName(input)?.name
    }

    fun resolveGenre(value: String?): String? {
        val input = GoBeyondFuzzy.sanitizeUserText(value.orEmpty())
        if (input.isBlank()) return null
        val known = resolveCandidate(input, genreOptions(), GENRE_THRESHOLD)
        return known ?: input
    }

    fun resolveColor(value: String?): String? {
        val input = GoBeyondFuzzy.sanitizeUserText(value.orEmpty())
        if (input.isBlank()) return null
        val known = resolveCandidate(input, colorOptions(), COLOR_THRESHOLD)
        return known
    }

    fun resolveMood(value: String?): String? {
        val input = GoBeyondFuzzy.sanitizeUserText(value.orEmpty())
        if (input.isBlank()) return null
        val known = resolveCandidate(input, moodOptions(), MOOD_THRESHOLD)
        return known
    }

    fun literalColorTerms(value: String?): Set<String> {
        val entry = GoBeyondColors.byName(resolveColor(value)) ?: return emptySet()
        return (listOf(entry.name) + entry.aliases).map { it.lowercase(Locale.ROOT) }.toSet()
    }

    fun colorPromptTerms(value: String?): Set<String> {
        val entry = GoBeyondColors.byName(resolveColor(value)) ?: return emptySet()
        return entry.terms
            .flatMap { it.split(' ', '-', '/') }
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.length >= 3 }
            .toSet()
    }

    private fun resolveCandidate(
        input: String,
        candidates: List<Pair<String, String>>,
        threshold: Double,
    ): String? {
        val values = candidates.map { it.second }
        val match = GoBeyondFuzzy.bestMatch(input, values, threshold) ?: return null
        val normalizedMatch = GoBeyondFuzzy.normalize(match.value)
        return candidates.firstOrNull { (_, candidate) ->
            GoBeyondFuzzy.normalize(candidate) == normalizedMatch
        }?.first
    }

    private fun genreOptions(): List<Pair<String, String>> =
        GoBeyondCatalog.genres.flatMap { entry ->
            (listOf(entry.title) + entry.aliases).map { entry.title to it }
        }

    private fun colorOptions(): List<Pair<String, String>> =
        GoBeyondColors.all.flatMap { entry ->
            (listOf(entry.name) + entry.aliases).map { entry.name to it }
        }

    private fun moodOptions(): List<Pair<String, String>> =
        GoBeyondCatalog.moods.flatMap { entry ->
            (listOf(entry.title) + entry.aliases).map { entry.title to it }
        }
}
