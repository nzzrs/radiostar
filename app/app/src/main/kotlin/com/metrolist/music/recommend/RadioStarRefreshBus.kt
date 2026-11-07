/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import kotlinx.coroutines.flow.MutableSharedFlow

/** Tells Home that imported listens changed and playlists should rebuild now. */
object RadioStarRefreshBus {
    val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun request() {
        requests.tryEmit(Unit)
    }
}
