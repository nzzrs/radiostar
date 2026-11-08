/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.text.Normalizer
import java.util.Locale

/**
 * One fuzzy match for a Go Beyond value.
 *
 * [value] is the original candidate string, [score] is in the 0..1 range.
 */
data class GoBeyondFuzzyMatch(
    val value: String,
    val score: Double,
)

/**
 * Typo-tolerant handling for RadioStar Go Beyond facets and free text.
 *
 * Pure JVM: no Android dependencies. Matching combines normalized character
 * distance with order-independent token similarity, so both spelling mistakes
 * and reordered words ("pop city" against "City Pop") resolve gracefully.
 */
object GoBeyondFuzzy {
    private val whitespace = Regex("\\s+")

    private val keyboardRuns =
        listOf(
            "qwer", "wert", "erty", "rtyu", "tyui", "yuio", "uiop",
            "asdf", "sdfg", "dfgh", "fghj", "ghjk", "hjkl",
            "zxcv", "xcvb", "cvbn", "vbnm",
            "1234", "2345", "3456", "4567", "5678", "6789", "7890",
        )

    private const val VOWELS = "aeiouy"

    private const val REORDERED_TOKEN_WEIGHT = 0.97

    /**
     * Lowercase, strip diacritics, expand "&" to "and", and reduce all
     * punctuation and whitespace to single spaces. Letters and digits survive.
     */
    fun normalize(text: String): String {
        if (text.isBlank()) return ""

        val expanded = text.lowercase(Locale.ROOT).replace("&", " and ")
        val decomposed = Normalizer.normalize(expanded, Normalizer.Form.NFD)
        val builder = StringBuilder(decomposed.length)

        var index = 0
        while (index < decomposed.length) {
            val codePoint = decomposed.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            index += charCount

            if (isCombiningMark(codePoint)) continue

            val folded = foldSpecialLetter(codePoint)
            if (folded != null) {
                builder.append(folded)
            } else if (Character.isLetterOrDigit(codePoint)) {
                builder.appendCodePoint(codePoint)
            } else {
                builder.append(' ')
            }
        }

        return builder.toString().trim().replace(whitespace, " ")
    }

    /** Normalized words of [text], in first-seen order. */
    fun tokens(text: String): Set<String> {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return emptySet()

        val result = linkedSetOf<String>()
        for (token in normalized.split(' ')) {
            if (token.isNotEmpty()) result.add(token)
        }
        return result
    }

