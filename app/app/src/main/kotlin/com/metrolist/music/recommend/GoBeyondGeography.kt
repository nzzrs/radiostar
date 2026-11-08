/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * One country that can seed a Go Beyond journey. [name] is the English display
 * name, [adjective] the demonym used in search prompts (falling back to the name
 * for countries without a curated demonym) and [hue] a stable accent colour.
 */
data class GoBeyondCountry(
    val code: String,
    val name: String,
    val flag: String,
    val adjective: String,
    val hue: Int,
    val aliases: List<String> = emptyList(),
)

/**
 * All ISO 3166 countries with a flag, a stable colour and accent-insensitive
 * fuzzy lookup. Pure JVM so the catalog can be exercised by unit tests.
 */
object GoBeyondGeography {
    private const val REGIONAL_INDICATOR_A = 0x1F1E6
    private const val ASCII_A = 'A'.code

    private val ADJECTIVES =
        mapOf(
            "BR" to "Brazilian",
            "JP" to "Japanese",
            "NG" to "Nigerian",
            "KR" to "South Korean",
            "IN" to "Indian",
            "MX" to "Mexican",
            "JM" to "Jamaican",
            "IS" to "Icelandic",
            "ML" to "Malian",
            "TR" to "Turkish",
            "FR" to "French",
            "CO" to "Colombian",
            "SN" to "Senegalese",
            "CU" to "Cuban",
            "ET" to "Ethiopian",
            "US" to "American",
            "GB" to "British",
            "DE" to "German",
            "IT" to "Italian",
            "ES" to "Spanish",
            "PT" to "Portuguese",
            "RU" to "Russian",
            "CN" to "Chinese",
            "AU" to "Australian",
            "CA" to "Canadian",
            "IE" to "Irish",
            "SE" to "Swedish",
            "NO" to "Norwegian",
            "FI" to "Finnish",
            "PL" to "Polish",
            "GR" to "Greek",
            "EG" to "Egyptian",
            "MA" to "Moroccan",
            "ZA" to "South African",
            "AR" to "Argentinian",
            "CL" to "Chilean",
            "PE" to "Peruvian",
            "PH" to "Filipino",
            "ID" to "Indonesian",
            "TH" to "Thai",
            "VN" to "Vietnamese",
            "PK" to "Pakistani",
            "BD" to "Bangladeshi",
            "IR" to "Iranian",
            "IQ" to "Iraqi",
            "IL" to "Israeli",
            "SA" to "Saudi",
            "AE" to "Emirati",
            "KE" to "Kenyan",
            "GH" to "Ghanaian",
            "NZ" to "New Zealander",
            "NL" to "Dutch",
            "BE" to "Belgian",
            "CH" to "Swiss",
            "AT" to "Austrian",
            "CZ" to "Czech",
            "HU" to "Hungarian",
            "RO" to "Romanian",
            "UA" to "Ukrainian",
            "DK" to "Danish",
        )

