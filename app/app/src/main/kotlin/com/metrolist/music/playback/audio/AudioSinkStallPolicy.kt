package com.metrolist.music.playback.audio

/**
 * Detects an audio sink that is still "playing" while the processor chain has
 * stopped receiving PCM. ExoPlayer can keep its clock and timeline advancing on
 * some OEMs after the AudioTrack was invalidated (app switch, screen off) while
 * no sound is produced. Recovery is rate-limited: once per track, plus a
 * cooldown so that a permanently broken sink cannot turn into a seek loop.
 */
internal class AudioSinkStallPolicy(
    private val stallTimeoutMs: Long = 4_000L,
    private val recoveryCooldownMs: Long = 30_000L,
) {
    enum class Decision { NONE, RECOVER }

    private var active = false
    private var lastTrackKey: String? = null
    private var lastFrames = 0L
    private var lastProgressAtMs = 0L
    private var lastRecoveryAtMs = Long.MIN_VALUE
    private var recoveredTrackKey: String? = null

    fun evaluate(
        nowMs: Long,
        eligible: Boolean,
        framesDelivered: Long,
        trackKey: String?,
    ): Decision {
        if (!eligible) {
            active = false
            return Decision.NONE
        }

        if (!active || trackKey != lastTrackKey) {
            active = true
            lastTrackKey = trackKey
            lastFrames = framesDelivered
            lastProgressAtMs = nowMs
            return Decision.NONE
        }

        if (framesDelivered != lastFrames) {
            lastFrames = framesDelivered
            lastProgressAtMs = nowMs
            return Decision.NONE
        }

        if (nowMs - lastProgressAtMs < stallTimeoutMs) return Decision.NONE
        if (recoveredTrackKey == trackKey) return Decision.NONE
        if (lastRecoveryAtMs != Long.MIN_VALUE &&
            nowMs - lastRecoveryAtMs < recoveryCooldownMs
        ) {
            return Decision.NONE
        }

        recoveredTrackKey = trackKey
        lastRecoveryAtMs = nowMs
        lastProgressAtMs = nowMs
        return Decision.RECOVER
    }

    /** Call on track transitions and player errors so the next stall is fresh. */
    fun reset() {
        active = false
        lastTrackKey = null
        lastFrames = 0L
        lastProgressAtMs = 0L
        recoveredTrackKey = null
    }
}
