/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.util.Locale

/** Tonal colour of a target. Only modes the analyser can detect are listed. */
enum class SonicMode {
    MAJOR,
    MINOR,
}

/**
 * The sound a Go Beyond card asks for, expressed in numbers the audio tap can
 * measure: tempo, loudness and mode.
 *
 * A card like "Euphoric" is a request for a fast, loud, bright record, not for
 * songs called "Euphoria". [tags] therefore carries curated genre/era words
 * used to search the sound, while the mood title and its descriptors are kept
 * out of the query list.
 *
 * Limitation: only tracks the phone has played have a local profile (BPM, RMS
 * and key come from [RadioStarProfilesKey]). Unplayed candidates rank by
 * embedding and seed radio, and improve as the listener plays them.
 */
data class SonicTarget(
    val bpmRange: ClosedFloatingPointRange<Double>,
    val energyRange: ClosedFloatingPointRange<Float>,
    val mode: SonicMode? = null,
    val valence: Float? = null,
    /** Curated sound words. Never the mood title, aliases or descriptors. */
    val tags: List<String> = emptyList(),
    /** How strongly a known profile may move this card's candidates. */
    val weight: Double = 0.30,
)

object GoBeyondSonic {
    private const val BPM_SCORE_WEIGHT = 0.45
    private const val ENERGY_SCORE_WEIGHT = 0.35
    private const val MODE_SCORE_WEIGHT = 0.20

    private const val COLOR_WEIGHT = 0.16
    private const val PERIOD_WEIGHT = 0.12
    private const val GENRE_WEIGHT = 0.08
    private const val COUNTRY_WEIGHT = 0.05

    /** Radio seeds need at least this fit before they are trusted. */
    const val SEED_THRESHOLD = 0.60

    /** Known tracks below this fit keep their semantic slot instead of moving. */
    const val ORDER_THRESHOLD = 0.50

    private const val LOW_RMS_MIN = 0.02f
    private const val LOW_RMS_MAX = 0.14f
    private const val MID_RMS_MIN = 0.08f
    private const val MID_RMS_MAX = 0.26f
    private const val HIGH_RMS_MIN = 0.18f
    private const val HIGH_RMS_MAX = 0.45f

    private val moodTargets: Map<String, SonicTarget> =
        mapOf(
            "euphoric" to
                SonicTarget(
                    bpmRange = 120.0..140.0,
                    energyRange = HIGH_RMS_MIN..HIGH_RMS_MAX,
                    mode = SonicMode.MAJOR,
                    valence = 0.85f,
                    tags = listOf("dance pop", "eurodance", "house", "trance", "disco"),
                ),
            "calm" to
                SonicTarget(
                    bpmRange = 60.0..90.0,
                    energyRange = LOW_RMS_MIN..LOW_RMS_MAX,
                    valence = 0.55f,
                    tags = listOf("ambient", "downtempo", "acoustic", "piano", "lo-fi"),
                ),
            "focus" to
                SonicTarget(
                    bpmRange = 90.0..120.0,
                    energyRange = MID_RMS_MIN..MID_RMS_MAX,
                    valence = 0.50f,
                    tags = listOf("lo-fi beats", "ambient techno", "minimal", "instrumental", "downtempo"),
                ),
            "melancholic" to
                SonicTarget(
                    bpmRange = 60.0..90.0,
                    energyRange = LOW_RMS_MIN..LOW_RMS_MAX,
                    mode = SonicMode.MINOR,
                    valence = 0.20f,
                    tags = listOf("indie folk", "chamber pop", "shoegaze", "piano", "acoustic"),
                ),
            "angry" to
                SonicTarget(
                    bpmRange = 130.0..180.0,
                    energyRange = HIGH_RMS_MIN..HIGH_RMS_MAX,
                    mode = SonicMode.MINOR,
                    valence = 0.15f,
                    tags = listOf("hardcore punk", "thrash metal", "industrial rock", "nu metal", "punk"),
                ),
            "dreamy" to
                SonicTarget(
                    bpmRange = 70.0..100.0,
                    energyRange = MID_RMS_MIN..MID_RMS_MAX,
                    valence = 0.60f,
                    tags = listOf("shoegaze", "ambient pop", "chillwave", "bedroom pop", "post-rock"),
                ),
            "romantic" to
                SonicTarget(
                    bpmRange = 60.0..100.0,
                    energyRange = MID_RMS_MIN..MID_RMS_MAX,
                    mode = SonicMode.MAJOR,
                    valence = 0.70f,
                    tags = listOf("slow jam", "soul", "bossa nova", "quiet storm", "R&B"),
                ),
            "nostalgic" to
                SonicTarget(
                    bpmRange = 80.0..110.0,
                    energyRange = MID_RMS_MIN..MID_RMS_MAX,
                    valence = 0.55f,
                    tags = listOf("classic rock", "city pop", "synthwave", "motown", "doo-wop"),
                ),
            "playful" to
                SonicTarget(
                    bpmRange = 110.0..135.0,
                    energyRange = HIGH_RMS_MIN..HIGH_RMS_MAX,
                    mode = SonicMode.MAJOR,
                    valence = 0.80f,
                    tags = listOf("bubblegum pop", "ska", "dance pop", "disco", "chiptune"),
                ),
            "moody" to
                SonicTarget(
                    bpmRange = 70.0..100.0,
                    energyRange = MID_RMS_MIN..MID_RMS_MAX,
                    mode = SonicMode.MINOR,
                    valence = 0.30f,
                    tags = listOf("trip-hop", "post-punk", "downtempo", "noir jazz", "industrial"),
                ),
        )

