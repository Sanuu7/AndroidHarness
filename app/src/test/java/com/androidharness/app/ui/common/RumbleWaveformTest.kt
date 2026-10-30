package com.androidharness.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RumbleWaveformTest {

    @Test
    fun `crispest setting is one short pulse at full strength`() {
        val wave = rumbleWaveform(RumbleCue.TOOL_START, RumbleStyle(true, 100, 0))
        assertEquals(1, wave.timings.size)
        assertEquals(255, wave.amplitudes.single())
        assertTrue(wave.timings.single() <= 15)
    }

    @Test
    fun `smoother settings swell up and back down over a longer time`() {
        val crisp = rumbleWaveform(RumbleCue.TOOL_START, RumbleStyle(true, 80, 0))
        val smooth = rumbleWaveform(RumbleCue.TOOL_START, RumbleStyle(true, 80, 100))
        assertTrue(smooth.timings.sum() > crisp.timings.sum() * 4)
        val peak = smooth.amplitudes.indices.maxBy { smooth.amplitudes[it] }
        assertTrue(peak in 1 until smooth.amplitudes.lastIndex)
        assertTrue(smooth.amplitudes.first() < smooth.amplitudes[peak])
        assertTrue(smooth.amplitudes.last() < smooth.amplitudes[peak])
    }

    @Test
    fun `strength scales amplitude and cues differ in weight`() {
        val soft = rumbleWaveform(RumbleCue.TOOL_START, RumbleStyle(true, 30, 50)).amplitudes.max()
        val firm = rumbleWaveform(RumbleCue.TOOL_START, RumbleStyle(true, 90, 50)).amplitudes.max()
        val quiet = rumbleWaveform(RumbleCue.THINK, RumbleStyle(true, 90, 50)).amplitudes.max()
        assertTrue(firm > soft)
        assertTrue(firm > quiet)
    }

    @Test
    fun `a failed tool pulses twice with a silent gap`() {
        val wave = rumbleWaveform(RumbleCue.TOOL_FAILED, RumbleStyle(true, 100, 0))
        assertEquals(3, wave.timings.size)
        assertEquals(0, wave.amplitudes[1])
        assertTrue(wave.amplitudes[0] > 0 && wave.amplitudes[2] > 0)
    }

    @Test
    fun `every amplitude stays in the legal range`() {
        for (cue in RumbleCue.entries) for (strength in listOf(5, 50, 100)) for (smooth in listOf(0, 40, 100)) {
            val wave = rumbleWaveform(cue, RumbleStyle(true, strength, smooth))
            assertEquals(wave.timings.size, wave.amplitudes.size)
            assertTrue(wave.timings.all { it > 0 })
            assertTrue(wave.amplitudes.all { it in 0..255 })
        }
    }
}
