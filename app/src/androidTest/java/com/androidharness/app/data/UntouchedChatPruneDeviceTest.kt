package com.androidharness.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UntouchedChatPruneDeviceTest {
    @Test fun onlyBlankDefaultChatsThatAreNotKeptAreRemoved() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val repo = SessionRepository(db)
            val blankA = repo.createSession("New chat")
            val blankB = repo.createSession("New chat")
            val open = repo.createSession("New chat")
            val used = repo.createSession("New chat")
            repo.addMessage(used, ChatMessage(role = Role.USER, text = "hello"))
            val drafted = repo.createSession("New chat")
            repo.saveConversation(drafted, ConversationConfig(draftText = "half a question"))
            val named = repo.createSession("Telegram chat")

            assertEquals(2, repo.pruneUntouchedChats(keep = setOf(open), defaultTitles = setOf("New chat")))
            assertNull(repo.session(blankA)); assertNull(repo.session(blankB))
            listOf(open, used, drafted, named).forEach { assertNotNull(it, repo.session(it)) }
        } finally { db.close() }
    }
}