    /** The curated target for a mood title or alias, or null for a free word. */
    fun targetForMood(mood: String?): SonicTarget? {
        val entry = moodEntry(mood) ?: return null
        return moodTargets[entry.title.lowercase(Locale.ROOT)]
    }

    /** Picks the strongest applicable target: mood, then colour, period, genre, country. */
    fun targetFor(selection: GoBeyondSelection): SonicTarget? {
        selection.mood?.let { mood -> targetForMood(mood)?.let { return it } }
        selection.color?.let { color -> colorTarget(color)?.let { return it } }
        selection.periodLabel()?.let { return periodTarget(selection.fromYear, selection.toYear) }
        selection.genre?.let { genre -> genreTarget(genre)?.let { return it } }
        if (selection.country != null) return countryTarget()
        return null
    }

    fun colorTarget(color: String?): SonicTarget? {
        val name = GoBeyondColors.byName(color)?.name ?: return null
        return when (name) {
            "Yellow", "Orange", "Red", "Magenta", "Turquoise" ->
                SonicTarget(
                    bpmRange = 110.0..150.0,
                    energyRange = HIGH_RMS_MIN..HIGH_RMS_MAX,
                    mode = SonicMode.MAJOR,
                    weight = COLOR_WEIGHT,
                    tags = listOf("dance", "disco"),
                )
            "Blue", "Indigo", "Black", "Grey" ->
                SonicTarget(
                    bpmRange = 65.0..105.0,
                    energyRange = LOW_RMS_MIN..MID_RMS_MAX,
                    mode = SonicMode.MINOR,
                    weight = COLOR_WEIGHT,
                    tags = listOf("ambient", "piano"),
                )
            "Pink", "White", "Teal", "Silver" ->
                SonicTarget(
                    bpmRange = 90.0..125.0,
                    energyRange = MID_RMS_MIN..MID_RMS_MAX,
                    weight = COLOR_WEIGHT,
                    tags = listOf("dream pop", "ambient pop"),
                )
            else ->
                SonicTarget(
                    bpmRange = 80.0..115.0,
                    energyRange = MID_RMS_MIN..MID_RMS_MAX,
                    weight = COLOR_WEIGHT,
                    tags = listOf("soul", "folk"),
                )
        }
    }

    fun periodTarget(fromYear: Int?, toYear: Int?): SonicTarget? {
        val start = fromYear ?: toYear?.minus(9) ?: return null
        val bpm =
            when {
                start <= 1959 -> 80.0..120.0
                start <= 1969 -> 90.0..130.0
                start <= 1979 -> 95.0..135.0
                start <= 1989 -> 100.0..140.0
                start <= 2009 -> 100.0..150.0
                start <= 2019 -> 100.0..160.0
                else -> 100.0..170.0
            }
        return SonicTarget(
            bpmRange = bpm,
            energyRange = MID_RMS_MIN..HIGH_RMS_MAX,
            weight = PERIOD_WEIGHT,
        )
    }

