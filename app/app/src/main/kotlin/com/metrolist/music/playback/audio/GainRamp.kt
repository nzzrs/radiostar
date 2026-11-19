package com.metrolist.music.playback.audio

/**
 * Short linear gain ramp. Applying a per-track normalization target in one step
 * is audible as a click or a sudden level jump; interpolating over a few
 * hundred milliseconds keeps corrections transparent.
 */
internal class GainRamp {
    private var from = 1.0
    private var to = 1.0
    private var position = 0
    private var length = 0

    fun setTarget(
        target: Double,
        samples: Int,
    ) {
        if (target == to && position >= length) return
        from = current()
        to = target
        length = samples.coerceAtLeast(0)
        position = 0
    }

    fun snapTo(target: Double) {
        from = target
        to = target
        position = 0
        length = 0
    }

    fun next(): Double {
        if (position < length) position++
        return current()
    }

    private fun current(): Double =
        if (length <= 0 || position >= length) {
            to
        } else {
            from + (to - from) * position / length
        }
}
