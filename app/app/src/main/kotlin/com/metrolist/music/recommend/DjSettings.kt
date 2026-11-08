/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

enum class DjPresence {
    NEVER,
    SOMETIMES,
    FREQUENT,
}

enum class DjPersona(
    val emoji: String,
    val displayName: String,
    val tagline: String,
    val pitch: Float,
    val rate: Float,
) {
    CHERRY("🍒", "DJ Cherry", "Sweet, curious and always up for a detour.", 1.02f, 1.0f),
    LEMON("🍋", "DJ Lemon", "Bright, sharp and a little acid.", 1.12f, 1.06f),
    BERRY("🫐", "DJ Berry", "Soft-spoken crate digger with a mellow streak.", 0.94f, 0.94f),
    BANANA("🍌", "DJ Banana", "Goofy, warm and impossible to rush.", 0.88f, 0.9f),
    STRAWBERRY("🍓", "DJ Strawberry", "Romantic hooks and bright choruses.", 1.08f, 1.03f),
    COCO("🥥", "DJ Coco", "Calm, deep and a bit mysterious.", 0.84f, 0.88f),
}

data class DjConfig(
    val persona: DjPersona = DjPersona.CHERRY,
    val known: DjPresence = DjPresence.SOMETIMES,
    val far: DjPresence = DjPresence.NEVER,
    val likely: DjPresence = DjPresence.FREQUENT,
)