    fun genreTarget(genre: String?): SonicTarget? {
        val value = genre?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val fast =
            listOf(
                "techno", "trance", "drum", "dubstep", "metal", "punk", "hardcore", "house",
                "disco", "dance", "electronic", "reggaeton", "breakbeat", "phonk", "drill",
                "trap", "grime", "hyperpop", "ska", "funk", "rock",
            )
        val slow =
            listOf(
                "ambient", "classical", "folk", "lo-fi", "bossa", "downtempo", "trip-hop",
                "new age", "blues", "soul", "jazz",
            )
        return when {
            fast.any(value::contains) ->
                SonicTarget(
                    bpmRange = 120.0..150.0,
                    energyRange = HIGH_RMS_MIN..HIGH_RMS_MAX,
                    weight = GENRE_WEIGHT,
                )
            slow.any(value::contains) ->
                SonicTarget(
                    bpmRange = 60.0..100.0,
                    energyRange = LOW_RMS_MIN..MID_RMS_MAX,
                    weight = GENRE_WEIGHT,
                )
            else ->
                SonicTarget(
                    bpmRange = 90.0..130.0,
                    energyRange = MID_RMS_MIN..MID_RMS_MAX,
                    weight = GENRE_WEIGHT,
                )
        }
    }

    fun countryTarget(): SonicTarget =
        SonicTarget(
            bpmRange = 80.0..150.0,
            energyRange = LOW_RMS_MIN..HIGH_RMS_MAX,
            weight = COUNTRY_WEIGHT,
        )

    /**
     * Fit of one local profile against a target, in `0..1`, or null when the
     * profile has none of the features the target asks for. A missing profile
     * is neutral: callers must not treat null as a bad score.
     */
    fun score(
        profile: AudioProfile?,
        target: SonicTarget?,
    ): Double? {
        if (profile == null || target == null) return null
        var total = 0.0
        var weight = 0.0

        profile.bpm?.let { bpm ->
            total += BPM_SCORE_WEIGHT * rangeFit(bpm, target.bpmRange.start, target.bpmRange.endInclusive)
            weight += BPM_SCORE_WEIGHT
        }
        profile.rms?.let { rms ->
            total +=
                ENERGY_SCORE_WEIGHT *
                rangeFit(rms.toDouble(), target.energyRange.start.toDouble(), target.energyRange.endInclusive.toDouble())
            weight += ENERGY_SCORE_WEIGHT
        }
        profile.key?.let { key ->
            target.mode?.let { mode ->
                total += MODE_SCORE_WEIGHT * if ((mode == SonicMode.MINOR) == key.minor) 1.0 else 0.0
                weight += MODE_SCORE_WEIGHT
            }
        }
        return if (weight < 1e-9) null else (total / weight).coerceIn(0.0, 1.0)
    }

    /**
     * Places the known, in-target tracks in an energy arc while unknown tracks
     * keep their semantic slot. Cold journeys are returned untouched.
     */
    fun order(
        entries: List<GoBeyondScore>,
        profiles: Map<String, AudioProfile>,
        target: SonicTarget?,
    ): List<GoBeyondScore> {
        if (entries.size < 2 || target == null || profiles.isEmpty()) return entries
        val active =
            profiles.filterValues { profile ->
                (score(profile, target) ?: 0.0) >= ORDER_THRESHOLD
            }
        if (active.size < 2) return entries

        val scoresById = entries.associateBy { it.candidate.id }
        val slate =
            entries.map { entry ->
                SlateEntry(
                    candidate =
                        Candidate(
                            id = entry.candidate.id,
                            title = entry.candidate.title,
                            artists = entry.candidate.artists,
                            vector = DoubleArray(0),
                            durationSeconds = entry.candidate.durationSeconds,
                        ),
                    score = entry.score,
                )
            }
        val rms =
            active.mapNotNull { (id, profile) ->
                profile.rms?.let { id to it }
            }.toMap()
        val ordered =
            if (rms.size >= 2) {
                EnergyOrderer.orderByEnergyArc(slate, rms)
            } else {
                AudioOrderer.harmonicPath(slate, active, reference = null)
            }
        return ordered.mapNotNull { scoresById[it.candidate.id] }
    }

