/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the last three slates so Home can show them and the service can play
 * one on demand. In memory only: after a process restart the shelf is empty
 * until playback produces a new analysis.
 */
object RadioStarMixes {
    private val mutableCards = MutableStateFlow<List<MixCard>>(emptyList())
    val cards: StateFlow<List<MixCard>> = mutableCards.asStateFlow()

    @Volatile
    private var slates: Map<MixKind, List<SlateEntry>> = emptyMap()

    @Volatile
    private var currentSeed: String? = null

    fun publish(slates: Map<MixKind, List<SlateEntry>>, seedTitle: String?) {
        this.slates = slates
        currentSeed = seedTitle
        mutableCards.value = MixCatalog.build(slates, seedTitle)
    }

    fun entries(kind: MixKind): List<SlateEntry> = slates[kind].orEmpty()

    fun clear() {
        slates = emptyMap()
        currentSeed = null
        mutableCards.value = emptyList()
    }
}