    /**
     * Common English names, historical names and misspellings. Anything here is
     * treated as an alternative label next to the ISO display name.
     */
    private val ALIASES =
        mapOf(
            "US" to
                listOf(
                    "USA",
                    "U.S.",
                    "U.S.A.",
                    "United States of America",
                    "America",
                    "Estados Unidos",
                ),
            "GB" to
                listOf(
                    "UK",
                    "U.K.",
                    "Britain",
                    "Great Britain",
                    "England",
                    "Scotland",
                    "Wales",
                    "Northern Ireland",
                ),
            "NL" to listOf("Holland", "The Netherlands"),
            "CI" to
                listOf(
                    "Ivory Coast",
                    "Cote d'Ivoire",
                    "Côte d'Ivoire",
                    "Cote dIvoire",
                    "Cote d Ivoire",
                ),
            "CZ" to listOf("Czech Republic", "Czechia"),
            "TR" to listOf("Türkiye", "Turkiye", "Turkey"),
            "KR" to listOf("Korea", "South Korea", "Republic of Korea", "Korea, South"),
            "KP" to listOf("North Korea", "DPRK", "Korea, North"),
            "RU" to listOf("Russian Federation", "Rossiya"),
            "VN" to listOf("Viet Nam", "Vietman"),
            "BO" to listOf("Plurinational State of Bolivia"),
            "VE" to listOf("Bolivarian Republic of Venezuela"),
            "IR" to listOf("Persia", "Islamic Republic of Iran"),
            "SY" to listOf("Syrian Arab Republic"),
            "LA" to listOf("Lao People's Democratic Republic", "Lao"),
            "TZ" to listOf("United Republic of Tanzania"),
            "MD" to listOf("Moldova", "Republic of Moldova"),
            "MK" to listOf("Macedonia", "North Macedonia"),
            "SZ" to listOf("Swaziland", "Eswatini"),
            "CV" to listOf("Cape Verde", "Cabo Verde"),
            "TL" to listOf("East Timor", "Timor-Leste"),
            "MM" to listOf("Burma", "Myanmar"),
            "CD" to
                listOf(
                    "DRC",
                    "Congo-Kinshasa",
                    "Democratic Republic of the Congo",
                    "Zaire",
                ),
            "CG" to listOf("Congo-Brazzaville", "Republic of the Congo"),
            "AE" to listOf("UAE", "U.A.E.", "Emirates"),
            "SA" to listOf("KSA", "Kingdom of Saudi Arabia"),
            "CH" to listOf("Schweiz", "Suisse", "Svizzera", "Swizerland"),
            "DE" to listOf("Deutschland", "Germny", "Germay"),
            "ES" to listOf("España", "Espana"),
            "GR" to listOf("Hellas"),
            "JP" to listOf("Nippon", "Nihon"),
            "CN" to listOf("PRC", "Mainland China", "People's Republic of China"),
            "IE" to listOf("Eire", "Éire"),
            "NZ" to listOf("Aotearoa"),
            "ZA" to listOf("RSA"),
            "BR" to listOf("Brasil"),
            "MX" to listOf("México", "Mejico", "Mexcio"),
            "AR" to listOf("Argentine", "Argnetina"),
            "PE" to listOf("Perú"),
            "CL" to listOf("Chile"),
            "CO" to listOf("Columbia"),
            "PH" to listOf("Pilipinas", "Phillipines", "Philipines", "The Philippines"),
            "TH" to listOf("Siam", "Thialand"),
            "ET" to listOf("Abyssinia"),
            "IS" to listOf("Island", "Icland"),
            "NO" to listOf("Norge"),
            "SE" to listOf("Sverige"),
            "FI" to listOf("Suomi"),
            "DK" to listOf("Danmark"),
            "PL" to listOf("Polska"),
            "AT" to listOf("Österreich", "Osterreich"),
            "HU" to listOf("Magyarország", "Magyarorszag"),
            "RO" to listOf("Rumania", "Roumania"),
            "UA" to listOf("Ukraina"),
            "IT" to listOf("Italia"),
            "BS" to listOf("The Bahamas"),
            "GM" to listOf("The Gambia"),
            "PS" to listOf("Palestine", "West Bank", "Gaza"),
            "VA" to listOf("Vatican", "Holy See"),
            "HK" to listOf("Hong Kong", "Hongkong"),
            "MO" to listOf("Macau", "Macao"),
            "TW" to listOf("Taiwan", "Republic of China"),
            "BN" to listOf("Brunei Darussalam"),
            "KZ" to listOf("Kazakhstan"),
            "BY" to listOf("Belarus", "Byelorussia"),
            "NG" to listOf("Nigeira"),
            "JM" to listOf("Jamaicia"),
            "SN" to listOf("Senagal"),
            "MA" to listOf("Morroco"),
            "PK" to listOf("Pakstan"),
            "ID" to listOf("Indoneisa"),
            "AU" to listOf("Austraila", "Austalia", "Oz"),
            "CA" to listOf("Cananda"),
            "PT" to listOf("Portgual"),
            "PR" to listOf("Porto Rico", "Puerto Rico"),
        )

