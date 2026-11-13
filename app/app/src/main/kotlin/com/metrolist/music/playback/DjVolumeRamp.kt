/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.os.Handler
import com.metrolist.music.recommend.DjPlaybackPolicy

/**
 * Steps the player volume between two levels instead of jumping.
 *
 * The ramp runs on the main handler in small linear steps (~600ms in total),
 * so ducking and restoring the music stay inaudible. Crossfades and session
 * changes cancel it; the next caller always ramps from the current volume.
 */
class DjVolumeRamp(
    private val handler: Handler,
    private val applyVolume: (Float) -> Unit,
) {
    private var pending: Runnable? = null
    private var generation = 0

    fun rampTo(
        from: Float,
        to: Float,
        durationMs: Long = DURATION_MS,
        onDone: (() -> Unit)? = null,
    ) {
        cancel()
        val values = stepValues(from, to)
        if (durationMs <= 0L || values.size <= 1) {
            applyVolume(values.last())
            onDone?.invoke()
            return
        }
        val token = generation
        val stepMs = (durationMs / (values.size - 1)).coerceAtLeast(1L)
        applyVolume(values.first())
        scheduleStep(token, values, 1, stepMs, onDone)
    }

    /** Stops the ramp where it is; the volume does not jump. */
    fun cancel() {
        generation++
        pending?.let(handler::removeCallbacks)
        pending = null
    }

    private fun scheduleStep(
        token: Int,
        values: List<Float>,
        index: Int,
        stepMs: Long,
        onDone: (() -> Unit)?,
    ) {
        if (index >= values.size) {
            onDone?.invoke()
            return
        }
        val runnable =
            object : Runnable {
                override fun run() {
                    if (token != generation) return
                    pending = null
                    applyVolume(values[index])
                    scheduleStep(token, values, index + 1, stepMs, onDone)
                }
            }
        pending = runnable
        handler.postDelayed(runnable, stepMs)
    }

    companion object {
        const val DURATION_MS: Long = 600L
        const val STEPS: Int = 12

        fun duckedTarget(baseVolume: Float): Float = DjPlaybackPolicy.duckedVolume(baseVolume)

        /**
         * Linear volume values from [from] to [to], inclusive. Both ends are
         * clamped to the valid player range and the last value is exact.
         */
        fun stepValues(
            from: Float,
            to: Float,
            steps: Int = STEPS,
        ): List<Float> {
            val start = from.coerceIn(0f, 1f)
            val end = to.coerceIn(0f, 1f)
            if (steps <= 0) return listOf(end)
            return (0..steps).map { index ->
                when (index) {
                    0 -> start
                    steps -> end
                    else -> start + (end - start) * (index.toFloat() / steps)
                }
            }
        }
    }
}