    /**
     * Search phrases for a mood target, built only from curated genre/era tags.
     * The mood word itself never belongs here.
     */
    fun queryTags(
        target: SonicTarget,
        era: String?,
        genre: String?,
    ): List<String> {
        val tags = target.tags.ifEmpty { listOf("instrumental") }
        val withEra =
            tags.take(3).map { tag ->
                listOfNotNull(tag, era).joinToString(" ")
            }
        val secondary = tags.drop(3).take(1).map { "$it music" }
        val genreMix =
            genre
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let { value -> tags.firstOrNull()?.let { "$value $it" } }
        return (withEra + secondary + listOfNotNull(genreMix)).distinct()
    }

    /**
     * Words that must never become a query for a mood card: its title, aliases
     * and prompt descriptors. "Euphoric" also covers "Euphoria" through a
     * shared stem, which is why literal title matching is not plain equality.
     */
    fun literalMoodTerms(mood: String?): Set<String> {
        val entry = moodEntry(mood) ?: return emptySet()
        val terms = LinkedHashSet<String>()
        terms += entry.title.lowercase(Locale.ROOT)
        entry.aliases.forEach { terms += it.lowercase(Locale.ROOT) }
        entry.prompt.split(',').forEach { part ->
            part.trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }?.let(terms::add)
        }
        return terms
    }

    /** Removes literal mood words from a query, keeping its genre/era tags. */
    fun stripLiteralMood(
        value: String,
        mood: String?,
    ): String {
        val terms = literalMoodTerms(mood)
        if (terms.isEmpty()) return value.replace(Regex("\\s+"), " ").trim()
        var text = value
        terms
            .filter { it.contains(' ') }
            .sortedByDescending { it.length }
            .forEach { phrase ->
                text = text.replace(Regex("(?i)\\b${Regex.escape(phrase)}\\b"), " ")
            }
        val words = terms.filterNot { it.contains(' ') }.map { it.lowercase(Locale.ROOT) }
        return text
            .split(Regex("\\s+"))
            .filter { word ->
                val normalized = word.lowercase(Locale.ROOT).trim(',', '.', '-', '&', '/')
                normalized.isNotEmpty() && words.none { term -> wordMatches(normalized, term) }
            }.joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** True when a query still carries a literal mood word. */
    fun containsLiteralMood(
        value: String,
        mood: String?,
    ): Boolean {
        val terms = literalMoodTerms(mood)
        if (terms.isEmpty()) return false
        val normalized = value.lowercase(Locale.ROOT)
        return terms.any { term ->
            if (term.contains(' ')) {
                Regex("(?i)\\b${Regex.escape(term)}\\b").containsMatchIn(value)
            } else {
                normalized
                    .split(Regex("\\s+"))
                    .map { it.trim(',', '.', '-', '&', '/') }
                    .any { word -> word.isNotEmpty() && wordMatches(word, term) }
            }
        }
    }

    private fun wordMatches(
        word: String,
        term: String,
    ): Boolean {
        if (term.length >= 5) {
            return word.length >= 5 && word.take(5) == term.take(5)
        }
        return word == term
    }

    /**
     * Distance to a numeric band: 1.0 inside, falling linearly to 0 at one band
     * width outside. Never negative, so an extreme track is neutral-bad, not
     * disqualified.
     */
    internal fun rangeFit(
        value: Double,
        start: Double,
        end: Double,
    ): Double {
        val low = minOf(start, end)
        val high = maxOf(start, end)
        if (value in low..high) return 1.0
        val span = (high - low).coerceAtLeast(1.0)
        val distance = if (value < low) low - value else value - high
        return (1.0 - distance / span).coerceIn(0.0, 1.0)
    }

    private fun moodEntry(mood: String?): GoBeyondEntry? {
        val value = mood?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return GoBeyondCatalog.moods.firstOrNull { entry ->
            entry.title.lowercase(Locale.ROOT) == value ||
                entry.aliases.any { alias -> alias.lowercase(Locale.ROOT) == value }
        }
    }
}
