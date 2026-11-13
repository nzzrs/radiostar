/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.metrolist.music.constants.RadioStarDjPersonaKey
import com.metrolist.music.constants.RadioStarDjPersonasKey
import com.metrolist.music.recommend.DjPersona
import com.metrolist.music.recommend.DjPersonaCodec
import com.metrolist.music.recommend.DjPersonaLibrary
import com.metrolist.music.utils.rememberPreference

/** Writes the persona library back to DataStore and keeps the id normalized. */
@Stable
class DjPersonaPreferences internal constructor(
    val library: DjPersonaLibrary,
    private val update: (DjPersonaLibrary) -> Unit,
) {
    fun select(persona: DjPersona) = update(library.select(persona))

    fun save(persona: DjPersona) = update(library.upsert(persona))

    fun delete(persona: DjPersona) = update(library.remove(persona.id))
}

@Composable
fun rememberDjPersonaPreferences(): DjPersonaPreferences {
    var rawCustom by rememberPreference(RadioStarDjPersonasKey, "")
    var selectedId by rememberPreference(RadioStarDjPersonaKey, DjPersona.CHERRY.id)
    val library = remember(rawCustom, selectedId) { DjPersonaLibrary.from(rawCustom, selectedId) }

    LaunchedEffect(selectedId, library.selectedId) {
        if (selectedId != library.selectedId) selectedId = library.selectedId
    }

    return remember(library) {
        DjPersonaPreferences(library) { updated ->
            val encoded = DjPersonaCodec.encode(updated.custom)
            if (encoded != rawCustom) rawCustom = encoded
            if (updated.selectedId != selectedId) selectedId = updated.selectedId
        }
    }
}
