/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DjSessionUi(
    val active: Boolean = false,
    val speaking: Boolean = false,
    val persona: DjPersona = DjPersona.CHERRY,
)

/** UI state driven by the DJ announcer; no queue mutation by itself. */
object DjSessionState {
    private val mutable = MutableStateFlow(DjSessionUi())
    val state = mutable.asStateFlow()

    fun speaking(persona: DjPersona) {
        mutable.value = DjSessionUi(active = true, speaking = true, persona = persona)
    }

    fun playing(persona: DjPersona = mutable.value.persona) {
        mutable.value = DjSessionUi(active = mutable.value.active, speaking = false, persona = persona)
    }

    fun idle() {
        mutable.value = DjSessionUi()
    }
}
