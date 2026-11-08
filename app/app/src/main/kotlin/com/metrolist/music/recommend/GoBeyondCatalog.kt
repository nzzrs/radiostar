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

    /**
     * Search phrases are deliberately narrow first, then wider. A blank first
     * result must not make the card unusable, so the period always has its own
     * fallback and the raw facets always have a generic one.
     */
    fun searchQueries(): List<String> {
        val era = periodLabel()
        val core =
            listOfNotNull(artist, genre, country, color, mood)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .ifBlank { freeText?.trim().orEmpty() }

        val queries = ArrayList<String>(3)
        if (core.isNotBlank()) {
            queries += listOf(core, era).filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
            queries += if (artist != null) {
                "artists similar to ${artist.trim()} ${listOfNotNull(genre, country).joinToString(" ")}".trim()
            } else {
                "$core music"
            }
            country?.let { place ->
                val adjective = GoBeyondCatalog.countryAdjective(place)
                queries +=
                    if (genre != null) {
                        "$adjective ${genre.trim()}"
                    } else {
                        "$adjective music"
                    }
            }
        }
        era?.let { queries += "$it music" }
        val descriptor =
            when {
                color != null -> GoBeyondCatalog.colorPrompt(color)
                mood != null ->
                    GoBeyondCatalog.moods
                        .firstOrNull { it.title.equals(mood, ignoreCase = true) }
                        ?.prompt
                else -> null
            }
        descriptor
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.take(4)
            ?.joinToString(" ")
            ?.takeIf { it.isNotBlank() }
            ?.let { queries += it }
        if (queries.isEmpty()) queries += "music"
        return queries
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

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
            parts += "${it.lowercase(Locale.ROOT)}: ${GoBeyondCatalog.colorPrompt(it)}"
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
)

object GoBeyondCatalog {
    val sections: List<GoBeyondSection> = GoBeyondSection.entries

    val countries =
        listOf(
            GoBeyondEntry("Brazil", "Samba, bossa nova and modern MPB", "Brazilian music, samba, bossa nova, MPB", 145),
            GoBeyondEntry("Japan", "City pop, enka and math rock", "Japanese music, city pop, enka, math rock", 10),
            GoBeyondEntry("Nigeria", "Afrobeat, highlife and alté", "Nigerian music, Afrobeat, highlife, alté", 120),
            GoBeyondEntry("South Korea", "K-indie, trot and hip-hop", "South Korean music, K-indie, trot, Korean hip-hop", 210),
            GoBeyondEntry("India", "Filmi, ghazal and indie", "Indian music, filmi, ghazal, Indian indie", 35),
            GoBeyondEntry("Mexico", "Cumbia, norteño and música Mexicana", "Mexican music, cumbia, norteño, música Mexicana", 20),
            GoBeyondEntry("Jamaica", "Reggae, dancehall and dub", "Jamaican music, reggae, dancehall, dub", 100),
            GoBeyondEntry("Iceland", "Post-rock and dream pop", "Icelandic music, post-rock, dream pop, glacial ambience", 190),
            GoBeyondEntry("Mali", "Desert blues and Wassoulou", "Malian music, desert blues, Wassoulou, kora", 30),
            GoBeyondEntry("Turkey", "Anatolian rock and arabesk", "Turkish music, Anatolian rock, arabesk, psychedelic folk", 5),
            GoBeyondEntry("France", "Chanson and French touch", "French music, chanson, French touch, variété", 220),
            GoBeyondEntry("Colombia", "Cumbia, vallenato and champeta", "Colombian music, cumbia, vallenato, champeta", 50),
            GoBeyondEntry("Senegal", "Mbalax and sabar rhythms", "Senegalese music, mbalax, sabar, Afro-jazz", 90),
            GoBeyondEntry("Cuba", "Son, timba and rumba", "Cuban music, son, timba, rumba", 15),
            GoBeyondEntry("Ethiopia", "Ethio-jazz and azmari", "Ethiopian music, Ethio-jazz, azmari, modal scales", 40),
        )

    val genres =
        listOf(
            GoBeyondEntry("Shoegaze", "Walls of guitar and soft vocals", "shoegaze, walls of guitar, dreamy vocals, reverb", 285),
            GoBeyondEntry("City Pop", "Tokyo nightlife and glossy chords", "city pop, glossy chords, night drive, Japanese 80s", 205),
            GoBeyondEntry("Afrobeat", "Long grooves and horn sections", "Afrobeat, long grooves, horn section, West African rhythm", 95),
            GoBeyondEntry("Ambient", "Slow air and patient space", "ambient music, slow air, drones, patient space", 195),
            GoBeyondEntry("Post-punk", "Sharp guitars and cold space", "post-punk, sharp guitars, cold space, spoken vocals", 250),
            GoBeyondEntry("Highlife", "Bright guitars and rolling rhythm", "highlife, bright guitars, rolling rhythm, West African", 110),
            GoBeyondEntry("Cumbia", "Offbeat accordion and dance", "cumbia, offbeat accordion, dance rhythm, Latin", 45),
            GoBeyondEntry("Jazz fusion", "Electric keys and stretched solos", "jazz fusion, electric keys, stretched solos, fusion", 38),
            GoBeyondEntry("Bossa nova", "Quiet guitar and close vocals", "bossa nova, quiet guitar, close vocals, Rio", 150),
            GoBeyondEntry("Downtempo", "Soft beats and late-night haze", "downtempo, soft beats, late-night haze, trip-hop", 230),
            GoBeyondEntry("Dream pop", "Glowing chords and whispered melody", "dream pop, glowing chords, whispered melody, reverb", 300),
            GoBeyondEntry("Bluegrass", "Fast strings and high harmony", "bluegrass, fast strings, high harmony, banjo", 75),
            GoBeyondEntry("Dub techno", "Deep chords and endless echo", "dub techno, deep chords, endless echo, minimal pulse", 180),
            GoBeyondEntry("Math rock", "Twisting guitars and odd meters", "math rock, twisting guitars, odd meters, precise drums", 270),
            GoBeyondEntry("Neo-soul", "Warm keys and lived-in vocals", "neo-soul, warm keys, lived-in vocals, groove", 25),
        )

