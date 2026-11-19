package com.androidharness.app.ui.chat

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.ui.chat.components.InlineActivityRow
import com.androidharness.app.ui.chat.components.WorkState
import com.androidharness.app.ui.theme.HarnessTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An open inline activity line can hold a card whose details scroll in a lazy list.
 * The line's guide must not ask that content for its intrinsic height, which Compose
 * refuses for lazy layouts and used to crash the app mid-frame.
 */
@RunWith(AndroidJUnit4::class)
class InlineActivityNestingDeviceTest {
    @Test fun openLineWithLazyContentDoesNotCrash() {
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            var open by mutableStateOf(false)
            scenario.onActivity { activity -> activity.setContent { HarnessTheme(ThemeMode.DARK, false) {
                Surface(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
                        item {
                            InlineActivityRow(Icons.Filled.Build, "Nested work", WorkState.Done, open, onToggle = { open = !open }) {
                                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                                    items(30) { Text("NESTED_ROW_$it") }
                                }
                            }
                        }
                    }
                }
            } } }
            SystemClock.sleep(300)
            scenario.onActivity { open = true }
            // Let the expand animation run through several frames.
            SystemClock.sleep(1500)
            var drawn = false
            scenario.onActivity { activity ->
                drawn = !activity.isFinishing && activity.window.decorView.isAttachedToWindow
            }
            assertTrue("Activity should survive opening the line", drawn)
        }
    }
}
