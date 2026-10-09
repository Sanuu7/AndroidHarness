package com.androidharness.app.agent

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunFollowUpsTest {
    @get:Rule val folder = TemporaryFolder()
    private val store by lazy { TaskControlStore(folder.root) }
    private val followUps by lazy { RunFollowUps(store) }

    private fun end(session: String = "a", finished: Boolean = true, recoverable: Boolean = false) =
        RunFollowUps.End(session, followUps.begin(session), finished, recoverable)

    @Test fun `stream pauses and tool round gaps cannot drain queue`() {
        val queued = QueuedPrompt(text = "next command")
        store.update("a") { it.copy(status = "running", queue = listOf(queued)) }
        val event = end(finished = false)
        assertNull(followUps.next(event))
        assertEquals(listOf(queued), store.flow("a").value.queue)
    }

    @Test fun `successful notification advances one prompt from only its chat`() {
        val a = QueuedPrompt(text = "chat A")
        val b = QueuedPrompt(text = "chat B")
        store.update("a") { it.copy(queue = listOf(a, QueuedPrompt(text = "second"))) }
        store.update("b") { it.copy(status = "running", queue = listOf(b)) }
        val eventA = end("a")
        val eventB = end("b")
        assertEquals(RunFollowUps.Action.Send(a), followUps.next(eventA))
        assertEquals(listOf(b), store.flow("b").value.queue)
        assertNull(followUps.next(eventA))
        store.update("b") { it.copy(status = "idle") }
        assertEquals(RunFollowUps.Action.Send(b), followUps.next(eventB))
    }

    @Test fun `wrong chat or old run notification cannot advance queue`() {
        store.update("a") { it.copy(queue = listOf(QueuedPrompt(text = "wait"))) }
        val old = end()
        assertNull(followUps.next(old.copy(sessionId = "b")))
        val current = end()
        assertNull(followUps.next(old))
        assertNotNull(followUps.next(current))
    }

    @Test fun `stopping after completion suppresses pending queue or continue`() {
        for (continueTask in listOf(false, true)) {
            store.update("a") { it.copy(status = if (continueTask) "paused" else "idle",
                autoContinue = true, queue = listOf(QueuedPrompt(text = "wait"))) }
            val event = end(finished = !continueTask, recoverable = continueTask)
            followUps.stop("a")
            assertNull(followUps.next(event))
        }
        assertEquals(0, store.flow("a").value.autoContinueAttempts)
    }

    @Test fun `stop between selecting follow up and starting it prevents successor`() {
        store.update("a") { it.copy(queue = listOf(QueuedPrompt(text = "wait"))) }
        val event = end()
        assertNotNull(followUps.next(event))
        followUps.stop("a")
        assertNull(followUps.advance("a", event.runId))
    }

    @Test fun `manual replacement between selection and start prevents old successor`() {
        store.update("a") { it.copy(queue = listOf(QueuedPrompt(text = "wait"))) }
        val event = end()
        assertNotNull(followUps.next(event))
        val manualRun = followUps.begin("a")
        assertNull(followUps.advance("a", event.runId))
        assertTrue(followUps.isCurrent("a", manualRun))
    }

    @Test fun `stop all invalidates completed and interrupted chats`() {
        store.update("a") { it.copy(queue = listOf(QueuedPrompt(text = "A"))) }
        store.update("b") { it.copy(status = "paused", autoContinue = true) }
        val a = end()
        val b = end("b", finished = false, recoverable = true)
        followUps.stopAll()
        assertNull(followUps.next(a))
        assertNull(followUps.next(b))
    }

    @Test fun `edits reordering and removal during cleanup are respected`() {
        val a = QueuedPrompt(text = "first")
        val b = QueuedPrompt(text = "second")
        store.update("a") { it.copy(queue = listOf(a, b)) }
        val event = end()
        val edited = b.copy(text = "edited")
        store.update("a") { it.copy(queue = listOf(edited, a)) }
        assertEquals(RunFollowUps.Action.Send(edited), followUps.next(event))
        val next = end()
        store.update("a") { it.copy(queue = emptyList()) }
        assertNull(followUps.next(next))
    }

    @Test fun `server interruption continues saved task without consuming queue`() {
        val queued = QueuedPrompt(text = "after finish")
        store.update("a") { it.copy(status = "paused", autoContinue = true, queue = listOf(queued),
            turnId = "original-turn", initialPrompt = "original task", usedTokens = 100, elapsedMs = 3000) }
        val event = end(finished = false, recoverable = true)
        assertEquals(RunFollowUps.Action.Continue, followUps.next(event))
        assertNull(followUps.next(event))
        val saved = store.flow("a").value
        assertEquals(listOf(queued), saved.queue)
        assertEquals("original-turn", saved.turnId)
        assertEquals("original task", saved.initialPrompt)
        assertEquals(100L, saved.usedTokens)
        assertEquals(3000L, saved.elapsedMs)
        assertEquals(1, saved.autoContinueAttempts)
    }

    @Test fun `continue retry cap persists across restart and cannot loop indefinitely`() {
        store.update("a") { it.copy(status = "paused", autoContinue = true, autoContinueLimit = 2) }
        repeat(2) { assertEquals(RunFollowUps.Action.Continue,
            followUps.next(end(finished = false, recoverable = true))) }
        assertNull(followUps.next(end(finished = false, recoverable = true)))
        val restored = TaskControlStore(folder.root)
        val restoredFollowUps = RunFollowUps(restored)
        val event = RunFollowUps.End("a", restoredFollowUps.begin("a"), false, true)
        assertNull(restoredFollowUps.next(event))
        assertEquals(2, restored.flow("a").value.autoContinueAttempts)
    }

    @Test fun `corrupt or legacy excessive limit is bounded to five`() {
        store.update("a") { it.copy(status = "paused", autoContinue = true, autoContinueLimit = 100) }
        repeat(5) { assertEquals(RunFollowUps.Action.Continue,
            followUps.next(end(finished = false, recoverable = true))) }
        assertNull(followUps.next(end(finished = false, recoverable = true)))
    }

    @Test fun `continue is opt in and ignores permanent errors limits and user pauses`() {
        store.update("a") { it.copy(status = "paused", queue = listOf(QueuedPrompt(text = "wait"))) }
        assertNull(followUps.next(end(finished = false, recoverable = true)))
        store.update("a") { it.copy(autoContinue = true) }
        assertNull(followUps.next(end(finished = false, recoverable = false)))
        assertEquals(0, store.flow("a").value.autoContinueAttempts)
        assertEquals(1, store.flow("a").value.queue.size)
    }

    @Test fun `disabling continue during backoff cancels pending recovery`() {
        store.update("a") { it.copy(status = "paused", autoContinue = true) }
        val event = end(finished = false, recoverable = true)
        store.update("a") { it.copy(autoContinue = false) }
        assertNull(followUps.next(event))
    }

    @Test fun `simultaneous duplicate events send only once`() = runBlocking {
        store.update("a") { it.copy(queue = listOf(QueuedPrompt(text = "once"))) }
        val event = end()
        val actions = coroutineScope { (1..20).map { async(Dispatchers.Default) {
            followUps.next(event)
        } }.awaitAll() }
        assertEquals(1, actions.count { it != null })
    }

    @Test fun `legacy settings default to disabled recovery with a small limit`() {
        folder.root.resolve("a.json").writeText("""{"status":"paused","queue":[{"id":"q","text":"wait"}]}""")
        val saved = store.flow("a").value
        assertFalse(saved.autoContinue)
        assertEquals(3, saved.autoContinueLimit)
        assertEquals(0, saved.autoContinueAttempts)
    }
}
