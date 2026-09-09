package com.androidharness.app.agent

import com.androidharness.app.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class TaskControlTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun `queue edits and reordering survive process recreation`() {
        val store = TaskControlStore(folder.root)
        val a = QueuedPrompt(text = "first")
        val b = QueuedPrompt(text = "second")
        store.update("session") { it.copy(status = "running", queue = listOf(a, b)) }
        store.update("session") { it.copy(queue = listOf(b.copy(text = "edited"), a)) }
        val restored = TaskControlStore(folder.root).flow("session").value
        assertEquals(listOf("edited", "first"), restored.queue.map { it.text })
        assertTrue(restored.resumable)
    }
    @Test fun `settings and usage survive atomic replacement`() {
        val record = TaskRecord(status = "paused", pins = "Keep Kotlin", summaryOverride = "Goal",
            limits = TaskLimits(100, 0.2, 5), usedTokens = 44, usedCost = 0.1, elapsedMs = 30000)
        TaskControlStore(folder.root).update("session") { record }
        assertEquals(record, TaskControlStore(folder.root).flow("session").value)
    }
    @Test fun `interrupted temporary write retains committed state`() {
        val store = TaskControlStore(folder.root)
        store.update("session") { it.copy(pins = "saved") }
        folder.root.resolve("session.json.tmp").writeText("{broken")
        assertEquals("saved", TaskControlStore(folder.root).flow("session").value.pins)
    }
    @Test fun `concurrent queue appends do not replace each other`() = runBlocking {
        val store = TaskControlStore(folder.root)
        coroutineScope { (1..20).map { n -> async(Dispatchers.Default) {
            store.update("session") { it.copy(queue = it.queue + QueuedPrompt(text = "$n")) }
        } }.awaitAll() }
        assertEquals(20, store.flow("session").value.queue.size)
    }
    @Test fun `recovery closes only unresolved calls`() {
        val messages = listOf(ChatMessage(Role.ASSISTANT, toolCalls = listOf(
            ToolCallData("a", "write_file", "{}"), ToolCallData("b", "shell", "{}"))),
            ChatMessage(Role.TOOL, text = "done", toolCallId = "a"))
        val missing = RunRecovery.missingResults(messages)
        assertEquals(listOf("b"), missing.map { it.toolCallId })
        assertTrue(missing.single().isError)
        assertTrue(RunRecovery.missingResults(messages + missing).isEmpty())
    }
    @Test fun `completed writes are reused and uncertain writes are blocked`() {
        val call = ToolCallData("a", "write_file", "{\"path\":\"x\"}")
        val assistant = ChatMessage(Role.ASSISTANT, toolCalls = listOf(call))
        val completed = RecoveryLedger(listOf(assistant, ChatMessage(Role.TOOL, "done", toolCallId = "a")))
        assertTrue(completed.result(call.copy(id = "new"))!!.ok)
        assertFalse(RecoveryLedger(listOf(assistant)).result(call)!!.ok)
        assertNull(completed.result(call.copy(argumentsJson = "{\"path\":\"y\"}")))
    }
    @Test fun `removed context stays out without deleting messages`() {
        val messages = listOf(ChatMessage(Role.USER, "old", createdAt = 1),
            ChatMessage(Role.USER, "new", createdAt = 10))
        val context = RunRecovery.context(messages, TaskRecord(contextAfter = 10, summaryOverride = "summary"))
        assertEquals(2, messages.size)
        assertFalse(context.any { it.text == "old" })
        assertTrue(context.any { it.text == "new" })
        assertTrue(context.first().text.contains("summary"))
    }
    @Test fun `editing summary replaces old summary and keeps recent turns`() {
        val context = RunRecovery.context(listOf(ContextHygiene.summaryMessage("stale"), ChatMessage(Role.USER, "recent")),
            TaskRecord(summaryOverride = "corrected"))
        assertFalse(context.any { it.text.contains("stale") })
        assertTrue(context.first().text.contains("corrected"))
        assertEquals("recent", context.last().text)
    }
    @Test fun `subagents share parent token and cost limits`(): Unit = runBlocking {
        val budget = TaskBudget(TaskLimits(tokens = 100, cost = 1.0), 0, 0.0, 0)
        withContext(budget) {
            coroutineScope { (1..2).map { async {
                kotlin.coroutines.coroutineContext[TaskBudget]!!.add(30, 20, 0.25)
            } }.awaitAll() }
        }
        assertEquals(100L, budget.usage().first)
        assertEquals(0.5, budget.usage().second, 0.00001)
        assertThrows(TaskPaused::class.java) { budget.check() }
    }
    @Test fun `cost pauses and unlimited permits usage`() {
        val limited = TaskBudget(TaskLimits(cost = 0.1), 0, 0.09, 0)
        limited.add(1, 1, 0.02)
        assertThrows(TaskPaused::class.java) { limited.check() }
        val unlimited = TaskBudget(TaskLimits(), 1000000, 500.0, 5000000)
        unlimited.check()
    }
    @Test fun `active time includes prior run but not downtime`() {
        var clock = 1000L
        val budget = TaskBudget(TaskLimits(minutes = 1), 0, 0.0, 59000) { clock }
        budget.check()
        clock += 1000
        assertThrows(TaskPaused::class.java) { budget.check() }
    }
    @Test fun `exhausted budget prevents any provider request`() = runBlocking {
        var called = false
        try {
            withContext(TaskBudget(TaskLimits(tokens = 1), 1, 0.0, 0)) {
                StreamRetrier.run(streamFor = { called = true; flowOf() }, onAttemptStart = {},
                    hasOutput = { false }, handleEvent = {}, retryReason = { it }, emitEvent = {})
            }
            fail("Expected pause")
        } catch (_: TaskPaused) { }
        assertFalse(called)
    }
    @Test fun `invalid limits rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TaskLimits(cost = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { TaskLimits(tokens = -1) }
    }
}
