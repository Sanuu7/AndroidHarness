package com.androidharness.app.ui.common

import com.androidharness.app.data.RumbleCue
import com.androidharness.app.data.RumbleCueConfig
import com.androidharness.app.data.RumblePattern
import com.androidharness.app.data.configFor
import com.androidharness.app.data.decodeRumbleCues
import com.androidharness.app.data.encodeRumbleCues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RumbleWaveformTest {

    private fun wave(pattern: RumblePattern, strength: Int = 100, smooth: Int = 0) =
        rumbleWaveform(pattern, strength, smooth)!!

    @Test
    fun `crispest click is one short pulse at full strength`() {
        val w = wave(RumblePattern.CLICK)
        assertEquals(1, w.timings.size)
        assertEquals(255, w.amplitudes.single())
        assertTrue(w.timings.single() <= 15)
    }

    @Test
    fun `off has no waveform`() {
        assertNull(rumbleWaveform(RumblePattern.OFF, 100, 50))
    }

    @Test
    fun `smoother settings swell up and back down over a longer time`() {
        val crisp = wave(RumblePattern.CLICK, 80, 0)
        val smooth = wave(RumblePattern.CLICK, 80, 100)
        assertTrue(smooth.timings.sum() > crisp.timings.sum() * 4)
        val peak = smooth.amplitudes.indices.maxBy { smooth.amplitudes[it] }
        assertTrue(peak in 1 until smooth.amplitudes.lastIndex)
    }

    @Test
    fun `rise only climbs while swell comes back down`() {
        val rise = wave(RumblePattern.RISE, 100, 100).amplitudes
        assertTrue(rise.toList().zipWithNext().all { (a, b) -> b >= a })
        val swell = wave(RumblePattern.SWELL, 100, 0).amplitudes
        assertTrue(swell.size > 2 && swell.last() < swell.max())
    }

    @Test
    fun `multi pulse patterns separate their pulses with silence`() {
        val double = wave(RumblePattern.DOUBLE_CLICK)
        assertEquals(3, double.timings.size)
        assertEquals(0, double.amplitudes[1])
        val triple = wave(RumblePattern.TRIPLE_TICK)
        assertEquals(5, triple.timings.size)
        assertEquals(listOf(0, 0), listOf(triple.amplitudes[1], triple.amplitudes[3]))
    }

    @Test
    fun `thud lasts longer than a tick and strength scales amplitude`() {
        assertTrue(wave(RumblePattern.THUD).timings.sum() > wave(RumblePattern.TICK).timings.sum())
        assertTrue(wave(RumblePattern.CLICK, 90).amplitudes.max() > wave(RumblePattern.CLICK, 30).amplitudes.max())
        assertTrue(wave(RumblePattern.SOFT_TICK).amplitudes.max() < wave(RumblePattern.CLICK).amplitudes.max())
    }

    @Test
    fun `every pattern keeps legal timings and amplitudes`() {
        for (pattern in RumblePattern.entries) for (strength in listOf(1, 50, 100)) for (smooth in listOf(0, 40, 100)) {
            val w = rumbleWaveform(pattern, strength, smooth) ?: continue
            assertEquals(w.timings.size, w.amplitudes.size)
            assertTrue(w.timings.all { it > 0 })
            assertTrue(w.amplitudes.all { it in 0..255 })
        }
    }

    @Test
    fun `system steps swap sharp primitives for soft ones when smooth`() {
        assertEquals(PRIMITIVE_CLICK, systemSteps(RumblePattern.CLICK, 10).single().primitive)
        assertEquals(PRIMITIVE_LOW_TICK, systemSteps(RumblePattern.CLICK, 90).single().primitive)
        assertEquals(PRIMITIVE_TICK, systemSteps(RumblePattern.TICK, 0).single().primitive)
        assertEquals(3, systemSteps(RumblePattern.TRIPLE_TICK, 0).size)
        assertEquals(listOf(PRIMITIVE_QUICK_RISE, PRIMITIVE_QUICK_FALL), systemSteps(RumblePattern.SWELL, 50).map { it.primitive })
        assertTrue(systemSteps(RumblePattern.OFF, 50).isEmpty())
    }

    @Test
    fun `only changed cues are stored and unknown ones are ignored`() {
        val saved = mapOf("TOOL_START" to RumbleCueConfig(RumblePattern.THUD, 40))
        val round = decodeRumbleCues(encodeRumbleCues(saved))
        assertEquals(RumbleCueConfig(RumblePattern.THUD, 40), round.configFor(RumbleCue.TOOL_START))
        assertEquals(RumbleCueConfig(RumbleCue.THINK.defaultPattern, RumbleCue.THINK.defaultLevel), round.configFor(RumbleCue.THINK))
        assertNotNull(decodeRumbleCues("not json"))
        assertTrue(decodeRumbleCues("""{"GONE":{"pattern":"TICK","level":10}}""").isEmpty())
        assertEquals(100, decodeRumbleCues("""{"THINK":{"pattern":"TICK","level":900}}""").getValue("THINK").level)
    }
}
