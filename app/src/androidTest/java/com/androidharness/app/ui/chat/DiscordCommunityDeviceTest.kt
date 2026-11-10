package com.androidharness.app.ui.chat

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.R
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.ui.chat.components.DISCORD_INVITE_URL
import com.androidharness.app.ui.chat.components.EmptyState
import com.androidharness.app.ui.theme.HarnessTheme
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiscordCommunityDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun label(id: Int) = context.getString(id)

    @Test fun inviteLinkPointsAtTheCommunityServer() {
        assertEquals("https://discord.gg/NdC5K3XeWF", DISCORD_INVITE_URL)
    }

    @Test fun dismissingFundingMessagePersistsAcrossRecreationAndBothSections(): Unit = runBlocking {
        val settings = (context.applicationContext as HarnessApp).container.settings
        val saved = settings.donationNoticeDismissed.first()
        val chat = mutableStateOf(true)
        fun display(host: ChatListTestActivity) = host.setContent {
            HarnessTheme(ThemeMode.LIGHT, dynamicColor = false) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    EmptyState(true, {}, {}, chatOnly = chat.value, modifier = Modifier.fillMaxSize())
                }
            }
        }
        try {
            settings.setDonationNoticeDismissed(false)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity(::display)
                reveal(label(R.string.donate_funding_message))
                screenshot("donation-funding-message")
                tap(label(R.string.donate_dismiss_message))
                awaitCondition { find(label(R.string.donate_funding_message)) == null }
                assertTrue(com.androidharness.app.data.SettingsRepository(context).donationNoticeDismissed.first())
                reveal(label(R.string.ui_donate))
                reveal(label(R.string.discord_join_title))
                screenshot("donation-message-dismissed")
                scenario.recreate()
                scenario.onActivity(::display)
                reveal(label(R.string.discord_join_title))
                assertNull(find(label(R.string.donate_funding_message)))
                scenario.onActivity { chat.value = false }
                reveal(label(R.string.discord_join_title))
                assertNull(find(label(R.string.donate_funding_message)))
            }
        } finally { settings.setDonationNoticeDismissed(saved) }
    }

    @Test fun chatAndCodeOfferDiscordBelowCoffeeWithLargeText() {
        val chat = mutableStateOf(true)
        val dark = mutableStateOf(false)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host -> host.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    HarnessTheme(if (dark.value) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                        Surface(Modifier.fillMaxSize()) {
                            Box(Modifier.width(320.dp).fillMaxHeight().safeDrawingPadding()) {
                                EmptyState(true, {}, {}, chatOnly = chat.value, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            } }
            for (isChat in listOf(true, false)) for (isDark in listOf(false, true)) {
                scenario.onActivity { chat.value = isChat; dark.value = isDark }
                reveal(label(R.string.discord_join_title))
                // The funding notice can still be expanding after its saved dismissal loads.
                awaitCondition {
                    val tree = nodes()
                    val discord = tree.firstOrNull { matches(it, label(R.string.discord_join_title)) }
                    val coffee = tree.firstOrNull { matches(it, label(R.string.ui_donate)) }
                    discord != null && coffee != null && bounds(discord).top >= bounds(coffee).bottom
                }
                assertNotNull("Subtitle stays visible at large fonts", find(label(R.string.discord_join_subtitle)))
                assertNull("UPI donation is gone", find("Donate with UPI"))
                screenshot("discord-${if (isChat) "chat" else "code"}-${if (isDark) "dark" else "light"}-row")
            }
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo) {
            add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun matches(node: AccessibilityNodeInfo, text: String) = node.isVisibleToUser &&
        (node.text?.toString()?.lineSequence()?.any { line -> line.equals(text, true) } == true || node.contentDescription?.toString() == text)
    private fun find(text: String) = nodes().firstOrNull { matches(it, text) }
    private fun await(text: String): AccessibilityNodeInfo {
        awaitCondition { find(text) != null }
        return requireNotNull(find(text))
    }
    private fun awaitCondition(timeoutMs: Long = 10_000, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (!predicate() && SystemClock.uptimeMillis() < until) SystemClock.sleep(60)
        assertTrue("Expected UI state was not reached", predicate())
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun reveal(text: String): AccessibilityNodeInfo {
        repeat(12) {
            find(text)?.let { return it }
            nodes().lastOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        return await(text)
    }
    private fun tap(text: String) {
        reveal(text)
        var previous = Rect()
        var stableFrames = 0
        var target = Rect()
        awaitCondition {
            var node = find(text) ?: return@awaitCondition false
            while (!node.isClickable && node.parent != null) node = node.parent
            target = bounds(node)
            if (target == previous && node.isEnabled) stableFrames++ else stableFrames = 0
            previous = Rect(target)
            stableFrames >= 4 && !target.isEmpty
        }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, target.exactCenterX(), target.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (action == android.view.MotionEvent.ACTION_DOWN) SystemClock.sleep(80)
        }
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(400)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
