/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

/**
 * Guards the music ducking: the DJ may only lower the track once its own audio
 * is actually audible. An attempt that never starts (failed synthesis, dead
 * TTS engine) must never touch the current song.
 */
class DjDuckGate {
    @Volatile
    var ducked: Boolean = false
        private set

    /** Returns true when the caller must duck now (the first audible frame). */
    fun onPlaybackStarted(): Boolean {
        if (ducked) return false
        ducked = true
        return true
    }

    /** Returns true when the caller must restore the music. */
    fun onPlaybackStopped(): Boolean {
        if (!ducked) return false
        ducked = false
        return true
    }

    fun reset() {
        ducked = false
    }
}
