package com.androidharness.app.ui.chat

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.agent.QuestionRequest
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.ui.chat.components.ChatAppearance
import com.androidharness.app.ui.chat.components.QuestionCard
import com.androidharness.app.ui.theme.HarnessTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuestionCardDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val questionText = "How would you like me to improve the chat?"
    private val choices = listOf("Polish the current design (Recommended)", "Try a fresh direction with a different layout", "Focus on accessibility first")

    @Test fun singleChoiceCanBeReviewedChangedAndSentOnlyOnce() = runBlocking {
        val request = QuestionRequest("single", questionText, choices)
        val calls = AtomicInteger()
        val theme = mutableStateOf(ThemeMode.LIGHT)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host -> host.setContent { Page(theme.value) { QuestionCard(request, onAnswer = { calls.incrementAndGet(); request.response.complete(it) }) } } }
            assertFalse(clickTarget(await("Send answer")).isEnabled)
            tap(choices[0]); tap(choices[2])
            assertFalse("Choosing an option must not send it", request.response.isCompleted)
            screenshot("question-single-light")
            assertTrue(chosen(choices[2]))
            assertFalse(chosen(choices[0]))
            val bounds = Rect().also(selectionTarget(await(choices[2]))::getBoundsInScreen)
            assertTrue("Full choice row must be a reachable target", bounds.height() >= 48 * instrumentation.targetContext.resources.displayMetrics.density)
            scenario.onActivity { theme.value = ThemeMode.DARK }
            await(choices[2]); screenshot("question-single-dark")
            tap("Send answer")
            assertEquals(choices[2], withTimeout(5000) { request.response.await() })
            assertFalse(clickTarget(await("Answer sent")).isEnabled)
            clickTarget(await("Answer sent")).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            assertEquals(1, calls.get())
        }
    }

    @Test fun multipleChoicesAndWrittenDetailSurviveActivityRecreation() = runBlocking {
        val request = QuestionRequest("multi", "Which parts should I focus on?", listOf("Layout", "Typography", "Motion"), multiSelect = true)
        val content: @Composable () -> Unit = { Page { QuestionCard(request, onAnswer = { request.response.complete(it) }) } }
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent(content = content) }
            tap("Typography"); tap("Layout"); tap("Motion"); tap("Motion")
            tap("Add a note"); write("Keep it compact.")
            assertFalse(request.response.isCompleted)
            screenshot("question-multiple")
            scenario.recreate()
            scenario.onActivity { it.setContent(content = content) }
            assertTrue(chosen("Layout"))
            assertTrue(chosen("Typography"))
            assertFalse(chosen("Motion"))
            await("Keep it compact.", contains = true)
            tap("Send answer")
            assertEquals("Layout; Typography; Keep it compact.", withTimeout(5000) { request.response.await() })
        }
    }

    @Test fun ownAnswerIsAnAlternativeAndHiddenDraftNeverLeaksIntoSelectedAnswer() = runBlocking {
        val request = QuestionRequest("custom", questionText, choices)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { Page { QuestionCard(request, onAnswer = { request.response.complete(it) }) } } }
            tap(choices[0]); tap("Write your own answer")
            assertFalse(chosen(choices[0]))
            write("Start with the question cards.")
            tap(choices[1])
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable })
            tap("Write your own answer")
            await("Start with the question cards.", contains = true)
            tap(choices[2]); tap("Send answer")
            assertEquals(choices[2], withTimeout(5000) { request.response.await() })
        }
    }

    @Test fun freeAnswerStaysCompactAndChangedQuestionWithReusedIdStartsEmpty() = runBlocking {
        val first = QuestionRequest("reused", "What would make this easier to use?", emptyList())
        val request = mutableStateOf(first)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { Page { QuestionCard(request.value, onAnswer = { answer -> request.value.response.complete(answer) }) } } }
            write("   ")
            assertFalse(clickTarget(await("Send answer")).isEnabled)
            write((1..10).joinToString("\n") { "Detail $it" })
            val bounds = Rect().also(await("Written answer")::getBoundsInScreen)
            val metrics = instrumentation.targetContext.resources.displayMetrics
            val scale = instrumentation.targetContext.resources.configuration.fontScale
            assertTrue("A long written answer should scroll inside a bounded field", bounds.height() <= (4 * 24 * scale + 56) * metrics.density)
            write("  Keep choices easy to read.  ")
            screenshot("question-written")
            tap("Send answer")
            assertEquals("Keep choices easy to read.", withTimeout(5000) { first.response.await() })
            val next = QuestionRequest("reused", "Which layout should I use?", listOf("Compact", "Comfortable"))
            scenario.onActivity { request.value = next }
            await("Which layout should I use?")
            assertFalse(clickTarget(await("Send answer")).isEnabled)
            tap("Comfortable"); tap("Send answer")
            assertEquals("Comfortable", withTimeout(5000) { next.response.await() })
        }
    }

    @Composable private fun Page(theme: ThemeMode = ThemeMode.LIGHT, content: @Composable () -> Unit) {
        HarnessTheme(themeMode = theme, dynamicColor = false) { ChatAppearance {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) { content() }
            }
        } }
    }

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        node ?: return null
        node.refresh()
        if (node.isVisibleToUser && predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun await(text: String, contains: Boolean = false): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 12_000
        var attempt = 0
        do {
            find(root()) { node ->
                listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).any { if (contains) it.contains(text) else it == text }
            }?.let { return it }
            if (++attempt % 4 == 0) find(instrumentation.uiAutomation.rootInActiveWindow) { it.isScrollable && !it.isEditable }
                ?.performAction(if (attempt % 32 < 16) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(120)
        } while (SystemClock.uptimeMillis() < deadline)
        screenshot("question-failure")
        throw AssertionError("Expected visible: $text")
    }
    private fun clickTarget(start: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var node = start
        while (!node.isClickable && node.parent != null) node = node.parent
        node.refresh()
        return node
    }
    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow
    }
    private fun selectionTarget(start: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var node = start
        // Android deliberately omits a click action from an already-selected radio row.
        while (!node.isCheckable && node.parent != null) node = node.parent
        node.refresh()
        return node
    }
    private fun chosen(text: String) = selectionTarget(await(text)).let { it.isSelected || it.isChecked }
    private fun tap(text: String) {
        val node = await(text)
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        assertTrue("Click $text", clickTarget(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(160)
    }
    private fun write(text: String) {
        await("Written answer")
        val field = requireNotNull(find(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable })
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        assertTrue("Write answer", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        SystemClock.sleep(120)
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(250)
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "question-card-qa").apply { mkdirs() }
        val semantics = StringBuilder()
        fun capture(node: AccessibilityNodeInfo?, depth: Int = 0) {
            node ?: return
            node.refresh()
            semantics.append(" ".repeat(depth)).append("${node.className} text=${node.text} desc=${node.contentDescription} clickable=${node.isClickable} selected=${node.isSelected} checked=${node.isChecked} checkable=${node.isCheckable} editable=${node.isEditable}\n")
            for (i in 0 until node.childCount) capture(node.getChild(i), depth + 1)
        }
        capture(root())
        File(dir, "$name.txt").writeText(semantics.toString())
        instrumentation.uiAutomation.takeScreenshot()!!.let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
