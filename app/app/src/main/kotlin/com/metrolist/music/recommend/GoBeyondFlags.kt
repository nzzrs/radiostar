/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import java.util.Locale

/**
 * Flag artwork source. A real flag image is used instead of the emoji so the
 * card can crop, tint and layer it like artwork.
 */
object GoBeyondFlags {
    private const val BASE = "https://flagcdn.com/w320/"

    fun url(code: String): String {
        val value = code.trim().lowercase(Locale.ROOT)
        if (value.length != 2 || value.any { !it.isLetter() }) return ""
        return BASE + value + ".png"
    }
}
