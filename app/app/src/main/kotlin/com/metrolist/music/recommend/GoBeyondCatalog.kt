/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.util.Locale
import java.util.UUID

enum class GoBeyondSection(
    val label: String,
) {
    COUNTRY("Country"),
    GENRE("Genre"),
    ARTIST("Artist"),
    PERIOD("Period"),
    COLOR("Color"),
    MOOD("Mood"),
    CUSTOM("Custom"),
}

data class GoBeyondFacet(
    val section: GoBeyondSection,
    val value: String,
)

/** Artwork source for a Go Beyond card. */
sealed interface GoBeyondArt {
    val hue: Int

    data class Flag(
        val flag: String,
        override val hue: Int,
    ) : GoBeyondArt

    data class Photo(
        val imageUrl: String?,
        override val hue: Int,
    ) : GoBeyondArt

    data class Abstract(
        override val hue: Int,
    ) : GoBeyondArt

    data class Color(
        val colorName: String,
        override val hue: Int,
    ) : GoBeyondArt

    data class Era(
        val label: String,
        override val hue: Int,
    ) : GoBeyondArt

    data class Mood(
        override val hue: Int,
    ) : GoBeyondArt
}

/**
 * One starting point for a Go Beyond journey. A card is either a single facet
 * (Brazil, Shoegaze, Purple) or a combination assembled by the custom builder.
 */
data class GoBeyondCard(
    val id: String,
    val section: GoBeyondSection,
    val title: String,
    val subtitle: String,
    val description: String,
    val selection: GoBeyondSelection,
    val accentHue: Int,
    val semanticPrompt: String,
    val art: GoBeyondArt = GoBeyondArt.Abstract(accentHue),
)

/**
 * A music area the listener wants to explore. Every field is optional, so the
 * same type describes one card and a custom combination of several facets.
 */
