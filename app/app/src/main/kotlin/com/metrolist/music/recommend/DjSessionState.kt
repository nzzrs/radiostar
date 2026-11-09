/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class DjSessionUi(
    val active: Boolean = false,
    val speaking: Boolean = false,
    val persona: DjPersona = DjPersona.CHERRY,
) {
    /** The DJ owns the queue order for the whole session, not just while speaking. */
    val queueEditable: Boolean get() = !active

    /** Shuffle and repeat would break the planned set, so they are hidden while the DJ is live. */
    val transportControlsAvailable: Boolean get() = !active
}

/** UI state driven by the DJ announcer; no queue mutation by itself. */
object DjSessionState {
    private val mutable = MutableStateFlow(DjSessionUi())
    val state = mutable.asStateFlow()

    private val openPlayerRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openPlayer = openPlayerRequests.asSharedFlow()

    /** Ask the activity to expand the player sheet for a new DJ session. */
    fun requestPlayerOpen() {
        openPlayerRequests.tryEmit(Unit)
    }

    fun speaking(persona: DjPersona) {
        mutable.value = DjSessionUi(active = true, speaking = true, persona = persona)
    }

    fun playing(persona: DjPersona = mutable.value.persona) {
        mutable.value = DjSessionUi(active = mutable.value.active, speaking = false, persona = persona)
    }

    fun dismissSpeaking() {
        if (mutable.value.speaking) mutable.value = mutable.value.copy(speaking = false)
    }

    fun idle() {
        mutable.value = DjSessionUi()
    }
}
