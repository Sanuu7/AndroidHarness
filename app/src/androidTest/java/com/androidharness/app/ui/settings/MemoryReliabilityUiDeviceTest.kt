package com.androidharness.app.ui.settings

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.R
import com.androidharness.app.core.*
import com.androidharness.app.data.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.chat.components.ChatMemoryReceipt
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MemoryReliabilityUiDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val c get() = (context.applicationContext as HarnessApp).container

    @Test fun customAndUnlimitedEntrySettingsPersistAndEditorsFollowTheSelectedLimit() = runBlocking {
        val before = c.settings.settings.first()
        try {
            c.settings.setMemoryEnabled(true)
            c.settings.setMemoryEntryUnlimited(false)
            c.settings.setMemoryEntryMaxChars(600)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                var scrollToBottom: (() -> Unit)? = null
                scenario.onActivity { activity -> activity.setContent {
                    HarnessTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                        val prefs by c.settings.settings.collectAsStateWithLifecycle(initialValue = before)
                        val work = rememberCoroutineScope()
                        val scroll = rememberScrollState()
                        scrollToBottom = { work.launch { scroll.scrollTo(scroll.maxValue) } }
                        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                                MemoryOptionsSection(c, prefs, onEnabled = {}, onOpen = {}, showManage = false)
                            }
                        }
                    }
                } }
                await { scrollToBottom != null }
                instrumentation.runOnMainSync { scrollToBottom?.invoke() }
                click(context.getString(R.string.memory_entry_max_chars))
                setInput("0")
                await { !saveEnabled() }
                setInput("1200")
                await { saveEnabled() }
                click(context.getString(R.string.settings_memory_save))
                await { runBlocking { c.settings.settings.first().memoryEntryMaxChars == 1_200 } }
                screenshot("memory-custom-entry-limit")
                click(context.getString(R.string.memory_entry_unlimited))
                await { runBlocking { c.settings.settings.first().memoryEntryUnlimited } }
                assertEquals(1_200, c.settings.settings.first().memoryEntryMaxChars)
                screenshot("memory-unlimited-entry-setting")

                var savedText: String? = null
                scenario.onActivity { activity -> activity.setContent {
                    HarnessTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                        val prefs by c.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings(memoryEntryUnlimited = true))
                        MemoryEditDialog(null, emptyList(), onDismiss = {}, onDelete = {}, settings = prefs,
                            onSave = { text, _, _, _, _ -> savedText = text })
                    }
                } }
                val long = "x".repeat(25_000)
                setInput(long)
                await { saveEnabled() }
                awaitText(context.getString(R.string.memory_entry_count_unlimited, long.length))
                screenshot("memory-unlimited-entry-editor")
                click(context.getString(R.string.settings_memory_save))
                await { savedText == long }
                c.settings.setMemoryEntryUnlimited(false)
                setInput("x".repeat(1_201))
                await { !saveEnabled() }
                setInput("x".repeat(1_200))
                await { saveEnabled() }
                screenshot("memory-custom-limit-editor")
            }
        } finally {
            c.settings.setMemoryEnabled(before.memoryEnabled)
            c.settings.setMemoryEntryUnlimited(before.memoryEntryUnlimited)
            c.settings.setMemoryEntryMaxChars(before.memoryEntryMaxChars)
        }
    }

    @Test fun memorySettingsKeepChatCodeAndCrossLookupIndependent() = runBlocking {
        val before = c.settings.settings.first()
        try {
            c.settings.setMemoryEnabled(true)
            c.settings.setSectionMemoryEnabled(MemorySection.CHAT, true)
            c.settings.setSectionMemoryEnabled(MemorySection.CODE, true)
            c.settings.setCrossSectionMemory(MemorySection.CHAT, false)
            c.settings.setCrossSectionMemory(MemorySection.CODE, false)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    HarnessTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                        val prefs by c.settings.settings.collectAsStateWithLifecycle(initialValue = before)
                        val work = rememberCoroutineScope()
                        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                MemoryOptionsSection(c, prefs, onEnabled = { value -> work.launch { c.settings.setMemoryEnabled(value) } }, onOpen = {}, showManage = false)
                            }
                        }
                    }
                } }
                click(context.getString(R.string.memory_chat_cross))
                await { runBlocking { c.settings.settings.first().chatRecallCodeMemory } }
                assertFalse(c.settings.settings.first().codeRecallChatMemory)
                click(context.getString(R.string.memory_code_enabled))
                await { runBlocking { !c.settings.settings.first().codeMemoryEnabled } }
                assertTrue(c.settings.settings.first().chatMemoryEnabled)
                screenshot("memory-separate-settings")
                click(context.getString(R.string.memory_chat_enabled))
                await { runBlocking { !c.settings.settings.first().chatMemoryEnabled } }
            }
        } finally {
            c.settings.setMemoryEnabled(before.memoryEnabled)
            c.settings.setSectionMemoryEnabled(MemorySection.CHAT, before.chatMemoryEnabled)
            c.settings.setSectionMemoryEnabled(MemorySection.CODE, before.codeMemoryEnabled)
            c.settings.setCrossSectionMemory(MemorySection.CHAT, before.chatRecallCodeMemory)
            c.settings.setCrossSectionMemory(MemorySection.CODE, before.codeRecallChatMemory)
        }
    }

    @Test fun updatedReceiptUsesReadableSheetAndEditsPersistAtLargeFonts() = runBlocking {
        val original = requireNotNull(c.memory.add("Receipt fixture ${System.nanoTime()}: prefers accessible replies with useful spacing. " +
            "Keep source paths and technical identifiers intact. ".repeat(5), MemoryCategory.PREFERENCE, MemoryScope.GLOBAL, null, MemorySection.CHAT))
        val messages = listOf(ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("memory-fixture", "remember", "{}"))),
            ChatMessage(Role.TOOL, "Memory updated.\n[memory_id:${original.id}]\n[memory_action:updated]", toolCallId = "memory-fixture", toolName = "remember"))
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                        HarnessTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                            Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) { ChatMemoryReceipt(messages, c, false) }
                            }
                        }
                    }
                } }
                click(context.getString(R.string.chat_memory_updated))
                awaitText(original.text)
                val screen = context.resources.displayMetrics
                // Semantics appear before the bottom sheet finishes sliding onto the screen.
                await {
                    find { it.text?.toString() == original.text }?.let { node ->
                        val rect = bounds(node)
                        rect.height() > 40 * screen.density && rect.bottom <= screen.heightPixels
                    } == true
                }
                val fact = bounds(requireNotNull(find { it.text?.toString() == original.text }))
                assertTrue(fact.left >= 0 && fact.right <= screen.widthPixels)
                assertTrue("Memory text wraps into readable lines", fact.height() > 40 * screen.density)
                screenshot("memory-updated-sheet-large-font")
                click(context.getString(R.string.settings_memory_edit_title))
                await { find { it.isEditable } != null }
                val input = requireNotNull(find { it.isEditable })
                assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Prefers readable replies with clear paragraphs")
                }))
                click(context.getString(R.string.memory_section_code))
                click(context.getString(R.string.settings_memory_save))
                await { runBlocking { c.memory.memories.first().any { it.id == original.id && it.section == "CODE" && it.text == "Prefers readable replies with clear paragraphs" } } }
                awaitText("Prefers readable replies with clear paragraphs")
                screenshot("memory-updated-sheet-saved")
            }
        } finally { c.memory.remove(original.id) }
    }

    @Test fun managerConsolidatesReviewedEntriesAndRestoresOriginals() = runBlocking {
        val ids = mutableListOf<String>()
        val projectId = "memory-ui-${System.nanoTime()}"
        val marker = "Memory cleanup fixture $projectId"
        try {
            repeat(12) { index ->
                ids += requireNotNull(c.memory.add("$marker: fact $index remains available", MemoryCategory.entries[index % 5],
                    MemoryScope.PROJECT, projectId, MemorySection.CODE)).id
            }
            var scrollToBottom: (() -> Unit)? = null
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    HarnessTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                        val scroll = rememberScrollState()
                        val scope = rememberCoroutineScope()
                        scrollToBottom = { scope.launch { scroll.scrollTo(scroll.maxValue) } }
                        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                            Column(Modifier.fillMaxSize().verticalScroll(scroll)) { MemoryManagerSection(c) }
                        }
                    }
                } }
                awaitText(context.getString(R.string.settings_memory_manage))
                click(context.getString(R.string.memory_section_code))
                val group = context.getString(R.string.memory_consolidate_group, 12, context.getString(R.string.memory_section_code), context.getString(R.string.settings_memory_project))
                click(group)
                awaitText(context.getString(R.string.memory_review_digest))
                val digest = requireNotNull(find { it.isEditable }).text.toString()
                assertTrue(digest.contains("Profile:")); assertTrue(digest.contains("Preferences:"))
                assertTrue(digest.contains("Decisions:")); assertTrue(digest.contains("Facts:"))
                screenshot("memory-consolidation-review")
                click(context.getString(R.string.settings_memory_save))
                await { runBlocking { c.memory.memories.first().filter { it.id in ids }.all { it.consolidatedInto != null } } }
                val summaryId = c.memory.memories.first().first { it.id == ids.first() }.consolidatedInto!!
                assertTrue(c.memory.memories.first().single { it.id == summaryId }.isSummary)
                val search = requireNotNull(find { it.isEditable })
                assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, marker)
                }))
                instrumentation.runOnMainSync { scrollToBottom?.invoke() }
                click(context.getString(R.string.memory_restore_originals))
                await { runBlocking { c.memory.memories.first().filter { it.id in ids }.all { it.consolidatedInto == null } } }
                assertEquals(12, c.memory.memories.first().count { it.id in ids })
                assertFalse(c.memory.memories.first().any { it.id == summaryId })
            }
        } finally {
            val saved = c.memory.memories.first()
            saved.filter { it.id in ids }.mapNotNull { it.consolidatedInto }.distinct().forEach { c.memory.restoreOriginals(it) }
            ids.forEach { c.memory.remove(it) }
        }
    }

    private fun awaitText(text: String) = await { find { it.text?.toString() == text } != null }
    private fun setInput(text: String) {
        await { find { it.isEditable } != null }
        assertTrue(requireNotNull(find { it.isEditable }).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
    }
    private fun saveEnabled(): Boolean = find { it.text?.toString() == context.getString(R.string.settings_memory_save) }
        ?.let { generateSequence(it) { node -> node.parent }.all { node -> node.isEnabled } } == true
    private fun click(text: String) {
        awaitText(text)
        var node = requireNotNull(find { it.text?.toString() == text })
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Click $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            if (condition()) return
            SystemClock.sleep(40)
        }
        fail("Memory UI did not reach the expected state")
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }
    private fun screenshot(name: String) {
        SystemClock.sleep(300) // Capture the settled sheet/switch state.
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
