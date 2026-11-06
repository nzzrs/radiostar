/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

/**
 * Asynchronous audio embedder.
 *
 * [submit] must return immediately: it is called from the tap worker while
 * playback is running. Implementations own a single worker and drop windows
 * instead of queueing without bound.
 */
interface ClapEmbedder : AutoCloseable {
    fun submit(window: FloatArray, sampleRate: Int, onResult: (FloatArray) -> Unit)

    val droppedWindows: Long
}
