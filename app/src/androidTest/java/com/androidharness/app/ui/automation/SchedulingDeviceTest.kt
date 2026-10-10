package com.androidharness.app.ui.automation

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkManager
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.agent.*
import com.androidharness.app.automation.*
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.llm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchedulingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    private suspend fun <T> fixture(mode: GatewayMode = GatewayMode.REPLY, block: suspend (MainActivity, ProviderConfig, String, String, File, FixtureGateway) -> T): T {
        val settings = c.settings.settings.first()
        val preferences = c.appContext.getSharedPreferences("schedule_preferences", android.content.Context.MODE_PRIVATE)
        val previousConnection = preferences.getBoolean("unmetered", false)
        preferences.edit().putBoolean("unmetered", false).commit()
        val previous = c.workspace.currentProjectOnce()
        val roots = File(c.appContext.cacheDir, "scheduling-${System.nanoTime()}/AndroidHarness").apply { mkdirs() }
        val project = c.workspace.addShellProject(roots.path)
        val session = c.sessions.createSession("Release checks for the Android project", project.id)
        val gateway = FixtureGateway(mode)
        val provider = c.providers.add("Scheduling fixture", ProviderType.OPENAI_COMPAT, "http://127.0.0.1:${gateway.port}/v1", "fixture-main", "fixture")
        c.providers.saveCatalog(provider.id, listOf(ModelEntry("fixture-main"), ModelEntry("fixture-alternative")))
        c.settings.setOnboardingDone(true); c.settings.setResumeLastChat(true); c.settings.setLastActiveSessionId(session)
        c.settings.setActiveProvider(provider.id); c.settings.setActiveModel("fixture-main"); c.settings.setPlanningModelsEnabled(false)
        c.settings.setPermissionMode(PermissionMode.FULL_AUTO); c.settings.setThinkingLevel(ThinkingLevel.OFF)
        shell("pm grant ${c.appContext.packageName} android.permission.POST_NOTIFICATIONS")
        val activity = instrumentation.startActivitySync(Intent(c.appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try { return block(activity, provider, session, project.id, roots, gateway) }
        finally {
            c.automation.repository.tasks.value.filter { it.projectId == project.id }.forEach { c.automation.delete(it.id) }
            c.runManager.stopAndJoin(session)
            instrumentation.runOnMainSync { activity.finish() }
            c.sessions.sessions.first().filter { it.projectId == project.id }.forEach { c.sessions.deleteSession(it) }
            c.providers.delete(provider.id); c.workspace.deleteProject(project); c.workspace.setActiveProject(previous.id)
            c.settings.restorePortable(settings); c.settings.setPermissionMode(settings.permissionMode)
            c.settings.setOnboardingDone(settings.onboardingDone); c.settings.setLastActiveSessionId(settings.lastActiveSessionId)
            preferences.edit().putBoolean("unmetered", previousConnection).commit()
            roots.parentFile?.deleteRecursively(); gateway.close()
        }
    }

    @Test fun composerKeepsDraftAndSavesSelectedContext(): Unit = runBlocking {
        fixture { activity, provider, session, project, root, _ ->
            await { it.contentDescription?.toString() == "Message actions" }
            val draft = "Build the debug APK and check the release notes"
            setText(draft)
            tap("Message actions"); awaitText("Schedule message"); screenshot("schedule-menu")
            tap("Schedule message"); awaitText("Connection"); screenshot("schedule-sheet-top")
            tap("Close schedule")
            awaitText(draft)
            tap("Message actions"); tap("Schedule message")
            assertTrue(await { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            SystemClock.sleep(700); screenshot("schedule-keyboard")
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            awaitText("Connection")
            tap("Date"); awaitText("Cancel"); screenshot("schedule-date-picker"); tap("Cancel")
            tap("Time"); awaitText("Cancel"); screenshot("schedule-time-picker"); tap("Cancel")
            scrollTo("Unmetered only"); tap("Unmetered only")
            scrollTo("Workspace follows the selected chat.")
            awaitText("fixture-main"); screenshot("schedule-sheet-context")
            tap("Schedule")
            withTimeout(15000) { while (c.automation.repository.tasks.value.none { it.targetSessionId == session }) delay(50) }
            val task = c.automation.repository.tasks.value.single { it.targetSessionId == session }
            assertEquals(project, task.projectId); assertEquals(provider.id, task.providerId); assertEquals("fixture-main", task.model)
            assertEquals(draft, task.prompt); assertTrue(task.unmeteredOnly)
            assertTrue(task.scheduledAt!! > System.currentTimeMillis())
            val work = WorkManager.getInstance(c.appContext).getWorkInfosForUniqueWork("automation-schedule-${task.id}").get().single()
            assertEquals(NetworkType.UNMETERED, work.constraints.requiredNetworkType)
            assertEquals(task, AutomationRepository(c.appContext).task(task.id))
            await { it.text?.toString()?.startsWith("Scheduled ") == true }; screenshot("schedule-chat-row")
            tap("Open scheduled messages"); awaitText("Edit schedule")
            tap("Repeat"); tap("Every day")
            scrollTo("Workspace follows the selected chat.")
            tap("Model"); awaitText("Choose model"); tap("fixture-alternative")
            scrollTo("Workspace follows the selected chat.")
            tap("Chat"); awaitText("Choose chat"); tap("New chat")
            scrollTo("Workspace"); tap("Workspace"); awaitText("Choose workspace"); tap(root.name)
            scrollTo("Chat"); tap("Chat"); awaitText("Choose chat"); tap("Release checks for the Android project")
            tap("Save changes")
            withTimeout(15000) { while (c.automation.repository.task(task.id)?.model != "fixture-alternative") delay(50) }
            assertEquals(AutomationSchedule.DAILY, c.automation.repository.task(task.id)?.schedule)
            assertEquals(session, c.automation.repository.task(task.id)?.targetSessionId)
            instrumentation.runOnMainSync { activity.setContent { com.androidharness.app.ui.theme.HarnessTheme { AutomationScreen(c, {}, {}) } } }
            awaitText("Automation"); SystemClock.sleep(700); screenshot("schedule-automations")
            scrollTo("Pause"); tap("Pause")
            withTimeout(15000) { while (c.automation.repository.task(task.id)?.enabled != false) delay(50) }
            awaitText("Resume")
            scrollTo("Cancel"); tap("Cancel"); awaitText("Cancel this schedule?"); tap("Keep schedule")
            assertEquals(AutomationStatus.PAUSED, c.automation.repository.task(task.id)?.lastStatus)
            scrollTo("Resume"); tap("Resume")
            withTimeout(15000) { while (c.automation.repository.task(task.id)?.enabled != true) delay(50) }
            assertNull(c.automation.repository.task(task.id)?.lastMessage)
            scrollTo("Cancel"); tap("Cancel"); awaitText("Cancel this schedule?"); tap("Cancel schedule")
            withTimeout(15000) { while (c.automation.repository.task(task.id)?.lastStatus != AutomationStatus.CANCELLED) delay(50) }
        }
    }

    @Test fun workerResumesSameChatAfterUnmeteredConnectionReturns(): Unit = runBlocking {
        try {
            fixture(GatewayMode.RECOVER) { _, provider, session, project, root, gateway ->
                await { it.contentDescription?.toString() == "Message actions" }
                withTimeout(30000) { c.automation.connection.first { it.connected && it.unmetered } }
                val task = AutomationTask(title = "Scheduled recovery", prompt = "Write result.txt once and finish", projectId = project,
                    projectName = root.name, providerId = provider.id, model = provider.model, targetSessionId = session,
                    unmeteredOnly = true, schedule = AutomationSchedule.ONCE, scheduledAt = System.currentTimeMillis() + 5000)
                c.automation.save(task)
                withTimeout(45000) { gateway.secondRequest.await() }
                assertEquals("Saved progress\n", File(root, "result.txt").readText())
                val modified = File(root, "result.txt").lastModified()
                val active = c.automation.repository.task(task.id)!!.activeRun!!
                c.runManager.inject(session, "Explain the completed checks")
                withTimeout(5000) { while (c.runManager.controls.flow(session).value.queue.isEmpty()) delay(50) }
                shell("svc wifi disable"); shell("svc data disable")
                withTimeout(30000) { while (c.runManager.isRunning(session) || c.automation.repository.task(task.id)?.lastStatus != AutomationStatus.QUEUED) delay(100) }
                val waiting = c.automation.repository.task(task.id)!!
                assertTrue(waiting.enabled); assertEquals(active.id, waiting.activeRun?.id); assertEquals(session, waiting.activeRun?.sessionId)
                assertEquals("interrupted", c.runManager.controls.flow(session).value.status)
                assertEquals(waiting, AutomationRepository(c.appContext).task(task.id))
                shell("svc wifi enable"); shell("svc data enable")
                withTimeout(120000) { while (c.automation.repository.task(task.id)?.lastStatus != AutomationStatus.COMPLETED) delay(100) }
                val finished = c.automation.repository.task(task.id)!!
                assertFalse(finished.enabled); assertNull(finished.activeRun); assertEquals(session, finished.lastSessionId)
                assertEquals(modified, File(root, "result.txt").lastModified())
                val messages = c.sessions.messages(session)
                assertEquals(1, messages.count { it.role == Role.USER && it.text.startsWith(task.prompt) })
                assertEquals(1, messages.count { it.role == Role.TOOL && it.toolCallId == "write-once" })
                assertTrue(messages.any { it.text == "Completed after connection returned" })
                withTimeout(30000) { while (gateway.requests.get() < 4 || c.runManager.isRunning(session)) delay(100) }
                assertTrue(c.sessions.messages(session).any { it.role == Role.USER && it.text == "Explain the completed checks" })
                assertTrue(c.runManager.controls.flow(session).value.queue.isEmpty())
                val count = gateway.requests.get()
                assertEquals(AutomationOutcome.DONE, c.automation.execute(task.id, true, task.scheduledAt.toString()))
                assertEquals(count, gateway.requests.get())
            }
        } finally {
            shell("svc wifi enable"); shell("svc data enable")
        }
    }

    @Test fun busyChatCannotBeReplacedByScheduledRun(): Unit = runBlocking {
        fixture(GatewayMode.HOLD) { _, provider, session, project, root, gateway ->
                withTimeout(30000) { c.automation.connection.first { it.connected } }
                c.runManager.startRun(session, "Manual task", emptyList(), provider, "fixture", PermissionMode.FULL_AUTO,
                    AgentMode.ACT, 1024, 32000, ThinkingLevel.OFF, 5, notifyOnFinish = false)
                withTimeout(30000) { gateway.firstRequest.await() }
                val before = c.runManager.controls.flow(session).value
                val task = AutomationTask(title = "Waiting task", prompt = "Scheduled task", projectId = project,
                    projectName = root.name, providerId = provider.id, model = provider.model, targetSessionId = session)
                c.automation.repository.save(task)
                assertEquals(AutomationOutcome.RETRY, c.automation.execute(task.id, occurrence = "busy-check"))
                assertTrue(c.runManager.isRunning(session)); assertEquals(before.turnId, c.runManager.controls.flow(session).value.turnId)
                assertEquals("Waiting for this chat to finish", c.automation.repository.task(task.id)?.lastMessage)
                val failure = runCatching { c.runManager.startRun(session, "Replacement", emptyList(), provider, "fixture",
                    PermissionMode.FULL_AUTO, AgentMode.ACT, 1024, 32000, ThinkingLevel.OFF, 5, onlyIfIdle = true) }.exceptionOrNull()
                assertTrue(failure is RunBusyException); assertTrue(c.runManager.isRunning(session))
                val progress = AutomationRun(occurrence = "busy-check", sessionId = session)
                c.automation.repository.updateTask(task.id) { it.copy(activeRun = progress) }
                c.automation.save(task.copy(title = "Edited title"))
                assertEquals(progress, c.automation.repository.task(task.id)?.activeRun)
                assertTrue(runCatching { c.automation.save(task.copy(prompt = "Different instructions")) }.isFailure)
        }
    }

    @Test fun notificationPauseResumesTheSameOccurrence(): Unit = runBlocking {
        fixture(GatewayMode.RECOVER) { _, provider, session, project, root, gateway ->
            withTimeout(30000) { c.automation.connection.first { it.connected } }
            val task = AutomationTask(title = "Notification pause", prompt = "Write result.txt once and finish", projectId = project,
                projectName = root.name, providerId = provider.id, model = provider.model, targetSessionId = session,
                schedule = AutomationSchedule.ONCE, scheduledAt = System.currentTimeMillis() + 5000)
            c.automation.save(task)
            withTimeout(45000) { gateway.secondRequest.await() }
            val active = c.automation.repository.task(task.id)!!.activeRun!!
            val modified = File(root, "result.txt").lastModified()
            val notifications = c.appContext.getSystemService(android.app.NotificationManager::class.java)
            val action = withTimeout(10000) {
                var pause: android.app.Notification.Action? = null
                while (pause == null) {
                    pause = notifications.activeNotifications.filter { it.notification.channelId == "automations" }
                        .flatMap { it.notification.actions?.toList().orEmpty() }.firstOrNull { it.title.toString() == "Pause" }
                    if (pause == null) delay(100)
                }
                pause
            }
            action.actionIntent.send()
            withTimeout(30000) { while (c.runManager.isRunning(session) || c.automation.repository.task(task.id)?.lastStatus != AutomationStatus.PAUSED) delay(100) }
            assertEquals(active.id, c.automation.repository.task(task.id)?.activeRun?.id)
            assertFalse(c.automation.repository.task(task.id)!!.enabled)
            c.automation.resumeSchedule(task.id)
            withTimeout(45000) { while (c.automation.repository.task(task.id)?.lastStatus != AutomationStatus.COMPLETED) delay(100) }
            assertEquals(modified, File(root, "result.txt").lastModified())
            assertEquals(1, c.sessions.messages(session).count { it.role == Role.USER && it.text.startsWith(task.prompt) })
        }
    }

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun find(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (match(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun await(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < end) { find(match)?.let { return it }; SystemClock.sleep(100) }
        screenshot("schedule-test-failed")
        error("Missing scheduling UI node")
    }
    private fun awaitText(text: String) = await { it.text?.toString() == text }
    private fun tap(text: String) {
        // Sheet and keyboard animations change coordinates after semantics first appear.
        SystemClock.sleep(650)
        val node = await { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        android.util.Log.i("SchedulingUi", "Tap $text at $bounds")
        val time = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        SystemClock.sleep(350)
    }
    private fun setText(text: String) {
        val field = await { it.isEditable }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
    }
    private fun scrollTo(text: String) {
        SystemClock.sleep(700)
        repeat(10) {
            find { it.text?.toString() == text }?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            SystemClock.sleep(300)
            val node = find { it.text?.toString() == text }
            val bounds = android.graphics.Rect()
            node?.getBoundsInScreen(bounds)
            val screen = android.graphics.Rect()
            (find { it.isScrollable } ?: instrumentation.uiAutomation.rootInActiveWindow)?.getBoundsInScreen(screen)
            if (node?.isVisibleToUser == true && !bounds.isEmpty && bounds.top >= screen.top && bounds.bottom < screen.bottom - 8) return
            find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(250)
        }
        awaitText(text)
    }
    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(c.appContext.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

private enum class GatewayMode { REPLY, HOLD, RECOVER }

/** A loopback SSE provider exercises the production HTTP path without an external account. */
private class FixtureGateway(private val mode: GatewayMode) : java.io.Closeable {
    private val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val port get() = server.localPort
    val requests = AtomicInteger()
    val firstRequest = CompletableDeferred<Unit>()
    val secondRequest = CompletableDeferred<Unit>()
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<java.net.Socket>()
    private val executor = java.util.concurrent.Executors.newCachedThreadPool()
    init {
        executor.execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets.add(socket)
                executor.execute {
                    try {
                        val input = socket.getInputStream()
                        val header = java.io.ByteArrayOutputStream()
                        var suffix = 0
                        while (suffix != 0x0d0a0d0a) {
                            val byte = input.read(); if (byte < 0) return@execute
                            header.write(byte); suffix = (suffix shl 8) or byte
                        }
                        val headers = header.toString("UTF-8")
                        val length = headers.lineSequence().firstOrNull { it.startsWith("Content-Length:", true) }
                            ?.substringAfter(':')?.trim()?.toInt() ?: 0
                        val body = ByteArray(length)
                        var received = 0
                        while (received < length) { val count = input.read(body, received, length - received); if (count < 0) return@execute; received += count }
                        val out = socket.getOutputStream().bufferedWriter()
                        if (headers.startsWith("GET")) {
                            val json = """{"data":[{"id":"fixture-main"},{"id":"fixture-alternative"}]}"""
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${json.toByteArray().size}\r\nConnection: close\r\n\r\n$json"); out.flush()
                        } else {
                            val attempt = requests.incrementAndGet()
                            firstRequest.complete(Unit)
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"); out.flush()
                            when {
                                mode == GatewayMode.HOLD || mode == GatewayMode.RECOVER && attempt == 2 -> {
                                    secondRequest.complete(Unit)
                                    while (!socket.isClosed) { out.write(": waiting\n\n"); out.flush(); Thread.sleep(200) }
                                }
                                mode == GatewayMode.RECOVER && attempt == 1 -> {
                                    val arguments = kotlinx.serialization.json.JsonPrimitive("""{"path":"result.txt","content":"Saved progress"}""").toString()
                                    out.write("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"write-once\",\"type\":\"function\",\"function\":{\"name\":\"write_file\",\"arguments\":$arguments}}]}}]}\n\n")
                                    out.write("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"); out.flush()
                                }
                                else -> {
                                    out.write("data: {\"choices\":[{\"delta\":{\"content\":\"Completed after connection returned\"}}]}\n\n")
                                    out.write("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"); out.flush()
                                }
                            }
                        }
                    } catch (_: Exception) { /* Client cancellation closes held streams. */ }
                    finally { sockets.remove(socket); socket.close() }
                }
            }
        }
    }
    override fun close() { server.close(); sockets.toList().forEach { it.close() }; executor.shutdownNow() }
}
