package com.androidharness.app.ui.settings

import com.androidharness.app.data.audio.VoicePresets
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceEngineStateTest {
    private val groq = checkNotNull(VoicePresets.byId(VoicePresets.GROQ))
    private val edge = checkNotNull(VoicePresets.byId(VoicePresets.EDGE))

    @Test fun `the phone is always ready`() {
        assertEquals(VoiceReadiness.READY, VoiceEngineState(provider = null, onPhone = true, keyReady = true).readiness)
    }

    @Test fun `a provider that was removed is missing`() {
        assertEquals(VoiceReadiness.MISSING, VoiceEngineState(provider = null, onPhone = false, keyReady = false).readiness)
    }

    @Test fun `a provider without its key needs one`() {
        assertEquals(VoiceReadiness.NEEDS_KEY, VoiceEngineState(groq, onPhone = false, keyReady = false).readiness)
    }

    @Test fun `a provider with its key is ready`() {
        assertEquals(VoiceReadiness.READY, VoiceEngineState(groq, onPhone = false, keyReady = true).readiness)
    }

    @Test fun `a free provider needs no key`() {
        assertEquals(false, edge.requiresKey)
        assertEquals(VoiceReadiness.READY, VoiceEngineState(edge, onPhone = false, keyReady = true).readiness)
    }
}
