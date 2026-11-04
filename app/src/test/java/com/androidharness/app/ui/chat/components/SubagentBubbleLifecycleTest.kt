package com.androidharness.app.ui.chat.components

import com.androidharness.app.agent.SubagentRun
import com.androidharness.app.agent.SubagentStatus
import com.androidharness.app.core.ToolCallData
import org.junit.Assert.*
import org.junit.Test

class SubagentBubbleLifecycleTest {
    private fun run(id: String, status: SubagentStatus, turn: String = "turn") = SubagentRun(
        "chat", turn, ToolCallData(id, "task", "{}"), id, "task", "model", "provider", false, status = status)

    @Test fun `opening completed history never shows a bubble`() {
        val state = SubagentBubbleLifecycle().update(listOf(run("old", SubagentStatus.COMPLETED)), 100)
        assertFalse(state.visible(100, false))
        assertFalse(state.visible(100, true))
        assertTrue(state.tracked.isEmpty())
    }

    @Test fun `last completion lingers for two seconds then hides`() {
        val state = SubagentBubbleLifecycle().update(listOf(run("a", SubagentStatus.RUNNING)), 100)
            .update(listOf(run("a", SubagentStatus.COMPLETED)), 200)
        assertTrue(state.visible(2199, false))
        assertFalse(state.visible(2200, false))
        assertTrue(state.active.isEmpty())
    }

    @Test fun `reading results keeps the panel until closed without restarting the timeout`() {
        val state = SubagentBubbleLifecycle().update(listOf(run("a", SubagentStatus.RUNNING)), 100)
            .update(listOf(run("a", SubagentStatus.COMPLETED)), 200)
        assertTrue(state.visible(10000, true))
        assertFalse(state.visible(10000, false))
        assertEquals(state.finishedAt, state.update(listOf(run("a", SubagentStatus.COMPLETED)), 9999).finishedAt)
    }

    @Test fun `one finished worker never hides other running or queued work`() {
        val a = run("a", SubagentStatus.RUNNING)
        val b = run("b", SubagentStatus.QUEUED)
        val state = SubagentBubbleLifecycle().update(listOf(a, b), 100).update(listOf(a.copy(status = SubagentStatus.COMPLETED), b), 200)
        assertTrue(state.visible(10000, false))
        assertEquals(setOf("b"), state.active)
        assertEquals(setOf("a", "b"), state.tracked)
    }

    @Test fun `failures stops and interruptions finish the popup lifecycle`() {
        for (status in listOf(SubagentStatus.FAILED, SubagentStatus.STOPPED, SubagentStatus.INTERRUPTED)) {
            val state = SubagentBubbleLifecycle().update(listOf(run("a", SubagentStatus.RUNNING)), 100)
                .update(listOf(run("a", status)), 200)
            assertTrue(state.visible(201, false))
            assertFalse(state.visible(2200, false))
        }
    }

    @Test fun `new work revives the bubble and includes results from its active turn`() {
        val old = run("old", SubagentStatus.COMPLETED, "old-turn")
        val early = run("early", SubagentStatus.COMPLETED)
        val active = run("a", SubagentStatus.RUNNING)
        val state = SubagentBubbleLifecycle().update(listOf(old, early, active), 100)
        assertEquals(setOf("early", "a"), state.tracked)
        val finished = state.update(listOf(old, early, active.copy(status = SubagentStatus.COMPLETED)), 200)
        val restarted = finished.update(listOf(old, early, active.copy(status = SubagentStatus.RUNNING)), 3000)
        assertTrue(restarted.visible(10000, false))
        assertNull(restarted.finishedAt)
    }
}
