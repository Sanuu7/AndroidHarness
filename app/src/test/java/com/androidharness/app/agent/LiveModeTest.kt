package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.LiveModeAccess
import com.androidharness.app.data.LiveModeSettings
import com.androidharness.app.data.SettingsBackupPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveModeTest {
    @Test fun transcriptRoundTripsAndFlattensLineBreaks() {
        val exchanges = listOf(
            LiveExchange("Let's plan the settings page", "Sure.\n\nWhat should it hold?"),
            LiveExchange("A model picker", ""),
        )
        val text = LiveTranscript.format(exchanges)
        val message = ChatMessage(role = Role.USER, text = text, id = LiveTranscript.messageId("abc"))
        assertTrue(LiveTranscript.isTranscript(message))
        assertEquals(listOf(
            LiveExchange("Let's plan the settings page", "Sure.\nWhat should it hold?".replace("\n", " ")),
            LiveExchange("A model picker", ""),
        ), LiveTranscript.parse(text))
        assertEquals("Let's plan the settings page", LiveTranscript.titleSource(text))
    }

    @Test fun ordinaryUserMessagesAreNotTranscripts() {
        val text = LiveTranscript.format(listOf(LiveExchange("hi", "hello")))
        assertFalse(LiveTranscript.isTranscript(ChatMessage(role = Role.USER, text = text, id = "other")))
        assertFalse(LiveTranscript.isTranscript(ChatMessage(role = Role.USER, text = "hello", id = LiveTranscript.messageId("x"))))
    }

    @Test fun chatTranscriptRidesOnTheFirstSpokenMessageOnly() {
        val conversation = listOf(
            ChatMessage(role = Role.USER, text = "first"),
            ChatMessage(role = Role.ASSISTANT, text = "reply"),
            ChatMessage(role = Role.USER, text = "second"),
        )
        val request = LiveMode.requestMessages("old chat", conversation)
        assertEquals("<chat_transcript>\nold chat\n</chat_transcript>\n\nfirst", request[0].text)
        assertEquals("second", request[2].text)
        assertEquals(conversation, LiveMode.requestMessages(null, conversation))
    }

    @Test fun startTaskNeedsABrief() {
        assertEquals("Build it", LiveMode.briefFrom("""{"brief":"  Build it "}"""))
        assertNull(LiveMode.briefFrom("""{"brief":"  "}"""))
        assertNull(LiveMode.briefFrom("not json"))
    }

    @Test fun onlyTheTaskLevelMentionsHandingOff() {
        assertTrue(LiveMode.systemPrompt(LiveModeAccess.START_TASKS).contains(LiveMode.START_TASK_TOOL))
        assertFalse(LiveMode.systemPrompt(LiveModeAccess.READ).contains(LiveMode.START_TASK_TOOL))
        assertFalse(LiveMode.systemPrompt(LiveModeAccess.TALK).contains(LiveMode.START_TASK_TOOL))
    }

    @Test fun liveModeIsNeverCarriedByASettingsBackup() {
        val optedIn = AppSettings(liveMode = LiveModeSettings(enabled = true, providerId = "api", access = LiveModeAccess.START_TASKS))
        assertEquals(LiveModeSettings(), SettingsBackupPolicy.exportSettings(optedIn, setOf("api")).liveMode)
    }
}