    private val SPECIAL_LETTERS =
        mapOf(
            'ß' to "ss",
            'æ' to "ae",
            'œ' to "oe",
            'ø' to "o",
            'ł' to "l",
            'đ' to "d",
            'ð' to "d",
            'þ' to "th",
            'ı' to "i",
            'ŋ' to "ng",
        )

    private val DIACRITICS = Regex("\\p{M}+")
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
    private val WHITESPACE = Regex("\\s+")

    val all: List<GoBeyondCountry> =
        Locale.getISOCountries()
            .asSequence()
            .mapNotNull { rawCode ->
                val code = rawCode.uppercase(Locale.ROOT)
                val name = Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ENGLISH).trim()
                if (name.isBlank()) return@mapNotNull null
                GoBeyondCountry(
                    code = code,
                    name = name,
                    flag = flagFor(code),
                    adjective = ADJECTIVES[code] ?: name,
                    hue = Math.floorMod(name.hashCode(), 360),
                    aliases = ALIASES[code].orEmpty(),
                )
            }
            .sortedWith(compareBy(Collator.getInstance(Locale.ENGLISH)) { it.name })
            .toList()

    private val byCode: Map<String, GoBeyondCountry> = all.associateBy { it.code }

    fun country(code: String): GoBeyondCountry? = byCode[code.trim().uppercase(Locale.ROOT)]

    /** Curated demonym for [code], or the English country name itself. */
    fun adjective(code: String): String? = country(code)?.adjective

    fun flag(code: String): String = flagFor(code)

    /**
     * Resolve one country by code, name, adjective or alias. Exact matches win
     * over prefixes, which win over fuzzy [search] hits.
     */
    fun byName(query: String): GoBeyondCountry? {
        val normalized = normalize(query)
        if (normalized.isEmpty()) return null
        val compact = compact(normalized)

        byCode[query.trim().uppercase(Locale.ROOT)]?.let { return it }

        all.firstOrNull {
            val name = normalize(it.name)
            name == normalized || compact(name) == compact
        }?.let { return it }

        all.firstOrNull { candidate ->
            searchableTexts(candidate).any {
                val text = normalize(it)
                text == normalized || compact(text) == compact
            }
        }?.let { return it }

        all.firstOrNull {
            val name = normalize(it.name)
            name.startsWith(normalized) || compact(name).startsWith(compact)
        }?.let { return it }

        all.firstOrNull { candidate ->
            searchableTexts(candidate).any {
                val text = normalize(it)
                text.startsWith(normalized) || compact(text).startsWith(compact)
            }
        }?.let { return it }

        return search(query, limit = 1).firstOrNull()
    }

    /**
     * Accent- and case-insensitive search over names, ISO codes, adjectives and
     * aliases. Prefix hits come first, then token/contains hits, then results
     * within a small edit distance. A blank query returns the head of [all].
     */
    fun search(query: String, limit: Int = 40): List<GoBeyondCountry> {
        if (limit <= 0) return emptyList()
        val normalized = normalize(query)
        if (normalized.isEmpty()) return all.take(limit)
        val compact = compact(normalized)

        return all.asSequence()
            .mapNotNull { candidate ->
                score(candidate, normalized, compact)?.let { candidate to it }
            }
            .sortedWith(
                compareBy<Pair<GoBeyondCountry, MatchScore>> { it.second.tier }
                    .thenBy { it.second.weight }
                    .thenBy { it.second.distance }
                    .thenBy { it.first.name },
            )
            .map { it.first }
            .take(limit)
            .toList()
    }

    private data class MatchScore(
        val tier: Int,
        val weight: Int,
        val distance: Int,
    )

    private fun score(country: GoBeyondCountry, query: String, compactQuery: String): MatchScore? {
        val candidates =
            buildList {
                add(country.name to 0)
                add(country.code to 1)
                add(country.adjective to 2)
                country.aliases.forEach { add(it to 3) }
            }

        return candidates
            .mapNotNull { (text, weight) ->
                scoreText(text, query, compactQuery)?.copy(weight = weight)
            }
            .minWithOrNull(compareBy({ it.tier }, { it.weight }, { it.distance }))
    }

    private fun scoreText(text: String, query: String, compactQuery: String): MatchScore? {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return null
        val compact = compact(normalized)

        if (normalized == query || compact == compactQuery) return MatchScore(TIER_EXACT, 0, 0)
        if (normalized.startsWith(query) || compact.startsWith(compactQuery)) {
            return MatchScore(TIER_PREFIX, 0, 0)
        }

        val tokens = normalized.split(' ')
        val queryTokens = query.split(' ')
        if (queryTokens.all { token -> tokens.any { it.startsWith(token) } }) {
            return MatchScore(TIER_TOKEN, 0, 0)
        }
        if (compact.contains(compactQuery)) return MatchScore(TIER_TOKEN, 0, 0)

        val maxDistance = maxDistance(compactQuery.length)
        if (maxDistance > 0) {
            val distance = distance(compactQuery, compact)
            if (distance <= maxDistance) return MatchScore(TIER_FUZZY, 0, distance)
            if (queryTokens.size == 1) {
                val tokenDistance = tokens.minOfOrNull { distance(compactQuery, it) } ?: Int.MAX_VALUE
                if (tokenDistance <= maxDistance) return MatchScore(TIER_FUZZY, 0, tokenDistance)
            }
        }
        return null
    }

    private fun searchableTexts(country: GoBeyondCountry): List<String> =
        buildList {
            add(country.name)
            add(country.code)
            add(country.adjective)
            addAll(country.aliases)
        }

    private fun flagFor(code: String): String {
        val upper = code.trim().uppercase(Locale.ROOT)
        if (upper.length != 2) return ""
        val first = upper[0]
        val second = upper[1]
        if (first !in 'A'..'Z' || second !in 'A'..'Z') return ""
        return String(Character.toChars(REGIONAL_INDICATOR_A + first.code - ASCII_A)) +
            String(Character.toChars(REGIONAL_INDICATOR_A + second.code - ASCII_A))
    }

    private fun normalize(value: String): String {
        if (value.isBlank()) return ""
        val expanded =
            buildString(value.length) {
                for (character in value.lowercase(Locale.ROOT)) {
                    append(SPECIAL_LETTERS[character] ?: character)
                }
            }
        return Normalizer.normalize(expanded, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .replace(NON_ALPHANUMERIC, " ")
            .trim()
            .replace(WHITESPACE, " ")
    }

    private fun compact(value: String): String = value.replace(" ", "")

    private fun maxDistance(length: Int): Int =
        when {
            length <= 3 -> 0
            length <= 6 -> 1
            length <= 10 -> 2
            else -> 3
        }

    private fun distance(from: String, to: String): Int {
        if (from == to) return 0
        if (from.isEmpty()) return to.length
        if (to.isEmpty()) return from.length

        var previous = IntArray(to.length + 1) { it }
        var current = IntArray(to.length + 1)

        for (i in 1..from.length) {
            current[0] = i
            for (j in 1..to.length) {
                val substitution = if (from[i - 1] == to[j - 1]) 0 else 1
                current[j] =
                    minOf(
                        previous[j] + 1,
                        current[j - 1] + 1,
                        previous[j - 1] + substitution,
                    )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[to.length]
    }

    private const val TIER_EXACT = 0
    private const val TIER_PREFIX = 1
    private const val TIER_TOKEN = 2
    private const val TIER_FUZZY = 3
}
