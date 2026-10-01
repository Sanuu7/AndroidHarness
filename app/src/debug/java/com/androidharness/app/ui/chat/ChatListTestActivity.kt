package com.androidharness.app.ui.chat

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity

/** Real Compose window for chat-list regression tests; excluded from release. */
class ChatListTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
