package com.androidharness.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveListenerTest {
    @Test fun shortWhisperFillerIsDropped() {
        assertTrue(LiveListener.isLikelyHallucination("Thank you.", 600))
        assertTrue(LiveListener.isLikelyHallucination("you", 400))
    }

    @Test fun realSpeechIsKept() {
        assertFalse(LiveListener.isLikelyHallucination("Thank you.", 2_000))
        assertFalse(LiveListener.isLikelyHallucination("Add a model picker", 600))
    }
}
