/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Why a draft persona cannot be saved, in the order the editor checks. */
enum class DjPersonaIssue {
    NAME,
    EMOJI,
    PITCH,
    RATE,
}

object DjPersonaValidation {
    fun problem(persona: DjPersona): DjPersonaIssue? {
        if (persona.displayName.isBlank()) return DjPersonaIssue.NAME
        val emoji = persona.emoji.trim()
        if (emoji.isEmpty() || emoji.codePointCount(0, emoji.length) !in 1..2) {
            return DjPersonaIssue.EMOJI
        }
        if (persona.pitch !in 0.5f..1.5f) return DjPersonaIssue.PITCH
        if (persona.rate !in 0.5f..1.5f) return DjPersonaIssue.RATE
        return null
    }
}

/** JSON persistence for custom personas; stored as one DataStore string. */
object DjPersonaCodec {
    private const val VERSION = 1

    fun encode(personas: List<DjPersona>): String {
        val array = JSONArray()
        personas.forEach { persona ->
            array.put(
                JSONObject()
                    .put("id", persona.id)
                    .put("emoji", persona.emoji)
                    .put("name", persona.displayName)
                    .put("tagline", persona.tagline)
                    .put("pitch", persona.pitch.toDouble())
                    .put("rate", persona.rate.toDouble()),
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("personas", array)
            .toString()
    }

    fun decode(raw: String?): List<DjPersona> {
        if (raw.isNullOrBlank()) return emptyList()
        val array =
            runCatching { JSONObject(raw).optJSONArray("personas") }.getOrNull()
                ?: return emptyList()
        val seen = HashSet<String>()
        val personas = mutableListOf<DjPersona>()
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val persona =
                DjPersona(
                    id = entry.optString("id").trim(),
                    emoji = entry.optString("emoji").trim(),
                    displayName = entry.optString("name").trim(),
                    tagline = entry.optString("tagline").trim(),
                    pitch = entry.optDouble("pitch", Double.NaN).toFloat(),
                    rate = entry.optDouble("rate", Double.NaN).toFloat(),
                )
            if (persona.id.isEmpty() || !seen.add(persona.id.lowercase(Locale.ROOT))) continue
            if (DjPersona.find(persona.id) != null) continue
            if (DjPersonaValidation.problem(persona) != null) continue
            personas.add(persona)
        }
        return personas
    }
}

/** Built-in and custom personas plus the selected id, ready for the UI. */
data class DjPersonaLibrary(
    val custom: List<DjPersona> = emptyList(),
    val selectedId: String = DjPersona.CHERRY.id,
) {
    val all: List<DjPersona>
        get() = DjPersona.builtins + custom

    val selected: DjPersona
        get() = resolve(selectedId)

    fun resolve(id: String?): DjPersona {
        val wanted = id?.trim().orEmpty()
        if (wanted.isEmpty()) return DjPersona.CHERRY
        return DjPersona.find(wanted)
            ?: custom.firstOrNull { it.id.equals(wanted, ignoreCase = true) }
            ?: DjPersona.CHERRY
    }

    fun select(persona: DjPersona): DjPersonaLibrary = copy(selectedId = persona.id)

    fun upsert(persona: DjPersona): DjPersonaLibrary {
        val index = custom.indexOfFirst { it.id == persona.id }
        val personas =
            if (index < 0) {
                custom + persona
            } else {
                custom.toMutableList().apply { set(index, persona) }
            }
        return copy(custom = personas, selectedId = persona.id)
    }

    fun remove(id: String): DjPersonaLibrary =
        copy(
            custom = custom.filterNot { it.id == id },
            selectedId = if (selectedId == id) DjPersona.CHERRY.id else selectedId,
        )

    companion object {
        /**
         * Reads both preference values, migrating the old enum name
         * (`LEMON`) to the persona id (`lemon`) and falling back to Cherry
         * when nothing matches.
         */
        fun from(
            rawCustom: String?,
            rawSelected: String?,
        ): DjPersonaLibrary {
            val library =
                DjPersonaLibrary(
                    custom = DjPersonaCodec.decode(rawCustom),
                    selectedId = rawSelected?.trim().orEmpty(),
                )
            return library.copy(selectedId = library.selected.id)
        }
    }
}
