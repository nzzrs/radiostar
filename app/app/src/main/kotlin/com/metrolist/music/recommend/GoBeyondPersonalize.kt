/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Local, cheap taste summary used to order the Go Beyond landing view.
 *
 * The profile only carries data that already exists on the device. Ordering is
 * deterministic, so the landing view never jumps around between recompositions.
 */
data class GoBeyondTasteProfile(
    val insights: TelemetryInsights = TelemetryInsights.EMPTY,
    val topArtists: List<String> = emptyList(),
    val recentTitles: List<String> = emptyList(),
    val recentAlbums: List<String> = emptyList(),
    val recentArtists: List<String> = emptyList(),
    val importedTitles: List<String> = emptyList(),
    val importedArtists: List<String> = emptyList(),
    val importedAlbums: List<String> = emptyList(),
)

data class GoBeyondSuggestion(
    val card: GoBeyondCard,
    val score: Double,
    val reason: String,
)

/**
 * Ranks Go Beyond gateway cards against a local taste profile.
 *
 * Everything here is fuzzy string matching and bounded arithmetic: no network,
 * no embeddings and no Android dependencies. Scores are explainable, which is
 * why every suggestion carries a short reason.
 */
object GoBeyondPersonalize {
    private const val FREE_EXPLORATION_REASON = "A new area that fits your variety"
    private const val DEFAULT_REASON = "A fresh area to explore"
    private const val EXPLORATION_BONUS = 0.35
    private const val FUZZY_THRESHOLD = 0.72
    private const val GATEWAY_THRESHOLD = 0.80
    private const val POSITIVE_GATEWAY_WEIGHT = 3.8
    private const val NEGATIVE_GATEWAY_WEIGHT = 3.0
    private const val TEXT_WEIGHT = 1.6
    private const val MOOD_HOUR_BONUS = 2.2
    private const val SKIP_PENALTY = 1.8

    private val DIACRITICS = Regex("\\p{M}+")
    private val NON_WORD = Regex("[^a-z0-9]+")
    private val SPACE = Regex("\\s+")

    private val STOPWORDS =
        setOf(
            "a", "an", "and", "the", "of", "to", "in", "for", "with", "from",
            "your", "you", "music", "song", "songs", "artist", "artists",
            "track", "tracks", "album", "albums", "play", "plays", "playing",
            "listen", "listening", "start", "explore", "familiar", "close",
            "way", "beyond", "sound", "sounds", "scene", "same", "around",
            "keep", "coming", "back",
        )

    fun suggestions(
        cards: List<GoBeyondCard>,
        taste: GoBeyondTasteProfile,
        limit: Int = 8,
    ): List<GoBeyondSuggestion> {
        if (cards.isEmpty() || limit <= 0) return emptyList()
        val context = TasteContext(taste)
        return cards
            .mapIndexed { index, card -> index to scoreCard(card, context) }
            .sortedWith(
                compareByDescending<Pair<Int, ScoredCard>> { it.second.score }
                    .thenBy { it.first },
            )
            .take(limit)
            .map { (_, scored) ->
                GoBeyondSuggestion(
                    card = scored.card,
                    score = scored.score,
                    reason = scored.reason,
                )
            }
    }

    fun orderCards(
        cards: List<GoBeyondCard>,
        taste: GoBeyondTasteProfile,
    ): List<GoBeyondCard> = suggestions(cards, taste, cards.size).map { it.card }

    fun orderSections(
        sections: List<GoBeyondSection>,
        taste: GoBeyondTasteProfile,
    ): List<GoBeyondSection> {
        if (sections.isEmpty()) return emptyList()
        val context = TasteContext(taste)
        return sections
            .mapIndexed { index, section ->
                val cards = sectionCards(section, context)
                val average =
                    if (cards.isEmpty()) {
                        0.0
                    } else {
                        cards.map { scoreCard(it, context).score }.average()
                    }
                var score = average
                if (section == GoBeyondSection.ARTIST && context.hasArtistData) score += 0.8
                if (section == GoBeyondSection.MOOD && context.hasMoodData) score += 0.5
                SectionScore(index, section, score)
            }
            .sortedWith(
                compareByDescending<SectionScore> { it.score }
                    .thenBy { it.index },
            )
            .map { it.section }
    }

