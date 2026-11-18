package com.androidharness.app.agent

import com.androidharness.app.agent.CodeActivityGroups.Slot
import com.androidharness.app.agent.CodeActivityGroups.Verb
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import org.junit.Assert.*
import org.junit.Test

class CodeActivityGroupsTest {
    private fun call(id: String, name: String, args: String = "{\"path\":\"a.kt\"}") = ToolCallData(id, name, args)
    private fun step(id: String, vararg calls: ToolCallData, text: String = "", thinking: String = "") =
        ChatMessage(Role.ASSISTANT, text = text, thinking = thinking, toolCalls = calls.toList(), id = id)

    @Test fun `explore and change tools split into lanes`() {
        listOf("read_file", "grep", "list_dir", "search_files", "codegraph_explore", "web_search", "git_diff", "recall")
            .forEach { assertTrue(it, CodeActivityGroups.isExplore(call("x", it))) }
        listOf("write_file", "edit_file", "apply_patch", "shell", "delete_file", "move_file", "git_commit",
            "pkg_install", "task", "ask_user", "browser_click", "todo_write")
            .forEach { assertFalse(it, CodeActivityGroups.isExplore(call("x", it))) }
    }

    @Test fun `consecutive reads across steps form one group and fold the thinking between them`() {
        val plan = CodeActivityGroups.plan(listOf(
            step("m1", call("c1", "read_file"), thinking = "look"),
            step("m2", call("c2", "grep"), thinking = "then search"),
            step("m3", call("c3", "list_dir")),
        ))
        val group = (plan.slots.getValue("m1").single() as Slot.Explore).group
        assertEquals(listOf("c1", "c2", "c3"), group.calls.map { it.id })
        assertEquals(setOf("m2"), plan.foldedThinking)
        assertTrue(plan.slots.getValue("m2").isEmpty())
        assertTrue(plan.slots.getValue("m3").isEmpty())
    }

    @Test fun `edits commands and prose break a run`() {
        val plan = CodeActivityGroups.plan(listOf(
            step("m1", call("c1", "read_file"), call("c2", "edit_file"), call("c3", "read_file")),
            step("m4", call("c4", "grep"), text = "Found it."),
            step("m5", call("c5", "shell", "{\"command\":\"ls\"}"), thinking = "run"),
        ))
        val first = plan.slots.getValue("m1")
        assertEquals(3, first.size)
        assertEquals(listOf("c1"), (first[0] as Slot.Explore).group.calls.map { it.id })
        assertEquals("c2", (first[1] as Slot.Single).call.id)
        assertEquals(listOf("c3"), (first[2] as Slot.Explore).group.calls.map { it.id })
        assertEquals(listOf("c4"), (plan.slots.getValue("m4").single() as Slot.Explore).group.calls.map { it.id })
        assertTrue(plan.slots.getValue("m5").single() is Slot.Single)
        assertTrue(plan.foldedThinking.isEmpty())
    }

    @Test fun `browser steps fold into their own run apart from reads`() {
        val plan = CodeActivityGroups.plan(listOf(
            step("m1", call("c1", "read_file"), call("c2", "browser_navigate", "{\"url\":\"https://a.dev\"}")),
            step("m2", call("c3", "browser_click"), thinking = "click it"),
            step("m3", call("c4", "browser_screenshot"), call("c5", "grep")),
        ))
        val first = plan.slots.getValue("m1")
        assertEquals(CodeActivityGroups.Kind.EXPLORE, (first[0] as Slot.Explore).group.kind)
        val browser = (first[1] as Slot.Explore).group
        assertEquals(CodeActivityGroups.Kind.BROWSER, browser.kind)
        assertEquals(listOf("c2", "c3", "c4"), browser.calls.map { it.id })
        assertEquals(setOf("m2"), plan.foldedThinking)
        assertEquals(listOf("c5"), (plan.slots.getValue("m3").single() as Slot.Explore).group.calls.map { it.id })
    }

    @Test fun `a run never crosses into the next turn`() {
        val plan = CodeActivityGroups.plan(listOf(
            step("m1", call("c1", "read_file")).copy(turnId = "t1"),
            ChatMessage(Role.USER, text = "next", turnId = "t2"),
            step("m2", call("c2", "read_file")).copy(turnId = "t2"),
        ))
        assertTrue(plan.slots.getValue("m1").single() is Slot.Explore)
        assertTrue(plan.slots.getValue("m2").single() is Slot.Explore)
    }

    @Test fun `group key stays stable as the run grows`() {
        val one = CodeActivityGroups.plan(listOf(step("m1", call("c1", "read_file"))))
        val two = CodeActivityGroups.plan(listOf(step("m1", call("c1", "read_file")), step("m2", call("c2", "grep"))))
        assertEquals((one.slots.getValue("m1").single() as Slot.Explore).group.key,
            (two.slots.getValue("m1").single() as Slot.Explore).group.key)
    }

    @Test fun `summary counts verbs in a fixed order`() {
        val summary = CodeActivityGroups.summary(listOf(call("1", "grep"), call("2", "read_file"), call("3", "read_file"), call("4", "list_dir")))
        assertEquals(listOf(Verb.READ to 2, Verb.SEARCH to 1, Verb.LIST to 1), summary)
    }
}
