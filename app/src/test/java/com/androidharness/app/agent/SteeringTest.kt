package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.SteeringMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SteeringTest {
    private val steer = ChatMessage(role = Role.USER, text = "Use Room instead", id = Steering.messageId("p1"))

    @Test fun modelSeesTheMessageMarkedAsMidTask() {
        val wrapped = Steering.forModel(steer)
        assertTrue(wrapped.text.startsWith("<steering_message>"))
        assertTrue(wrapped.text.contains("Use Room instead"))
        assertEquals(steer.id, wrapped.id)
    }

    @Test fun wrappingIsStableSoTheCachedPrefixMatches() {
        val once = Steering.forModel(steer)
        assertSame(once, Steering.forModel(once))
    }

    @Test fun ordinaryMessagesAreUntouched() {
        val user = ChatMessage(role = Role.USER, text = "Hello", id = "turn-user")
        val tool = ChatMessage(role = Role.TOOL, text = "x", id = Steering.messageId("p2"), toolCallId = "c")
        assertSame(user, Steering.forModel(user))
        assertSame(tool, Steering.forModel(tool))
    }

    @Test fun rebuiltHistoryWrapsLikeTheLiveRequest() {
        val history = listOf(ChatMessage(role = Role.USER, text = "Start"), steer)
        assertEquals(listOf(history[0], Steering.forModel(steer)), ContextHygiene.forModel(history))
    }

    @Test fun ahAutoIsTheDefault() {
        assertEquals(SteeringMode.AH_AUTO, SteeringMode.fromStored(null))
        assertEquals(SteeringMode.AH_AUTO, SteeringMode.fromStored("bogus"))
        assertEquals(SteeringMode.AFTER_TASK, SteeringMode.fromStored("AFTER_TASK"))
    }
}