    private fun sectionCards(
        section: GoBeyondSection,
        context: TasteContext,
    ): List<GoBeyondCard> =
        when (section) {
            GoBeyondSection.ARTIST -> GoBeyondCatalog.artistCards(context.artistNames)
            GoBeyondSection.CUSTOM -> emptyList()
            else -> GoBeyondCatalog.cards(section)
        }

    private fun scoreCard(
        card: GoBeyondCard,
        context: TasteContext,
    ): ScoredCard {
        val reasons = ArrayList<WeightedReason>(6)
        var score = 0.0

        val gateway = gatewayMatch(card, context)
        if (gateway != null) {
            score += gateway.points
            gateway.positiveValue?.let {
                reasons += WeightedReason(gateway.points.coerceAtLeast(0.0), "Because you keep coming back to $it")
            }
            gateway.negativeValue?.let {
                reasons += WeightedReason(abs(gateway.points), "You tend to skip $it lately")
            }
        }

        val artist = artistMatch(card, context)
        if (artist != null) {
            score += artist.points
            val reason =
                when (artist.kind) {
                    ArtistKind.TOP -> "${artist.name} is one of your most played artists"
                    ArtistKind.RECENT -> "Because you have been listening to ${artist.name}"
                    ArtistKind.IMPORTED -> "Because ${artist.name} shows up in your listening history"
                }
            reasons += WeightedReason(artist.points, reason)
        }

        val text = textMatch(card, context)
        if (text != null) {
            val points = text.overlap * TEXT_WEIGHT
            score += points
            reasons += WeightedReason(points, textReason(text))
        }

        val mood = moodMatch(card, context)
        if (mood != null) {
            score += mood.bonus
            if (mood.hourReason && mood.hour != null) {
                val label = mood.hour.toString().padStart(2, '0')
                reasons += WeightedReason(MOOD_HOUR_BONUS, "You listen most around $label:00")
            }
        }

        val behavior = behaviorBonus(card, context)
        if (behavior != 0.0) {
            score += behavior
            if (behavior > 0.0) reasons += WeightedReason(behavior, FREE_EXPLORATION_REASON)
        }

        val period = periodMatch(card, context)
        if (period != null) {
            score += period.points
            if (period.points > 0.0) reasons += WeightedReason(period.points, "A recent era worth digging into")
        }

        val hasEvidence =
            gateway != null ||
                artist != null ||
                text != null ||
                mood?.hourReason == true ||
                period != null
        if (!hasEvidence && context.insights.noveltyAppetite >= 0.5) {
            score += EXPLORATION_BONUS
            reasons += WeightedReason(EXPLORATION_BONUS, FREE_EXPLORATION_REASON)
        }

        val reason = reasons.maxByOrNull { it.weight }?.text ?: DEFAULT_REASON
        return ScoredCard(card, score, reason)
    }

    private fun gatewayMatch(
        card: GoBeyondCard,
        context: TasteContext,
    ): GatewayMatch? {
        val affinity = context.insights.gatewayAffinity
        if (affinity.isEmpty()) return null
        val keys = cardGatewayKeys(card)
        var positive = 0.0
        var positiveValue: String? = null
        var negative = 0.0
        var negativeValue: String? = null

        for ((value, weight) in affinity.entries.sortedBy { normalize(it.key) }) {
            val similarity = keys.maxOf { fuzzySimilarity(it, value) }
            if (similarity < GATEWAY_THRESHOLD) continue
            if (weight > 0.0) {
                val points = weight * similarity * POSITIVE_GATEWAY_WEIGHT
                if (points > positive) {
                    positive = points
                    positiveValue = value
                }
            } else if (weight < 0.0) {
                val points = weight * similarity * NEGATIVE_GATEWAY_WEIGHT
                if (points < negative) {
                    negative = points
                    negativeValue = value
                }
            }
        }

        if (positiveValue == null && negativeValue == null) return null
        return GatewayMatch(positive + negative, positiveValue, negativeValue)
    }

