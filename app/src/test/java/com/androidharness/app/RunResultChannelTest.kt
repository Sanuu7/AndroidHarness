package com.androidharness.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RunResultChannelTest {
    @Test fun `finished runs use the results channel`() {
        val result = RunResultNotification("s", "Chat", ok = true, summary = "Run finished")
        assertEquals(AgentService.RESULTS_CHANNEL_ID, RunResultNotifications.channelFor(result))
    }

    @Test fun `failed runs use their own channel so they can have their own sound`() {
        val result = RunResultNotification("s", "Chat", ok = false, summary = "Stopped: boom")
        assertEquals(AgentService.ERRORS_CHANNEL_ID, RunResultNotifications.channelFor(result))
        assertNotEquals(AgentService.RESULTS_CHANNEL_ID, AgentService.ERRORS_CHANNEL_ID)
    }
}