data class GoBeyondSelection(
    val country: String? = null,
    val genre: String? = null,
    val artist: String? = null,
    val fromYear: Int? = null,
    val toYear: Int? = null,
    val color: String? = null,
    val mood: String? = null,
    val freeText: String? = null,
) {
    val facets: List<GoBeyondFacet>
        get() =
            buildList {
                country?.trim()?.takeIf(String::isNotBlank)?.let {
                    add(GoBeyondFacet(GoBeyondSection.COUNTRY, it))
                }
                genre?.trim()?.takeIf(String::isNotBlank)?.let {
                    add(GoBeyondFacet(GoBeyondSection.GENRE, it))
                }
                artist?.trim()?.takeIf(String::isNotBlank)?.let {
                    add(GoBeyondFacet(GoBeyondSection.ARTIST, it))
                }
                periodLabel()?.let {
                    add(GoBeyondFacet(GoBeyondSection.PERIOD, it))
                }
                color?.trim()?.takeIf(String::isNotBlank)?.let {
                    add(GoBeyondFacet(GoBeyondSection.COLOR, it))
                }
                mood?.trim()?.takeIf(String::isNotBlank)?.let {
                    add(GoBeyondFacet(GoBeyondSection.MOOD, it))
                }
                freeText?.trim()?.takeIf(String::isNotBlank)?.let {
                    add(GoBeyondFacet(GoBeyondSection.CUSTOM, it))
                }
            }

    fun isBlank(): Boolean = facets.isEmpty()

    fun displayTitle(): String =
        facets
            .filter { it.section != GoBeyondSection.CUSTOM }
            .joinToString(" · ") { it.value }
            .ifBlank { freeText?.trim().orEmpty() }

    fun playlistName(): String {
        val values = facets.map { it.value }
        return if (values.isEmpty()) "Go Beyond" else "Go Beyond · ${values.joinToString(" · ")}"
    }

    fun key(): String = facets.joinToString("|") { "${it.section.name}:${it.value.lowercase(Locale.ROOT)}" }

    /** Stable playlist id so regenerating a journey replaces the same row. */
    fun stableId(): String {
        val digest = UUID.nameUUIDFromBytes(key().toByteArray(Charsets.UTF_8)).toString()
        return "LP_RS_GO_" + digest.replace("-", "").take(16).uppercase(Locale.ROOT)
    }

    fun periodLabel(): String? {
        val start = fromYear
        val end = toYear
        if (start == null && end == null) return null
        return GoBeyondCatalog.periodLabel(start, end)
    }

    /** Queries for an artist journey. All results are later filtered to the artist. */
    fun artistSearchQueries(): List<String> {
        val name = artist?.trim()?.takeIf(String::isNotBlank) ?: return emptyList()
        val hints = listOfNotNull(genre?.trim(), country?.trim(), mood?.trim()).filter { it.isNotBlank() }
        val base =
            listOf(
                name,
                "$name songs",
                "$name album tracks",
                "$name deep cuts",
                "$name live",
            )
        return (base + hints.map { "$name $it" })
            .map(::cleanQuery)
            .filter { it.isNotBlank() }
            .distinct()
    }

    /** Country queries use the demonym, so the target is the country, not the word. */
    fun countrySearchQueries(): List<String> {
        val place = country?.trim()?.takeIf(String::isNotBlank) ?: return emptyList()
        val adjective = GoBeyondCatalog.countryAdjective(place)
        val sound = listOfNotNull(genre?.trim(), mood?.trim()).filter { it.isNotBlank() }.joinToString(" ")
        val combined = listOf(adjective, sound).filter { it.isNotBlank() }.joinToString(" ")
        return listOf(
            combined,
            "$adjective music",
            "$adjective songs",
            if (sound.isBlank()) "$adjective classics" else "$adjective $sound mix",
            "$place music",
        )
            .map(::cleanQuery)
            .filter { it.isNotBlank() }
            .distinct()
    }

    /**
     * Search phrases are deliberately narrow first, then wider. The colour
     * name is never a keyword: "purple" must mean a sound, not a song title.
     */
    fun searchQueries(): List<String> {
        val era = periodLabel()
        val queries = ArrayList<String>(6)

        if (artist != null) {
            queries += artistSearchQueries()
            era?.let { queries += cleanQuery("$artist $it") }
            return queries.distinct()
        }

        if (country != null) {
            queries += countrySearchQueries()
        } else {
            val core =
                listOfNotNull(genre, mood)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
                    .ifBlank { freeText?.trim().orEmpty() }
            if (core.isNotBlank()) {
                queries += cleanQuery(listOf(core, era).filterNotNull().joinToString(" "))
                queries += cleanQuery("$core music")
            }
        }

        era?.let { queries += cleanQuery("$it music") }

        val colorEntry = GoBeyondColors.byName(color)
        colorEntry?.let { entry ->
            queries += GoBeyondColors.searchQueryTerms(entry)
        }
        if (colorEntry == null) {
            val descriptor =
                mood?.let {
                    GoBeyondCatalog.moods
                        .firstOrNull { moodEntry -> moodEntry.title.equals(it, ignoreCase = true) }
                        ?.prompt
                }
            descriptor
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.take(4)
                ?.joinToString(" ")
                ?.takeIf { it.isNotBlank() }
                ?.let { queries += cleanQuery(it) }
        }
        if (queries.isEmpty()) queries += "music"
        return queries.map(::cleanQuery).filter { it.isNotBlank() }.distinct()
    }

    private fun cleanQuery(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    fun semanticPrompt(): String {
        val parts = ArrayList<String>()
        country?.let {
            parts += GoBeyondCatalog.countryPrompt(it)
        }
        genre?.let {
            parts += "$it music, $it sounds and textures"
        }
        artist?.let {
            parts += "music by $it and artists from the same scene"
        }
        periodLabel()?.let {
            parts += "music released in the $it"
        }
        color?.let {
            parts += GoBeyondColors.byName(it)?.prompt
                ?: "music that feels ${it.lowercase(Locale.ROOT)}, atmospheric and textural"
        }
        mood?.let {
            parts += "$it music, ${it.lowercase(Locale.ROOT)} emotional tone"
        }
        freeText?.trim()?.takeIf(String::isNotBlank)?.let { parts += it }
        return parts.joinToString(", ").ifBlank { "music" }
    }
}

data class GoBeyondEntry(
    val title: String,
    val subtitle: String,
    val prompt: String,
    val hue: Int,
    val flag: String? = null,
    val aliases: List<String> = emptyList(),
)

object GoBeyondCatalog {
    val sections: List<GoBeyondSection> = GoBeyondSection.entries

    private data class CountryHint(
        val subtitle: String,
        val prompt: String,
        val hue: Int,
    )

    private val countryHints =
        mapOf(
            "BR" to CountryHint("Samba, bossa nova and modern MPB", "Brazilian music, samba, bossa nova, MPB", 145),
            "JP" to CountryHint("City pop, enka and math rock", "Japanese music, city pop, enka, math rock", 10),
            "NG" to CountryHint("Afrobeat, highlife and alté", "Nigerian music, Afrobeat, highlife, alté", 120),
            "KR" to CountryHint("K-indie, trot and hip-hop", "South Korean music, K-indie, trot, Korean hip-hop", 210),
            "IN" to CountryHint("Filmi, ghazal and indie", "Indian music, filmi, ghazal, Indian indie", 35),
            "MX" to CountryHint("Cumbia, norteño and música Mexicana", "Mexican music, cumbia, norteño, música Mexicana", 20),
            "JM" to CountryHint("Reggae, dancehall and dub", "Jamaican music, reggae, dancehall, dub", 100),
            "IS" to CountryHint("Post-rock and dream pop", "Icelandic music, post-rock, dream pop, glacial ambience", 190),
            "ML" to CountryHint("Desert blues and Wassoulou", "Malian music, desert blues, Wassoulou, kora", 30),
            "TR" to CountryHint("Anatolian rock and arabesk", "Turkish music, Anatolian rock, arabesk, psychedelic folk", 5),
            "FR" to CountryHint("Chanson and French touch", "French music, chanson, French touch, variété", 220),
            "CO" to CountryHint("Cumbia, vallenato and champeta", "Colombian music, cumbia, vallenato, champeta", 50),
            "SN" to CountryHint("Mbalax and sabar rhythms", "Senegalese music, mbalax, sabar, Afro-jazz", 90),
            "CU" to CountryHint("Son, timba and rumba", "Cuban music, son, timba, rumba", 15),
            "ET" to CountryHint("Ethio-jazz and azmari", "Ethiopian music, Ethio-jazz, azmari, modal scales", 40),
        )

    /** Every ISO country, so the picker is not a hardcoded shortlist. */
    val countries: List<GoBeyondEntry> =
        GoBeyondGeography.all.map { country ->
            val hint = countryHints[country.code]
            GoBeyondEntry(
                title = country.name,
                subtitle = hint?.subtitle ?: "Music from ${country.name}",
                prompt = hint?.prompt ?: "${country.adjective} music",
                hue = hint?.hue ?: country.hue,
                flag = country.flag,
                aliases = country.aliases,
            )
        }

    val genres =
        listOf(
            GoBeyondEntry("Pop", "Hooks, polish and a chorus that stays", "pop music, hooks, polished chorus, radio", 330, aliases = listOf("pop music", "top 40")),
            GoBeyondEntry("Rock", "Guitars, drums and release", "rock music, guitars, drums, release", 12, aliases = listOf("rock n roll", "rock and roll")),
            GoBeyondEntry("Hip-Hop", "Beats, flow and wordplay", "hip-hop, beats, flow, wordplay", 25, aliases = listOf("hip hop", "rap", "hihop")),
            GoBeyondEntry("R&B", "Warm groove and close vocals", "R&B, warm groove, close vocals, slow jam", 285, aliases = listOf("rnb", "r and b", "rhythm and blues")),
            GoBeyondEntry("Electronic", "Synthetic rhythm and texture", "electronic music, synthetic rhythm, texture, production", 195, aliases = listOf("edm", "electronica")),
            GoBeyondEntry("House", "Four-on-the-floor and deep groove", "house music, four on the floor, deep groove, dance", 205, aliases = listOf("deep house")),
            GoBeyondEntry("Techno", "Machine pulse and forward motion", "techno, machine pulse, forward motion, club", 250, aliases = listOf("techno music")),
            GoBeyondEntry("Trance", "Big build and weightless release", "trance, big build, weightless release, synth", 215),
            GoBeyondEntry("Drum and Bass", "Fast breaks and heavy low end", "drum and bass, fast breaks, heavy bass, rave", 165, aliases = listOf("dnb", "drum n bass", "drum & bass")),
            GoBeyondEntry("Dubstep", "Wobble bass and space", "dubstep, wobble bass, space, drop", 280),
            GoBeyondEntry("Metal", "Heavy riffs and force", "metal, heavy riffs, force, double kick", 0, aliases = listOf("heavy metal")),
            GoBeyondEntry("Punk", "Fast, raw and direct", "punk, fast, raw, direct, guitars", 5, aliases = listOf("punk rock")),
            GoBeyondEntry("Hardcore", "Speed, volume and intensity", "hardcore, speed, volume, intensity", 355),
            GoBeyondEntry("Indie Rock", "Guitars outside the mainstream", "indie rock, guitars, independent, alternative", 30, aliases = listOf("indie")),
            GoBeyondEntry("Alternative Rock", "Left-field guitars and melody", "alternative rock, left-field guitars, melody", 15, aliases = listOf("alt rock")),
            GoBeyondEntry("Classic Rock", "Big rooms and bigger riffs", "classic rock, big riffs, stadium, vintage", 35),
            GoBeyondEntry("Progressive Rock", "Long form and odd meters", "progressive rock, long form, odd meters, suite", 260, aliases = listOf("prog rock", "prog")),
            GoBeyondEntry("Folk", "Acoustic stories and close harmony", "folk music, acoustic stories, close harmony, traditional", 95),
            GoBeyondEntry("Country", "Nashville storytelling and steel guitar", "country music, storytelling, steel guitar, Nashville", 40),
            GoBeyondEntry("Blues", "Blue notes and slow burn", "blues, blue notes, slow burn, guitar", 225),
            GoBeyondEntry("Jazz", "Improvisation and swing", "jazz, improvisation, swing, harmony", 38, aliases = listOf("jazz music")),
            GoBeyondEntry("Classical", "Orchestra and chamber rooms", "classical music, orchestra, chamber, strings", 45),
            GoBeyondEntry("Soul", "Deep feeling and warm voice", "soul music, deep feeling, warm voice, groove", 20),
            GoBeyondEntry("Funk", "Syncopation and pocket", "funk, syncopation, pocket, bass", 55),
            GoBeyondEntry("Disco", "Mirrorball rhythm and strings", "disco, mirrorball, rhythm, strings, dance", 300),
            GoBeyondEntry("Reggae", "Offbeat skank and roots", "reggae, offbeat skank, roots, island", 110),
            GoBeyondEntry("Ska", "Upstroke guitars and horns", "ska, upstroke guitar, horns, upbeat", 80),
            GoBeyondEntry("Latin", "Clave, percussion and movement", "Latin music, clave, percussion, movement", 50),
            GoBeyondEntry("Salsa", "Hot horns and dance floor", "salsa, hot horns, dance floor, clave", 15),
            GoBeyondEntry("Bachata", "Romantic guitar and close rhythm", "bachata, romantic guitar, close rhythm", 340),
            GoBeyondEntry("K-pop", "Korean pop with maximal production", "K-pop, Korean pop, maximal production, choreography", 315, aliases = listOf("kpop", "k pop")),
            GoBeyondEntry("J-pop", "Japanese pop melody and shine", "J-pop, Japanese pop, melody, shine", 335, aliases = listOf("jpop", "j pop")),
            GoBeyondEntry("Anime", "Opening themes and cinematic energy", "anime music, opening themes, cinematic energy", 275),
            GoBeyondEntry("Soundtrack", "Score, cue and atmosphere", "soundtrack, film score, cue, atmosphere", 200),
            GoBeyondEntry("Lo-fi", "Soft beats and study haze", "lo-fi, soft beats, study haze, chill", 185, aliases = listOf("lofi", "chillhop", "low fi")),
            GoBeyondEntry("Trap", "Hi-hats and heavy 808", "trap, hi-hats, 808, bass", 265),
            GoBeyondEntry("Drill", "Sliding bass and cold space", "drill, sliding bass, cold space, street", 240),
            GoBeyondEntry("Grime", "Sparse beats and fast bars", "grime, sparse beats, fast bars, London", 195),
            GoBeyondEntry("Gospel", "Choir, faith and lift", "gospel, choir, faith, lift", 50),
            GoBeyondEntry("Ambient", "Slow air and patient space", "ambient music, slow air, drones, patient space", 195),
            GoBeyondEntry("New Age", "Calm shimmer and open horizon", "new age, calm shimmer, open horizon, meditation", 175),
            GoBeyondEntry("Experimental", "Unknown forms and risk", "experimental music, unknown forms, risk, abstract", 290),
            GoBeyondEntry("Noise", "Texture and volume as material", "noise, texture, volume, material", 0),
            GoBeyondEntry("Industrial", "Metal, machines and grit", "industrial, metal, machines, grit", 250),
            GoBeyondEntry("Synthwave", "Neon night and analog synth", "synthwave, neon night, analog synth, retro", 305, aliases = listOf("synth wave", "retrowave", "outrun")),
            GoBeyondEntry("Vaporwave", "Mall light and slowed memory", "vaporwave, mall light, slowed memory, nostalgic", 320),
            GoBeyondEntry("Hyperpop", "Bright, fast and exaggerated", "hyperpop, bright, fast, exaggerated, digital", 310),
            GoBeyondEntry("Emo", "Big feeling and guitar", "emo, big feeling, guitar, heartfelt", 270),
            GoBeyondEntry("Screamo", "Raw voice and release", "screamo, raw voice, release, guitars", 260),
            GoBeyondEntry("Post-rock", "Long arcs and guitar weather", "post-rock, long arcs, guitar weather, crescendo", 190),
            GoBeyondEntry("Post-punk", "Sharp guitars and cold space", "post-punk, sharp guitars, cold space, spoken vocals", 250),
            GoBeyondEntry("Shoegaze", "Walls of guitar and soft vocals", "shoegaze, walls of guitar, dreamy vocals, reverb", 285, aliases = listOf("shoegaz", "shoe gaze", "shoe-gaze")),
            GoBeyondEntry("Dream pop", "Glowing chords and whispered melody", "dream pop, glowing chords, whispered melody, reverb", 300, aliases = listOf("dreampop")),
            GoBeyondEntry("City Pop", "Tokyo nightlife and glossy chords", "city pop, glossy chords, night drive, Japanese 80s", 205, aliases = listOf("citypop", "city-pop")),
            GoBeyondEntry("Afrobeat", "Long grooves and horn sections", "Afrobeat, long grooves, horn section, West African rhythm", 95),
            GoBeyondEntry("Highlife", "Bright guitars and rolling rhythm", "highlife, bright guitars, rolling rhythm, West African", 110),
            GoBeyondEntry("Amapiano", "Log drum and airy house", "amapiano, log drum, airy house, South African", 150),
            GoBeyondEntry("Cumbia", "Offbeat accordion and dance", "cumbia, offbeat accordion, dance rhythm, Latin", 45),
            GoBeyondEntry("Bossa nova", "Quiet guitar and close vocals", "bossa nova, quiet guitar, close vocals, Rio", 150, aliases = listOf("bossa")),
            GoBeyondEntry("Samba", "Percussion and communal movement", "samba, percussion, communal movement, Brazil", 130),
            GoBeyondEntry("Tango", "Drama, bandoneon and tension", "tango, drama, bandoneon, tension", 5),
            GoBeyondEntry("Flamenco", "Guitar, voice and duende", "flamenco, guitar, voice, duende", 10),
            GoBeyondEntry("Bluegrass", "Fast strings and high harmony", "bluegrass, fast strings, high harmony, banjo", 75),
            GoBeyondEntry("Jazz fusion", "Electric keys and stretched solos", "jazz fusion, electric keys, stretched solos, fusion", 38),
            GoBeyondEntry("Neo-soul", "Warm keys and lived-in vocals", "neo-soul, warm keys, lived-in vocals, groove", 25, aliases = listOf("neosoul")),
            GoBeyondEntry("Trip-hop", "Slow breakbeat and smoke", "trip-hop, slow breakbeat, smoke, cinematic", 230, aliases = listOf("triphop")),
            GoBeyondEntry("Garage", "Swing, shuffle and low end", "garage, swing, shuffle, low end", 180),
            GoBeyondEntry("Dub", "Echo, space and bass", "dub, echo, space, bass", 100),
            GoBeyondEntry("Dub techno", "Deep chords and endless echo", "dub techno, deep chords, endless echo, minimal pulse", 180),
            GoBeyondEntry("Math rock", "Twisting guitars and odd meters", "math rock, twisting guitars, odd meters, precise drums", 270),
            GoBeyondEntry("Midwest emo", "Open chords and honest voice", "midwest emo, open chords, honest voice, guitar", 230),
            GoBeyondEntry("Chiptune", "Game hardware and 8-bit melody", "chiptune, game hardware, 8-bit melody, pixel", 160),
            GoBeyondEntry("Orchestral", "Strings, brass and scale", "orchestral, strings, brass, scale, score", 40),
            GoBeyondEntry("Reggaeton", "Dembow rhythm and heat", "reggaeton, dembow rhythm, heat, dance", 35),
            GoBeyondEntry("Baile funk", "Brazilian bass and fast drums", "baile funk, Brazilian bass, fast drums, favela", 20, aliases = listOf("funk carioca")),
            GoBeyondEntry("Phonk", "Memphis samples and drift", "phonk, Memphis samples, drift, bass", 340),
            GoBeyondEntry("Breakbeat", "Broken drums and energy", "breakbeat, broken drums, energy, rave", 200),
            GoBeyondEntry("Downtempo", "Soft beats and late-night haze", "downtempo, soft beats, late-night haze, trip-hop", 230),
        )

    val colors: List<GoBeyondEntry> =
        GoBeyondColors.all.map { color ->
            GoBeyondEntry(
                title = color.name,
                subtitle = color.subtitle,
                prompt = color.prompt,
                hue = color.hue,
                aliases = color.aliases,
            )
        }

    val moods =
        listOf(
            GoBeyondEntry("Melancholic", "Beautifully blue and reflective", "melancholic, reflective, beautifully blue, slow", 215, aliases = listOf("sad", "melancholy", "down", "blue", "melancolic")),
            GoBeyondEntry("Euphoric", "Big release and bright energy", "euphoric, big release, bright energy, uplifting", 55, aliases = listOf("happy", "joy", "energetic", "party", "upbeat")),
            GoBeyondEntry("Calm", "Low pulse and soft edges", "calm, low pulse, soft edges, quiet", 170, aliases = listOf("chill", "relax", "relaxing", "peaceful", "sleep")),
            GoBeyondEntry("Focus", "Steady motion without distraction", "focus, steady motion, minimal distraction, flow", 195, aliases = listOf("study", "concentrate", "work", "flow")),
            GoBeyondEntry("Romantic", "Close, warm and intimate", "romantic, close, warm, intimate, slow dance", 340, aliases = listOf("love", "sexy", "intimate")),
            GoBeyondEntry("Nostalgic", "Old light and half-remembered rooms", "nostalgic, old light, half-remembered, warm tape", 35, aliases = listOf("old", "throwback", "memories", "memory")),
            GoBeyondEntry("Angry", "Tension, volume and release", "angry, tension, volume, release, distorted", 5, aliases = listOf("rage", "aggressive", "heavy", "mad")),
            GoBeyondEntry("Dreamy", "Floating, soft and unreal", "dreamy, floating, soft focus, unreal, reverb", 290, aliases = listOf("dream", "ethereal", "floaty", "hazy")),
            GoBeyondEntry("Playful", "Bouncy, bright and loose", "playful, bouncy, bright, loose, fun", 65, aliases = listOf("fun", "bouncy", "silly")),
            GoBeyondEntry("Moody", "Brooding and cinematic", "moody, brooding, cinematic, low light, tension", 245, aliases = listOf("dark", "brooding", "cinematic")),
        )

    private val decades = listOf(1950, 1960, 1970, 1980, 1990, 2000, 2010, 2020)

    val periods: List<GoBeyondCard> =
        decades.map { start -> periodCard(start, start + 9) }

    fun cards(section: GoBeyondSection): List<GoBeyondCard> =
        when (section) {
            GoBeyondSection.COUNTRY -> countries.map { countryCard(it) }
            GoBeyondSection.GENRE -> genres.map { genreCard(it) }
            GoBeyondSection.ARTIST -> emptyList()
            GoBeyondSection.PERIOD -> periods
            GoBeyondSection.COLOR -> colors.map { colorCard(it) }
            GoBeyondSection.MOOD -> moods.map { moodCard(it) }
            GoBeyondSection.CUSTOM -> emptyList()
        }

    fun period(fromYear: Int, toYear: Int): GoBeyondCard = periodCard(fromYear, toYear)

    fun periodLabel(fromYear: Int?, toYear: Int?): String? {
        if (fromYear == null && toYear == null) return null
        if (fromYear != null && toYear != null) {
            val start = minOf(fromYear, toYear)
            val end = maxOf(fromYear, toYear)
            if (start == end) return start.toString()
            if (start % 10 == 0 && end == start + 9) return "${start}s"
            return "$start–$end"
        }
        return if (fromYear != null) "since $fromYear" else "before $toYear"
    }

    fun artistCards(
        names: List<String>,
        photoUrls: Map<String, String?> = emptyMap(),
    ): List<GoBeyondCard> {
        val seen = HashSet<String>()
        return names
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { seen.add(it.lowercase(Locale.ROOT)) }
            .take(12)
            .map { name ->
                GoBeyondCard(
                    id = "artist-${slug(name)}",
                    section = GoBeyondSection.ARTIST,
                    title = name,
                    subtitle = "Artist deep cuts",
                    description = "Explore $name beyond the tracks you already know.",
                    selection = GoBeyondSelection(artist = name),
                    accentHue = Math.floorMod(name.hashCode(), 360),
                    semanticPrompt = "music by $name, their catalogue and albums",
                    art =
                        GoBeyondArt.Photo(
                            imageUrl = photoUrls[name],
                            hue = Math.floorMod(name.hashCode(), 360),
                        ),
                )
            }
            .toList()
    }

    fun allValueCards(): List<GoBeyondCard> =
        countries.map { countryCard(it) } +
            genres.map { genreCard(it) } +
            colors.map { colorCard(it) } +
            moods.map { moodCard(it) } +
            periods

    fun cardForValue(value: String): GoBeyondCard? =
        allValueCards().firstOrNull {
            it.title.equals(value, ignoreCase = true) ||
                it.selection.periodLabel().equals(value, ignoreCase = true)
        }

    fun countryPrompt(value: String): String =
        countries.firstOrNull { it.title.equals(value, ignoreCase = true) }?.prompt
            ?: "$value music"

    fun countryAdjective(value: String): String =
        GoBeyondGeography.byName(value)?.adjective ?: value.trim()

    fun colorPrompt(value: String): String =
        GoBeyondColors.byName(value)?.prompt ?: "$value mood and colour"

    private fun countryCard(entry: GoBeyondEntry) =
        card(
            section = GoBeyondSection.COUNTRY,
            slug = slug(entry.title),
            title = entry.title,
            subtitle = entry.subtitle,
            description = "A first route into ${entry.title}. Familiar favourites stay close, new artists lead the way.",
            selection = GoBeyondSelection(country = entry.title),
            hue = entry.hue,
            prompt = entry.prompt,
            art = GoBeyondArt.Flag(entry.flag.orEmpty(), entry.hue),
        )

    private fun genreCard(entry: GoBeyondEntry) =
        card(
            section = GoBeyondSection.GENRE,
            slug = slug(entry.title),
            title = entry.title,
            subtitle = entry.subtitle,
            description = "Start with the ${entry.title} records closest to your taste, then move outward.",
            selection = GoBeyondSelection(genre = entry.title),
            hue = entry.hue,
            prompt = entry.prompt,
            art = GoBeyondArt.Abstract(entry.hue),
        )

    private fun colorCard(entry: GoBeyondEntry) =
        card(
            section = GoBeyondSection.COLOR,
            slug = slug(entry.title),
            title = entry.title,
            subtitle = entry.subtitle,
            description = "Tracks whose sound and atmosphere share the colour ${entry.title.lowercase(Locale.ROOT)}.",
            selection = GoBeyondSelection(color = entry.title),
            hue = entry.hue,
            prompt = entry.prompt,
            art = GoBeyondArt.Color(entry.title, entry.hue),
        )

    private fun moodCard(entry: GoBeyondEntry) =
        card(
            section = GoBeyondSection.MOOD,
            slug = slug(entry.title),
            title = entry.title,
            subtitle = entry.subtitle,
            description = "A route into music that carries a ${entry.title.lowercase(Locale.ROOT)} feeling.",
            selection = GoBeyondSelection(mood = entry.title),
            hue = entry.hue,
            prompt = entry.prompt,
            art = GoBeyondArt.Mood(entry.hue),
        )

    private fun periodCard(fromYear: Int, toYear: Int): GoBeyondCard {
        val label = periodLabel(fromYear, toYear) ?: fromYear.toString()
        return card(
            section = GoBeyondSection.PERIOD,
            slug = slug(label),
            title = label,
            subtitle = "Records from $fromYear to $toYear",
            description = "Explore the $label sound without leaving the tracks that already work for you.",
            selection = GoBeyondSelection(fromYear = fromYear, toYear = toYear),
            hue = 25 + ((fromYear / 10) % 7) * 43,
            prompt = "music released between $fromYear and $toYear",
            art = GoBeyondArt.Era(label, 25 + ((fromYear / 10) % 7) * 43),
        )
    }

    private fun card(
        section: GoBeyondSection,
        slug: String,
        title: String,
        subtitle: String,
        description: String,
        selection: GoBeyondSelection,
        hue: Int,
        prompt: String,
        art: GoBeyondArt,
    ) = GoBeyondCard(
        id = "${section.name.lowercase(Locale.ROOT)}-$slug",
        section = section,
        title = title,
        subtitle = subtitle,
        description = description,
        selection = selection,
        accentHue = Math.floorMod(hue, 360),
        semanticPrompt = prompt,
        art = art,
    )

    private fun slug(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
}

object GoBeyondGateway {
    const val PREFIX = "Go Beyond · "

    fun facets(playlistName: String?): List<String> {
        val name = playlistName?.trim().orEmpty()
        if (!name.startsWith(PREFIX)) return emptyList()
        return name
            .removePrefix(PREFIX)
            .split(" · ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}

/**
 * Small URL-encoded codec for the saved custom journey. DataStore stores one
 * string per playlist id so pull-to-refresh can rebuild the exact same area.
 */
object GoBeyondSelectionCodec {
    fun encode(selection: GoBeyondSelection): String =
        buildList {
            selection.country?.let { add("country" to it) }
            selection.genre?.let { add("genre" to it) }
            selection.artist?.let { add("artist" to it) }
            selection.fromYear?.let { add("from" to it.toString()) }
            selection.toYear?.let { add("to" to it.toString()) }
            selection.color?.let { add("color" to it) }
            selection.mood?.let { add("mood" to it) }
            selection.freeText?.let { add("free" to it) }
        }.joinToString("&") { (key, value) ->
            "$key=${java.net.URLEncoder.encode(value, Charsets.UTF_8.name())}"
        }

    fun decode(text: String?): GoBeyondSelection? {
        if (text.isNullOrBlank()) return null
        val values = HashMap<String, String>()
        for (part in text.split("&")) {
            val separator = part.indexOf('=').takeIf { it > 0 } ?: return null
            val key = part.substring(0, separator)
            val value =
                runCatching {
                    java.net.URLDecoder.decode(part.substring(separator + 1), Charsets.UTF_8.name())
                }.getOrNull() ?: return null
            values[key] = value
        }
        return GoBeyondSelection(
            country = values["country"],
            genre = values["genre"],
            artist = values["artist"],
            fromYear = values["from"]?.toIntOrNull(),
            toYear = values["to"]?.toIntOrNull(),
            color = values["color"],
            mood = values["mood"],
            freeText = values["free"],
        )
    }
}