    private fun artistMatch(
        card: GoBeyondCard,
        context: TasteContext,
    ): ArtistMatch? {
        if (card.section != GoBeyondSection.ARTIST) return null
        val names =
            buildList {
                add(card.title)
                card.selection.artist?.let { add(it) }
            }.filter { it.isNotBlank() }
        if (names.isEmpty()) return null

        var best: ArtistMatch? = null

        fun consider(
            values: List<String>,
            weight: Double,
            kind: ArtistKind,
        ) {
            for (value in values) {
                val similarity = names.maxOf { fuzzySimilarity(it, value) }
                if (similarity < FUZZY_THRESHOLD) continue
                val points = similarity * weight
                if (best == null || points > best!!.points) {
                    best = ArtistMatch(points, value, kind)
                }
            }
        }

        consider(context.topArtists, 4.2, ArtistKind.TOP)
        consider(context.recentArtists, 3.2, ArtistKind.RECENT)
        consider(context.importedArtists, 2.8, ArtistKind.IMPORTED)
        return best
    }

    private fun textMatch(
        card: GoBeyondCard,
        context: TasteContext,
    ): TextMatch? {
        if (context.textValues.isEmpty()) return null
        var best = 0.0
        var bestValue: TextValue? = null
        for (value in context.textValues) {
            val overlap = cardOverlap(card, value.value) * value.weight
            if (overlap > best) {
                best = overlap
                bestValue = value
            }
        }
        if (best < 0.25 || bestValue == null) return null
        return TextMatch(best, bestValue.value, bestValue.kind)
    }

    private fun cardOverlap(
        card: GoBeyondCard,
        value: String,
    ): Double {
        val fields =
            listOf(
                card.title to 1.0,
                card.subtitle to 0.55,
                card.semanticPrompt to 0.5,
                card.description to 0.4,
            )
        var best = 0.0
        for ((text, weight) in fields) {
            val overlap = textOverlap(text, value) * weight
            if (overlap > best) best = overlap
        }
        return best
    }

    private fun textReason(match: TextMatch): String =
        when (match.kind) {
            TextKind.TOP_ARTIST, TextKind.RECENT_ARTIST, TextKind.IMPORTED_ARTIST ->
                "Because you have been listening to ${match.value}"
            TextKind.RECENT_TITLE, TextKind.IMPORTED_TITLE, TextKind.ALBUM ->
                "Because ${match.value} is already in your rotation"
        }

    private fun moodMatch(
        card: GoBeyondCard,
        context: TasteContext,
    ): MoodMatch? {
        if (card.section != GoBeyondSection.MOOD) return null
        val title = normalize(card.title)
        val insights = context.insights
        var bonus = 0.0
        var hourReason = false
        val hour = insights.bestHour

        if (hour != null) {
            val favored =
                when (hour) {
                    in 22..23, in 0..4 -> setOf("calm", "melancholic", "dreamy")
                    in 5..11 -> setOf("focus", "euphoric")
                    in 12..16 -> setOf("playful", "nostalgic")
                    in 17..21 -> setOf("romantic", "dreamy")
                    else -> emptySet()
                }
            if (title in favored) {
                bonus += MOOD_HOUR_BONUS
                hourReason = true
            }
        }

        if (insights.skipRate >= 0.45 && title in setOf("angry", "euphoric")) {
            bonus -= SKIP_PENALTY
        }
        if (insights.completionRate >= 0.7 && title == "euphoric") {
            bonus += 0.35
        }

        return if (bonus != 0.0) MoodMatch(bonus, hourReason, hour) else null
    }

