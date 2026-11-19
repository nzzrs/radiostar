package com.metrolist.music.playback

/**
 * Audio-focus events mapped from [android.media.AudioManager] so the duck policy
 * stays Android-free and unit-testable.
 */
internal enum class AudioFocusEvent {
    GAIN,
    GAIN_TRANSIENT,
    GAIN_TRANSIENT_MAY_DUCK,
    LOSS,
    LOSS_TRANSIENT,
    LOSS_TRANSIENT_CAN_DUCK,
}

/**
 * Bounded ducking state machine.
 *
 * A transient duck is only safe while the other app actually holds focus. If the
 * matching GAIN event is missed (common when the app is backgrounded), the
 * player would otherwise stay at [duckMultiplier] forever. The deadline makes
 * that state recoverable without needing another focus callback.
 */
internal class AudioFocusPolicy(
    private val duckMultiplier: Float = 0.2f,
    val maxDuckMs: Long = 25_000L,
) {
    var hasFocus: Boolean = false
        private set

    var isDucking: Boolean = false
        private set

    var volumeMultiplier: Float = 1f
        private set

    private var duckDeadlineMs = 0L

    fun onEvent(
        event: AudioFocusEvent,
        nowMs: Long,
    ) {
        when (event) {
            AudioFocusEvent.GAIN,
            AudioFocusEvent.GAIN_TRANSIENT,
            AudioFocusEvent.GAIN_TRANSIENT_MAY_DUCK,
            AudioFocusEvent.LOSS_TRANSIENT_CAN_DUCK,
            -> hasFocus = true

            AudioFocusEvent.LOSS,
            AudioFocusEvent.LOSS_TRANSIENT,
            -> hasFocus = false
        }

        if (event == AudioFocusEvent.LOSS_TRANSIENT_CAN_DUCK) {
            isDucking = true
            volumeMultiplier = duckMultiplier
            duckDeadlineMs = nowMs + maxDuckMs
        } else {
            restore()
        }
    }

    /**
     * Restores the volume once the duck deadline has passed.
     * @return true when this call ended the duck and volume must be reapplied.
     */
    fun expireDuck(nowMs: Long): Boolean {
        if (!isDucking || nowMs < duckDeadlineMs) return false
        restore()
        return true
    }

    fun onFocusRequestResult(granted: Boolean) {
        hasFocus = granted
        // A fresh grant proves the previous duck no longer applies.
        if (granted) restore()
    }

    fun onAbandoned() {
        hasFocus = false
        restore()
    }

    private fun restore() {
        isDucking = false
        volumeMultiplier = 1f
        duckDeadlineMs = 0L
    }
}
