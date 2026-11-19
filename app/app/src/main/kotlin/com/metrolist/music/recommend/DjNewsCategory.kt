/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.util.Locale

/**
 * Public Google News topics the DJ may mention. Topic ids mirror the
 * identifiers used by the Google News RSS section URLs.
 */
enum class DjNewsCategory(val topicId: String) {
    WORLD("WORLD"),
    NATION("NATION"),
    BUSINESS("BUSINESS"),
    TECHNOLOGY("TECHNOLOGY"),
    SCIENCE("SCIENCE"),
    HEALTH("HEALTH"),
    SPORTS("SPORTS"),
    ENTERTAINMENT("ENTERTAINMENT"),
    ;

    companion object {
        /** World and national topics keep general global and local news on by default. */
        val Default: Set<DjNewsCategory> = setOf(WORLD, NATION)

        fun fromStorage(values: Collection<String>): Set<DjNewsCategory> {
            val selected =
                entries
                    .filter { category -> values.any { it.equals(category.name, ignoreCase = true) } }
                    .toSet()
            return selected.ifEmpty { Default }
        }

        fun toStorage(categories: Set<DjNewsCategory>): Set<String> =
            normalize(categories).mapTo(LinkedHashSet()) { it.name }

        fun normalize(categories: Set<DjNewsCategory>): Set<DjNewsCategory> =
            categories.ifEmpty { Default }
    }
}

/** One Google News edition: language for `hl`, country for `gl` and `ceid`. */
data class DjNewsEdition(
    val language: String,
    val country: String,
    val hl: String = language,
) {
    val ceid: String get() = "$country:$language"
}

/** Builds the RSS URLs for the global edition and the listener edition. */
object DjNewsUrls {
    const val BASE_URL = "https://news.google.com/rss"

    val GlobalEdition = DjNewsEdition(language = "en", country = "US", hl = "en-US")

    fun fromLocale(locale: Locale): DjNewsEdition {
        val country = locale.country.uppercase(Locale.ROOT)
        val language = locale.language.lowercase(Locale.ROOT)
        if (country.length != 2 || language.length !in 2..3) return GlobalEdition
        return DjNewsEdition(language = language, country = country, hl = language)
    }

    fun home(edition: DjNewsEdition): String = "$BASE_URL?${query(edition)}"

    fun topic(
        category: DjNewsCategory,
        edition: DjNewsEdition,
    ): String = "$BASE_URL/headlines/section/topic/${category.topicId}?${query(edition)}"

    private fun query(edition: DjNewsEdition): String =
        "hl=${edition.hl}&gl=${edition.country}&ceid=${edition.ceid}"
}
