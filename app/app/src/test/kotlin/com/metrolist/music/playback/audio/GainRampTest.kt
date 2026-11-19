package com.metrolist.music.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class GainRampTest {
    @Test
    fun `ramp reaches the target after the requested samples`() {
        val ramp = GainRamp()

        ramp.setTarget(0.5, samples = 4)

        assertEquals(0.875, ramp.next(), 0.0001)
        assertEquals(0.750, ramp.next(), 0.0001)
        assertEquals(0.625, ramp.next(), 0.0001)
        assertEquals(0.500, ramp.next(), 0.0001)
        assertEquals(0.500, ramp.next(), 0.0001)
    }

    @Test
    fun `retargeting starts from the value currently being rendered`() {
        val ramp = GainRamp()
        ramp.setTarget(0.0, samples = 4)
        ramp.next()
        ramp.next()

        ramp.setTarget(1.0, samples = 2)

        assertEquals(0.75, ramp.next(), 0.0001)
        assertEquals(1.0, ramp.next(), 0.0001)
    }

    @Test
    fun `snap bypasses smoothing`() {
        val ramp = GainRamp()
        ramp.setTarget(0.2, samples = 100)

        ramp.snapTo(0.2)

        assertEquals(0.2, ramp.next(), 0.0001)
    }

    @Test
    fun `zero-length ramp jumps to the target`() {
        val ramp = GainRamp()
        ramp.setTarget(0.25, samples = 0)

        assertEquals(0.25, ramp.next(), 0.0001)
    }

    @Test
    fun `setting the same settled target keeps the current value`() {
        val ramp = GainRamp()
        ramp.snapTo(0.4)

        ramp.setTarget(0.4, samples = 10)

        assertEquals(0.4, ramp.next(), 0.0001)
    }
}
