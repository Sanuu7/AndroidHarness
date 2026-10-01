package com.androidharness.app.ui.chat

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.ui.chat.components.AssistantText
import com.androidharness.app.ui.chat.components.ThinkingBlock
import com.androidharness.app.ui.chat.components.ToolCallCard
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real chat components and the production list state in a real window. */
@RunWith(AndroidJUnit4::class)
class ChatListScrollDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun historyScrollSurvivesLateConditionalRows() = exerciseList(streaming = false)

    @Test
    fun fingerScrollSurvivesStreamingAndCommits() = exerciseList(streaming = true)

    private fun exerciseList(streaming: Boolean) {
        lateinit var listState: LazyListState
        val tick = mutableIntStateOf(0)
        val bounds = android.graphics.Rect()
        val updating = AtomicBoolean(true)
        lateinit var host: ChatListTestActivity
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    host = activity
                    activity.setContent {
                        MaterialTheme {
                            listState = rememberChatListState()
                            LaunchedEffect(Unit) {
                                // History opens at its end; metadata continues loading after that.
                                listState.requestScrollToItem(HISTORY_SIZE - 4)
                                while (updating.get()) {
                                    withFrameNanos { }
                                    tick.intValue++
                                }
                            }
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                val frame = tick.intValue
                                for (index in 0 until HISTORY_SIZE) {
                                    // Async history metadata adds/removes rows while scrolling.
                                    if (index % 3 == 0 && frame % 12 < 6) {
                                        item("message-$index-thinking", ChatListContentType.Thinking) {
                                            Box(Modifier.animateItem(placementSpec = null, fadeOutSpec = null)) {
                                                ThinkingBlock("Reasoning for saved message $index")
                                            }
                                        }
                                    }
                                    item("message-$index-text", ChatListContentType.AssistantText) {
                                        Box(Modifier.animateItem(placementSpec = null, fadeOutSpec = null)) {
                                            AssistantText("Message $index\n\n" + "Saved history text. ".repeat(12))
                                        }
                                    }
                                    if (index % 4 == 0) {
                                        item("message-$index-tool", ChatListContentType.Tool) {
                                            ToolCallCard(
                                                call = ToolCallData("call-$index", "read_file", "{\"path\":\"test.kt\"}"),
                                                result = null,
                                                running = false,
                                                onOpenFile = { _, _ -> },
                                            )
                                        }
                                    }
                                }
                                if (streaming) {
                                    // Commit a live response, then start another with a new key.
                                    val committed = frame / 30
                                    for (index in 0 until committed) {
                                        item("committed-$index", ChatListContentType.AssistantText) {
                                            AssistantText("Committed response $index\n\n" + "Result. ".repeat(15))
                                        }
                                    }
                                    if (frame % 30 < 29) {
                                        if (frame % 10 < 5) {
                                            item("streaming-$committed-thinking", ChatListContentType.Thinking) {
                                                ThinkingBlock("Reasoning delta $frame", live = true)
                                            }
                                        }
                                        item("streaming-$committed-text", ChatListContentType.AssistantText) {
                                            AssistantText(
                                                "## Streaming $frame\n\n" + "Token ".repeat(frame % 30 + 1),
                                                streaming = true,
                                            )
                                        }
                                    }
                                    if (frame % 16 < 8) {
                                        item("approval", ChatListContentType.Approval) { Text("Approval pending") }
                                    }
                                }
                            }
                        }
                    }
                }
                awaitCondition("history was not measured") { listState.layoutInfo.totalItemsCount > HISTORY_SIZE }
                awaitCondition("opening scroll did not reach history") {
                    listState.layoutInfo.visibleItemsInfo.isNotEmpty() && firstVisibleMessage(listState) >= 50
                }
                instrumentation.runOnMainSync { host.window.decorView.getGlobalVisibleRect(bounds) }
                var before = 0
                instrumentation.runOnMainSync { before = firstVisibleMessage(listState) }
                swipe(bounds, up = false)
                awaitCondition("finger scroll did not move the list") { firstVisibleMessage(listState) < before }
                repeat(12) {
                    // Scroll through history, then jump back to changing live content.
                    swipe(bounds, up = it % 2 == 0)
                    instrumentation.runOnMainSync { listState.requestScrollToItem(listState.layoutInfo.totalItemsCount - 4) }
                    swipe(bounds, up = false)
                }
                instrumentation.runOnMainSync {
                    assertTrue("updates never ran", tick.intValue >= 60)
                    assertTrue("the list lost its visible rows", listState.layoutInfo.visibleItemsInfo.isNotEmpty())
                    if (streaming) {
                        assertTrue("stream commits did not start", tick.intValue / 30 > 0)
                        listState.requestScrollToItem(listState.layoutInfo.totalItemsCount - 4)
                    }
                }
                if (streaming) {
                    awaitCondition("committed responses never became visible") {
                        listState.layoutInfo.visibleItemsInfo.any { it.key.toString().startsWith("committed-") }
                    }
                }
            } finally {
                updating.set(false)
            }
        }
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var satisfied = false
            instrumentation.runOnMainSync { satisfied = condition() }
            if (satisfied) return
            SystemClock.sleep(16)
        }
        throw AssertionError(message)
    }

    private fun firstVisibleMessage(state: LazyListState): Int =
        state.layoutInfo.visibleItemsInfo.first().key.toString().split('-')[1].toInt()

    private fun swipe(bounds: android.graphics.Rect, up: Boolean) {
        val x = bounds.exactCenterX()
        val from = bounds.top + bounds.height() * if (up) 0.7f else 0.3f
        val to = bounds.top + bounds.height() * if (up) 0.3f else 0.7f
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, from)
        for (step in 1..12) {
            SystemClock.sleep(16)
            send(MotionEvent.ACTION_MOVE, from + (to - from) * step / 12)
        }
        send(MotionEvent.ACTION_UP, to)
    }

    private companion object {
        const val HISTORY_SIZE = 120
    }
}