    private fun behaviorBonus(
        card: GoBeyondCard,
        context: TasteContext,
    ): Double {
        val novelty = context.insights.noveltyAppetite
        if (novelty < 0.5) return 0.0
        return when (card.section) {
            GoBeyondSection.COUNTRY,
            GoBeyondSection.GENRE,
            GoBeyondSection.PERIOD,
            -> 0.25 + novelty * 0.45
            GoBeyondSection.ARTIST -> -0.35 * novelty
            else -> 0.0
        }
    }

    private fun periodMatch(
        card: GoBeyondCard,
        context: TasteContext,
    ): PeriodMatch? {
        if (card.section != GoBeyondSection.PERIOD) return null
        val activity =
            context.insights.events +
                context.taste.recentTitles.size +
                context.taste.importedTitles.size
        if (activity <= 0) return null
        val fromYear = card.selection.fromYear ?: return null
        val recency = ((fromYear - 1950) / 10).coerceIn(0, 7) / 7.0
        val strength = (activity / 24.0).coerceIn(0.15, 1.0)
        val variety = (Math.floorMod(card.id.hashCode(), 5) - 2) * 0.04
        return PeriodMatch(recency * strength * 0.9 + variety)
    }

    private fun cardGatewayKeys(card: GoBeyondCard): List<String> =
        buildList {
            add(card.title)
            val selection = card.selection
            selection.country?.let { add(it) }
            selection.genre?.let { add(it) }
            selection.artist?.let { add(it) }
            selection.color?.let { add(it) }
            selection.mood?.let { add(it) }
            selection.freeText?.let { add(it) }
            selection.periodLabel()?.let { add(it) }
            selection.fromYear?.let { add(it.toString()) }
        }.filter { it.isNotBlank() }

    private fun textOverlap(
        cardText: String,
        userText: String,
    ): Double {
        val userTokens = meaningfulTokens(userText)
        if (userTokens.isEmpty()) return 0.0
        val cardTokens = meaningfulTokens(cardText)
        if (cardTokens.isEmpty()) return 0.0
        if (cardTokens == userTokens) return 1.0
        var hits = 0
        for (token in userTokens) {
            if (cardTokens.any { closeToken(it, token) }) hits += 1
        }
        return hits.toDouble() / userTokens.size
    }

    private fun meaningfulTokens(text: String): List<String> =
        normalize(text)
            .split(' ')
            .filter { it.length >= 2 && it !in STOPWORDS }

    private fun closeToken(
        left: String,
        right: String,
    ): Boolean {
        if (left == right) return true
        if (left.length < 4 || right.length < 4) return false
        if (abs(left.length - right.length) > 2) return false
        val allowed = if (max(left.length, right.length) >= 8) 2 else 1
        return levenshtein(left, right) <= allowed
    }

    private fun fuzzySimilarity(
        left: String,
        right: String,
    ): Double {
        val a = normalize(left)
        val b = normalize(right)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0

        val aTokens = a.split(' ')
        val bTokens = b.split(' ')
        val hits = aTokens.count { token -> bTokens.any { closeToken(token, it) } }
        val containment =
            when {
                hits == aTokens.size -> 0.9
                hits == bTokens.size -> 0.9
                else -> 0.0
            }
        val jaccard = if (hits == 0) 0.0 else hits.toDouble() / (aTokens.size + bTokens.size - hits)
        val maxLength = max(a.length, b.length)
        val levenshteinRatio =
            if (maxLength >= 4 && abs(a.length - b.length) <= maxLength / 2) {
                (1.0 - levenshtein(a, b).toDouble() / maxLength).coerceAtLeast(0.0)
            } else {
                0.0
            }
        return maxOf(containment, jaccard * 0.85, levenshteinRatio)
    }