    val colors =
        listOf(
            GoBeyondEntry("Purple", "Hazy, psychedelic and velvet-toned", "deep purple, velvet synths, psychedelic haze, slow tempo", 275),
            GoBeyondEntry("Blue", "Cool, spacious and nocturnal", "blue, cool space, nocturnal, blue-note melancholy", 220),
            GoBeyondEntry("Red", "Hot, raw and urgent", "red, raw, urgent, saturated distortion, pulse", 0),
            GoBeyondEntry("Green", "Earthy, organic and growing", "green, earthy, organic, acoustic textures, open air", 125),
            GoBeyondEntry("Gold", "Warm, luxurious and sunlit", "gold, warm brass, luxurious, sunlit soul", 45),
            GoBeyondEntry("Orange", "Fiery, playful and sunset", "orange, fiery, playful, sunset warmth", 25),
            GoBeyondEntry("Pink", "Soft, glowing and romantic", "pink, soft glow, romantic, pastel synths", 335),
            GoBeyondEntry("Black", "Dark, stark and minimal", "black, dark, stark, minimal, low light", 260),
            GoBeyondEntry("White", "Bright, airy and weightless", "white, bright, airy, weightless, clean space", 200),
            GoBeyondEntry("Silver", "Cold, metallic and futuristic", "silver, cold, metallic, futuristic, chrome", 205),
        )

    val moods =
        listOf(
            GoBeyondEntry("Melancholic", "Beautifully blue and reflective", "melancholic, reflective, beautifully blue, slow", 215),
            GoBeyondEntry("Euphoric", "Big release and bright energy", "euphoric, big release, bright energy, uplifting", 55),
            GoBeyondEntry("Calm", "Low pulse and soft edges", "calm, low pulse, soft edges, quiet", 170),
            GoBeyondEntry("Focus", "Steady motion without distraction", "focus, steady motion, minimal distraction, flow", 195),
            GoBeyondEntry("Romantic", "Close, warm and intimate", "romantic, close, warm, intimate, slow dance", 340),
            GoBeyondEntry("Nostalgic", "Old light and half-remembered rooms", "nostalgic, old light, half-remembered, warm tape", 35),
            GoBeyondEntry("Angry", "Tension, volume and release", "angry, tension, volume, release, distorted", 5),
            GoBeyondEntry("Dreamy", "Floating, soft and unreal", "dreamy, floating, soft focus, unreal, reverb", 290),
            GoBeyondEntry("Playful", "Bouncy, bright and loose", "playful, bouncy, bright, loose, fun", 65),
            GoBeyondEntry("Moody", "Brooding and cinematic", "moody, brooding, cinematic, low light, tension", 245),
        )

    val defaultArtists =
        listOf(
            "Fela Kuti",
            "Cocteau Twins",
            "Ryuichi Sakamoto",
            "SAULT",
            "Khruangbin",
            "Björk",
            "Caetano Veloso",
            "Nusrat Fateh Ali Khan",
            "Massive Attack",
            "Stereolab",
            "Alice Coltrane",
            "King Tubby",
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

    fun artistCards(names: List<String>): List<GoBeyondCard> {
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
                    semanticPrompt = "music by $name and artists from the same scene",
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
        when (value.trim().lowercase(Locale.ROOT)) {
            "brazil" -> "Brazilian"
            "japan" -> "Japanese"
            "nigeria" -> "Nigerian"
            "south korea" -> "South Korean"
            "india" -> "Indian"
            "mexico" -> "Mexican"
            "jamaica" -> "Jamaican"
            "iceland" -> "Icelandic"
            "mali" -> "Malian"
            "turkey" -> "Turkish"
            "france" -> "French"
            "colombia" -> "Colombian"
            "senegal" -> "Senegalese"
            "cuba" -> "Cuban"
            "ethiopia" -> "Ethiopian"
            else -> value.trim()
        }

    fun colorPrompt(value: String): String =
        colors.firstOrNull { it.title.equals(value, ignoreCase = true) }?.prompt
            ?: "$value mood and colour"

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
    ) = GoBeyondCard(
        id = "${section.name.lowercase(Locale.ROOT)}-$slug",
        section = section,
        title = title,
        subtitle = subtitle,
        description = description,
        selection = selection,
        accentHue = Math.floorMod(hue, 360),
        semanticPrompt = prompt,
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
