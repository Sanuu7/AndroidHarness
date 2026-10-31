package com.androidharness.app.ui.common

import com.androidharness.app.data.RumbleCue
import com.androidharness.app.data.RumbleCueConfig
import com.androidharness.app.data.RumblePattern
import com.androidharness.app.data.RUMBLE_COMBO_COUNT
import com.androidharness.app.data.RUMBLE_DEFAULT_COMBO
import com.androidharness.app.data.configFor
import com.androidharness.app.data.rumbleComboConfig
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
        assertEquals(rumbleComboConfig(RUMBLE_DEFAULT_COMBO, RumbleCue.THINK), round.configFor(RumbleCue.THINK))
        assertEquals(rumbleComboConfig(3, RumbleCue.THINK), round.configFor(RumbleCue.THINK, 3))
        assertNotNull(decodeRumbleCues("not json"))
        assertTrue(decodeRumbleCues("""{"GONE":{"pattern":"TICK","level":10}}""").isEmpty())
        assertEquals(100, decodeRumbleCues("""{"THINK":{"pattern":"TICK","level":900}}""").getValue("THINK").level)
    }

    @Test
    fun `every combination covers all moments and keeps the key signals apart`() {
        for (combo in 0 until RUMBLE_COMBO_COUNT) {
            val configs = RumbleCue.entries.map { rumbleComboConfig(combo, it) }
            configs.forEach { assertTrue(it.pattern != RumblePattern.OFF && it.level in 1..100) }
            val signals = listOf(RumbleCue.TOOL_FAILED, RumbleCue.ANSWER_DONE, RumbleCue.APPROVAL).map { rumbleComboConfig(combo, it).pattern }
            assertEquals("combo $combo reuses a signal pattern", signals.size, signals.toSet().size)
        }
        assertEquals(rumbleComboConfig(RUMBLE_COMBO_COUNT - 1, RumbleCue.WRITE), rumbleComboConfig(99, RumbleCue.WRITE))
    }

    @Test
    fun `a flow breathes slowly and a visual feels different from prose`() {
        val writing = flowSpec(RumbleFlow.WRITING)
        val visual = flowSpec(RumbleFlow.VISUAL)
        assertTrue(writing.periodMs >= 1_000 && visual.periodMs > writing.periodMs)
        assertTrue(visual.steps.size > writing.steps.size)
        assertTrue(writing.fallback != visual.fallback)
        listOf(writing, visual).forEach { spec ->
            assertEquals(PRIMITIVE_SLOW_RISE, spec.steps.first { it.primitive == PRIMITIVE_SLOW_RISE }.primitive)
            assertTrue(spec.steps.all { it.scale in .1f..1f && it.delayMs >= 0 })
            assertTrue(spec.level in 1..100)
            assertTrue(rumbleWaveform(spec.fallback, spec.level, 100) != null)
        }
    }
}
