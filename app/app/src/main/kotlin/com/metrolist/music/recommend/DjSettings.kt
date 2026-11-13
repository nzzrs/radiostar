/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.util.Locale

enum class DjPresence {
    NEVER,
    SOMETIMES,
    FREQUENT,
}

data class DjPersona(
    val id: String,
    val emoji: String,
    val displayName: String,
    val tagline: String,
    val pitch: Float,
    val rate: Float,
    val builtin: Boolean = false,
) {
    /** Display name without the "DJ " prefix, as spoken in a link. */
    val shortName: String
        get() = displayName.removePrefix("DJ ").trim()

    /** Personality handed to the AI prompt; custom personas may leave it empty. */
    val promptStyle: String
        get() = tagline.trim().ifBlank { "curious, warm and concise" }

    companion object {
        val CHERRY =
            DjPersona(
                id = "cherry",
                emoji = "🍒",
                displayName = "DJ Cherry",
                tagline = "Sweet, curious and always up for a detour.",
                pitch = 1.02f,
                rate = 1.0f,
                builtin = true,
            )
        val LEMON =
            DjPersona(
                id = "lemon",
                emoji = "🍋",
                displayName = "DJ Lemon",
                tagline = "Bright, sharp and a little acid.",
                pitch = 1.12f,
                rate = 1.06f,
                builtin = true,
            )
        val BERRY =
            DjPersona(
                id = "berry",
                emoji = "🫐",
                displayName = "DJ Berry",
                tagline = "Soft-spoken crate digger with a mellow streak.",
                pitch = 0.94f,
                rate = 0.94f,
                builtin = true,
            )
        val BANANA =
            DjPersona(
                id = "banana",
                emoji = "🍌",
                displayName = "DJ Banana",
                tagline = "Goofy, warm and impossible to rush.",
                pitch = 0.88f,
                rate = 0.9f,
                builtin = true,
            )
        val STRAWBERRY =
            DjPersona(
                id = "strawberry",
                emoji = "🍓",
                displayName = "DJ Strawberry",
                tagline = "Romantic hooks and bright choruses.",
                pitch = 1.08f,
                rate = 1.03f,
                builtin = true,
            )
        val COCO =
            DjPersona(
                id = "coco",
                emoji = "🥥",
                displayName = "DJ Coco",
                tagline = "Calm, deep and a bit mysterious.",
                pitch = 0.84f,
                rate = 0.88f,
                builtin = true,
            )

        val builtins: List<DjPersona> =
            listOf(CHERRY, LEMON, BERRY, BANANA, STRAWBERRY, COCO)

        fun find(id: String?): DjPersona? =
            id
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { wanted -> builtins.firstOrNull { it.id.equals(wanted, ignoreCase = true) } }

        /** A stable, readable id for a new custom persona. */
        fun newCustomId(
            displayName: String,
            existingIds: Collection<String>,
        ): String {
            val slug =
                displayName
                    .trim()
                    .lowercase(Locale.ROOT)
                    .replace(Regex("[^a-z0-9]+"), "-")
                    .trim('-')
                    .ifEmpty { "dj" }
            val base = "custom-$slug"
            if (base !in existingIds) return base
            var suffix = 2
            while ("$base-$suffix" in existingIds) suffix++
            return "$base-$suffix"
        }
    }
}

data class DjConfig(
    val persona: DjPersona = DjPersona.CHERRY,
    val known: DjPresence = DjPresence.SOMETIMES,
    val far: DjPresence = DjPresence.NEVER,
    val likely: DjPresence = DjPresence.FREQUENT,
)
