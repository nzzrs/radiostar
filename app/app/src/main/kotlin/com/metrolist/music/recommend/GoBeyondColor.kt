/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.util.Locale

/**
 * A colour as a musical atmosphere, not a word in a song title.
 *
 * `terms` are the words sent to YouTube search when the user has no embedding
 * server. The colour name itself is deliberately absent: asking for purple
 * should surface hazy, slow, psychedelic music, not tracks literally named
 * "Purple".
 */
data class GoBeyondColorEntry(
    val name: String,
    val subtitle: String,
    val prompt: String,
    val hue: Int,
    val terms: List<String>,
    val aliases: List<String> = emptyList(),
)

object GoBeyondColors {
    val all =
        listOf(
            GoBeyondColorEntry(
                name = "Purple",
                subtitle = "Hazy, psychedelic and velvet-toned",
                prompt = "music that feels purple: hazy, psychedelic, velvet, nocturnal, dreamy, slow",
                hue = 275,
                terms = listOf("dreamy", "hazy", "psychedelic", "velvet", "nocturnal", "slow"),
                aliases = listOf("violet", "lavender", "plum", "mauve", "purpel"),
            ),
            GoBeyondColorEntry(
                name = "Blue",
                subtitle = "Cool, spacious and nocturnal",
                prompt = "music that feels blue: cool, spacious, nocturnal, melancholic, piano, rain",
                hue = 220,
                terms = listOf("cool", "spacious", "nocturnal", "melancholic", "piano", "rain"),
                aliases = listOf("azure", "navy", "blu"),
            ),
            GoBeyondColorEntry(
                name = "Indigo",
                subtitle = "Deep, introspective and after-hours",
                prompt = "music that feels indigo: deep, introspective, nocturnal, synth, after hours",
                hue = 255,
                terms = listOf("deep", "introspective", "nocturnal", "synth", "after hours"),
                aliases = listOf("indgio"),
            ),
            GoBeyondColorEntry(
                name = "Teal",
                subtitle = "Cool, aquatic and shimmering",
                prompt = "music that feels teal: cool, aquatic, shimmering, calm, glassy",
                hue = 180,
                terms = listOf("aquatic", "shimmering", "cool", "calm", "glassy"),
            ),
            GoBeyondColorEntry(
                name = "Turquoise",
                subtitle = "Bright water and clear light",
                prompt = "music that feels turquoise: aquatic, bright, clear, shimmering, open",
                hue = 175,
                terms = listOf("aquatic", "bright", "clear", "shimmering", "open"),
                aliases = listOf("turquoise", "turkish blue"),
            ),
            GoBeyondColorEntry(
                name = "Green",
                subtitle = "Earthy, organic and growing",
                prompt = "music that feels green: earthy, organic, acoustic, open air, folk",
                hue = 125,
                terms = listOf("earthy", "organic", "acoustic", "open air", "folk"),
                aliases = listOf("gren", "emerald"),
            ),
            GoBeyondColorEntry(
                name = "Yellow",
                subtitle = "Sunny, warm and bright",
                prompt = "music that feels yellow: sunny, warm, bright, playful, acoustic, golden",
                hue = 52,
                terms = listOf("sunny", "warm", "bright", "playful", "acoustic", "golden"),
                aliases = listOf("yelow", "lemon", "sunshine"),
            ),
            GoBeyondColorEntry(
                name = "Gold",
                subtitle = "Warm, luxurious and sunlit",
                prompt = "music that feels gold: warm brass, luxurious, sunlit, soul, rich",
                hue = 45,
                terms = listOf("warm brass", "luxurious", "sunlit", "soul", "rich"),
                aliases = listOf("golden", "gld"),
            ),
            GoBeyondColorEntry(
                name = "Orange",
                subtitle = "Fiery, playful and sunset",
                prompt = "music that feels orange: fiery, playful, sunset, warm, upbeat",
                hue = 25,
                terms = listOf("fiery", "playful", "sunset", "warm", "upbeat"),
                aliases = listOf("oragne", "tangerine"),
            ),
            GoBeyondColorEntry(
                name = "Red",
                subtitle = "Hot, raw and urgent",
                prompt = "music that feels red: raw, urgent, hot, saturated distortion, pulse",
                hue = 0,
                terms = listOf("raw", "urgent", "hot", "distorted", "pulse"),
                aliases = listOf("crimson", "scarlet", "rd"),
            ),
            GoBeyondColorEntry(
                name = "Pink",
                subtitle = "Soft, glowing and romantic",
                prompt = "music that feels pink: soft glow, romantic, pastel synths, tender",
                hue = 335,
                terms = listOf("soft glow", "romantic", "pastel", "synth", "tender"),
                aliases = listOf("pnk", "rose"),
            ),
            GoBeyondColorEntry(
                name = "Magenta",
                subtitle = "Vivid, neon and electric",
                prompt = "music that feels magenta: vivid, neon, electric, glossy, night",
                hue = 315,
                terms = listOf("vivid", "neon", "electric", "glossy", "night"),
                aliases = listOf("magenta", "fuchsia"),
            ),
            GoBeyondColorEntry(
                name = "Brown",
                subtitle = "Earthy, warm and dusty",
                prompt = "music that feels brown: earthy, warm, dusty, rustic, analog",
                hue = 30,
                terms = listOf("earthy", "warm", "dusty", "rustic", "analog"),
                aliases = listOf("browm", "sepia"),
            ),
            GoBeyondColorEntry(
                name = "Black",
                subtitle = "Dark, stark and minimal",
                prompt = "music that feels black: dark, stark, minimal, low light, industrial",
                hue = 260,
                terms = listOf("dark", "stark", "minimal", "low light", "industrial"),
                aliases = listOf("blak", "noir"),
            ),
            GoBeyondColorEntry(
                name = "White",
                subtitle = "Bright, airy and weightless",
                prompt = "music that feels white: bright, airy, weightless, clean space, ambient",
                hue = 200,
                terms = listOf("bright", "airy", "weightless", "clean", "ambient"),
                aliases = listOf("wite", "ivory"),
            ),
            GoBeyondColorEntry(
                name = "Grey",
                subtitle = "Muted, rainy and neutral",
                prompt = "music that feels grey: muted, rainy, neutral, overcast, quiet",
                hue = 210,
                terms = listOf("muted", "rainy", "neutral", "overcast", "quiet"),
                aliases = listOf("gray", "silver grey", "grey"),
            ),
            GoBeyondColorEntry(
                name = "Silver",
                subtitle = "Cold, metallic and futuristic",
                prompt = "music that feels silver: cold, metallic, futuristic, chrome, electronic",
                hue = 205,
                terms = listOf("cold", "metallic", "futuristic", "chrome", "electronic"),
                aliases = listOf("sliver", "chrome"),
            ),
        )

    fun byName(value: String?): GoBeyondColorEntry? {
        val normalized = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normalized.isEmpty()) return null
        return all.firstOrNull { entry ->
            entry.name.lowercase(Locale.ROOT) == normalized ||
                entry.aliases.any { it.lowercase(Locale.ROOT) == normalized }
        }
    }

    fun searchQueryTerms(entry: GoBeyondColorEntry): List<String> {
        val terms = entry.terms
        return listOf(
            terms.take(4).joinToString(" "),
            "${terms.drop(1).take(4).joinToString(" ")} music",
        ).distinct()
    }
}