    /**
     * Optimal-string-alignment Damerau-Levenshtein distance. Counts insertion,
     * deletion, substitution, and adjacent transposition as one edit.
     */
    fun distance(
        a: String,
        b: String,
    ): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val distances = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) distances[i][0] = i
        for (j in 0..b.length) distances[0][j] = j

        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val substitutionCost = if (a[i - 1] == b[j - 1]) 0 else 1
                var value =
                    minOf(
                        distances[i][j - 1] + 1,
                        distances[i - 1][j] + 1,
                        distances[i - 1][j - 1] + substitutionCost,
                    )
                if (i > 1 &&
                    j > 1 &&
                    a[i - 1] == b[j - 2] &&
                    a[i - 2] == b[j - 1]
                ) {
                    value = minOf(value, distances[i - 2][j - 2] + 1)
                }
                distances[i][j] = value
            }
        }

        return distances[a.length][b.length]
    }

    /**
     * Fuzzy similarity in the 0..1 range. Exact normalized matches score 1.
     * Spelling similarity, compact spelling similarity, and token similarity
     * are combined, so word order changes keep a high score.
     */
    fun ratio(
        a: String,
        b: String,
    ): Double {
        val normalizedA = normalize(a)
        val normalizedB = normalize(b)
        if (normalizedA.isEmpty() && normalizedB.isEmpty()) return 1.0
        if (normalizedA.isEmpty() || normalizedB.isEmpty()) return 0.0
        if (normalizedA == normalizedB) return 1.0

        val characterScore = similarity(normalizedA, normalizedB)
        val compactScore =
            similarity(
                normalizedA.replace(" ", ""),
                normalizedB.replace(" ", ""),
            )
        val tokenScore = tokenSimilarity(normalizedA, normalizedB) * REORDERED_TOKEN_WEIGHT

        return maxOf(characterScore, compactScore, tokenScore).coerceIn(0.0, 1.0)
    }

    /** Best candidate at or above [threshold], or null when nothing is close. */
    fun bestMatch(
        query: String,
        candidates: List<String>,
        threshold: Double = 0.62,
    ): GoBeyondFuzzyMatch? {
        if (normalize(query).isEmpty()) return null

        var best: GoBeyondFuzzyMatch? = null
        for (candidate in candidates) {
            if (candidate.isBlank()) continue
            val score = ratio(query, candidate)
            if (score < threshold) continue
            best = chooseBetter(best, GoBeyondFuzzyMatch(candidate, score))
        }
        return best
    }

    /** All candidates ranked by score descending, with a stable tie-break. */
    fun rank(
        query: String,
        candidates: List<String>,
    ): List<GoBeyondFuzzyMatch> {
        if (normalize(query).isEmpty()) return emptyList()

        return candidates
            .asSequence()
            .filter { it.isNotBlank() }
            .map { GoBeyondFuzzyMatch(it, ratio(query, it)) }
            .sortedWith(
                compareByDescending<GoBeyondFuzzyMatch> { it.score }
                    .thenBy { normalize(it.value) }
                    .thenBy { it.value },
            ).toList()
    }

    /**
     * True when [term] appears as a normalized token or token prefix sequence
     * inside [text]. "shoe" matches "Shoegaze"; "city pop" matches "City Pop".
     */
    fun containsTerm(
        text: String,
        term: String,
    ): Boolean {
        val textTokens = tokens(text).toList()
        val termTokens = tokens(term).toList()
        if (textTokens.isEmpty() || termTokens.isEmpty()) return false
        if (termTokens.size > textTokens.size) {
            return compactContains(text, term)
        }

        for (start in 0..textTokens.size - termTokens.size) {
            var matches = true
            for (offset in termTokens.indices) {
                if (!textTokens[start + offset].startsWith(termTokens[offset])) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }

        return compactContains(text, term)
    }

    /**
     * Cleans free text for storage and prompting: removes control characters
     * and dangerous punctuation, collapses whitespace, caps the code-point
     * length, and rejects blank or keyboard-mash-only input with "".
     */
    fun sanitizeUserText(
        text: String,
        maxLength: Int = 64,
    ): String {
        if (maxLength <= 0 || text.isBlank()) return ""

        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)

            when {
                Character.isISOControl(codePoint) -> builder.append(' ')
                isWhitespace(codePoint) -> builder.append(' ')
                codePoint == '&'.code -> builder.append(" and ")
                codePoint == 0x2018 || codePoint == 0x2019 -> builder.append('\'')
                isSafePunctuation(codePoint) || Character.isLetterOrDigit(codePoint) -> {
                    builder.appendCodePoint(codePoint)
                }
                else -> builder.append(' ')
            }
        }

        val collapsed = builder.toString().trim().replace(whitespace, " ")
        if (collapsed.isEmpty()) return ""

        val capped = capCodePoints(collapsed, maxLength).trim()
        if (capped.isEmpty()) return ""

        val words = capped.split(' ').filter { word -> word.any { it.isLetterOrDigit() } }
        if (words.isEmpty()) return ""
        if (words.all(::isGibberishToken)) return ""

        return capped
    }

    private fun chooseBetter(
        current: GoBeyondFuzzyMatch?,
        candidate: GoBeyondFuzzyMatch,
    ): GoBeyondFuzzyMatch {
        if (current == null) return candidate
        if (candidate.score > current.score) return candidate
        if (candidate.score < current.score) return current

        val candidateKey = normalize(candidate.value)
        val currentKey = normalize(current.value)
        return when {
            candidateKey < currentKey -> candidate
            candidateKey > currentKey -> current
            candidate.value <= current.value -> candidate
            else -> current
        }
    }

    private fun similarity(
        a: String,
        b: String,
    ): Double {
        if (a == b) return 1.0
        val longest = maxOf(a.length, b.length)
        if (longest == 0) return 1.0
        return (1.0 - distance(a, b).toDouble() / longest).coerceIn(0.0, 1.0)
    }

    private fun tokenSimilarity(
        normalizedA: String,
        normalizedB: String,
    ): Double {
        val tokensA = normalizedA.split(' ').filter(String::isNotEmpty)
        val tokensB = normalizedB.split(' ').filter(String::isNotEmpty)
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0

        val setA = tokensA.toSet()
        val setB = tokensB.toSet()
        val shared = setA.count { it in setB }
        val dice = 2.0 * shared / (setA.size + setB.size)
        val jaccard = shared.toDouble() / (setA.size + setB.size - shared)

        val coverageA = tokenCoverage(tokensA, tokensB)
        val coverageB = tokenCoverage(tokensB, tokensA)
        val coverage = (coverageA + coverageB) / 2.0

        return maxOf(dice, jaccard, coverage).coerceIn(0.0, 1.0)
    }

    /**
     * Mean of each source token's best match among the target tokens and the
     * target's adjacent/full concatenations. The concatenations catch merged
     * and split spellings such as "hihop" against "hip hop".
     */
    private fun tokenCoverage(
        source: List<String>,
        target: List<String>,
    ): Double {
        if (source.isEmpty() || target.isEmpty()) return 0.0

        val targets = ArrayList<String>(target.size * 2 + 1)
        targets.addAll(target)
        for (index in 0 until target.lastIndex) {
            targets.add(target[index] + target[index + 1])
        }
        if (target.size > 2) targets.add(target.joinToString(""))

        var total = 0.0
        for (token in source) {
            var best = 0.0
            for (candidate in targets) {
                best = maxOf(best, similarity(token, candidate))
            }
            total += best
        }
        return total / source.size
    }

    /**
     * Matches a term against whole adjacent token concatenations, so merged or
     * split spellings ("shoegaze" against "shoe gaze") still resolve without
     * turning arbitrary substrings into matches.
     */
    private fun compactContains(
        text: String,
        term: String,
    ): Boolean {
        val textTokens = normalize(text).split(' ').filter(String::isNotEmpty)
        val compactTerm = normalize(term).replace(" ", "")
        if (textTokens.isEmpty() || compactTerm.isEmpty()) return false

        for (start in textTokens.indices) {
            val joined = StringBuilder()
            for (end in start until textTokens.size) {
                joined.append(textTokens[end])
                val candidate = joined.toString()
                if (candidate == compactTerm) return true
                if (candidate.length >= compactTerm.length) break
            }
        }
        return false
    }

    private fun isCombiningMark(codePoint: Int): Boolean =
        when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }

    private fun foldSpecialLetter(codePoint: Int): String? =
        when (codePoint) {
            'ø'.code -> "o"
            'æ'.code -> "ae"
            'œ'.code -> "oe"
            'ß'.code -> "ss"
            'ł'.code -> "l"
            'đ'.code, 'ð'.code -> "d"
            'þ'.code -> "th"
            'ı'.code -> "i"
            else -> null
        }

    private fun isWhitespace(codePoint: Int): Boolean =
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

    private fun isSafePunctuation(codePoint: Int): Boolean =
        when (codePoint) {
            ' '.code,
            '\''.code,
            '-'.code,
            ','.code,
            '.'.code,
            '#'.code,
            '+'.code -> true
            else -> false
        }

    private fun capCodePoints(
        text: String,
        maxLength: Int,
    ): String {
        val codePoints = text.codePoints().toArray()
        if (codePoints.size <= maxLength) return text
        return String(codePoints, 0, maxLength)
    }

    private fun isGibberishToken(token: String): Boolean {
        val letters = token.filter { it.isLetter() }.lowercase(Locale.ROOT)
        if (letters.length < 4) return false

        val plain = normalize(letters).replace(" ", "")
        if (plain.isEmpty()) return false

        if (plain.length >= 3 && plain.toSet().size == 1) return true
        if (keyboardRuns.any(plain::contains)) return true
        if (VOWELS.none(plain::contains)) return true

        var consonantRun = 0
        for (character in plain) {
            if (character in VOWELS) {
                consonantRun = 0
            } else {
                consonantRun++
                if (consonantRun >= 7) return true
            }
        }
        return false
    }
}