    private fun normalize(raw: String): String {
        val folded = Normalizer.normalize(raw, Normalizer.Form.NFD)
        return folded
            .replace(DIACRITICS, "")
            .lowercase(Locale.ROOT)
            .replace(NON_WORD, " ")
            .replace(SPACE, " ")
            .trim()
    }

    private fun levenshtein(
        left: String,
        right: String,
    ): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (i in 1..left.length) {
            current[0] = i
            val leftChar = left[i - 1]
            for (j in 1..right.length) {
                val cost = if (leftChar == right[j - 1]) 0 else 1
                current[j] =
                    minOf(
                        current[j - 1] + 1,
                        previous[j] + 1,
                        previous[j - 1] + cost,
                    )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }

    private class TasteContext(
        val taste: GoBeyondTasteProfile,
    ) {
        val insights: TelemetryInsights = taste.insights

        val topArtists: List<String> =
            distinctStrings(
                taste.topArtists +
                    insights.topArtists.sortedByDescending { it.listens }.map { it.displayName },
            )
        val recentArtists: List<String> = distinctStrings(taste.recentArtists)
        val importedArtists: List<String> = distinctStrings(taste.importedArtists)
        val recentTitles: List<String> = distinctStrings(taste.recentTitles)
        val importedTitles: List<String> = distinctStrings(taste.importedTitles)
        val albums: List<String> = distinctStrings(taste.recentAlbums + taste.importedAlbums)

        val artistNames: List<String> =
            distinctStrings(topArtists + recentArtists + importedArtists)

        val hasArtistData: Boolean = artistNames.isNotEmpty()
        val hasMoodData: Boolean = insights.bestHour != null

        val textValues: List<TextValue> =
            buildList {
                topArtists.forEach { add(TextValue(it, 1.0, TextKind.TOP_ARTIST)) }
                recentArtists.forEach { add(TextValue(it, 0.75, TextKind.RECENT_ARTIST)) }
                importedArtists.forEach { add(TextValue(it, 0.70, TextKind.IMPORTED_ARTIST)) }
                insights.topSongs
                    .sortedByDescending { it.listens }
                    .forEach { add(TextValue(it.artist, 0.60, TextKind.RECENT_ARTIST)) }
                recentTitles.forEach { add(TextValue(it, 0.60, TextKind.RECENT_TITLE)) }
                importedTitles.forEach { add(TextValue(it, 0.55, TextKind.IMPORTED_TITLE)) }
                albums.forEach { add(TextValue(it, 0.50, TextKind.ALBUM)) }
            }.filter { it.value.isNotBlank() }

        private fun distinctStrings(values: List<String>): List<String> {
            val seen = HashSet<String>()
            return values
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filter { normalize(it).isNotEmpty() }
                .filter { seen.add(normalize(it)) }
        }
    }

    private data class ScoredCard(
        val card: GoBeyondCard,
        val score: Double,
        val reason: String,
    )

    private data class SectionScore(
        val index: Int,
        val section: GoBeyondSection,
        val score: Double,
    )

    private data class WeightedReason(
        val weight: Double,
        val text: String,
    )

    private data class GatewayMatch(
        val points: Double,
        val positiveValue: String?,
        val negativeValue: String?,
    )

    private enum class ArtistKind { TOP, RECENT, IMPORTED }

    private data class ArtistMatch(
        val points: Double,
        val name: String,
        val kind: ArtistKind,
    )

    private enum class TextKind { TOP_ARTIST, RECENT_ARTIST, IMPORTED_ARTIST, RECENT_TITLE, IMPORTED_TITLE, ALBUM }

    private data class TextValue(
        val value: String,
        val weight: Double,
        val kind: TextKind,
    )

    private data class TextMatch(
        val overlap: Double,
        val value: String,
        val kind: TextKind,
    )

    private data class MoodMatch(
        val bonus: Double,
        val hourReason: Boolean,
        val hour: Int?,
    )

    private data class PeriodMatch(
        val points: Double,
    )
}
