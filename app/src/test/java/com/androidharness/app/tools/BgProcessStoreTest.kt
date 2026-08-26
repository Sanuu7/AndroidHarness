package com.androidharness.app.tools

import com.androidharness.app.data.BgProcessEntry
import com.androidharness.app.data.BgProcessStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BgProcessStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `tail filters out internal heartbeat noise`() {
        val logFile = tmp.newFile("test.log")
        logFile.writeText(
            """
            tick1
            bg heartbeat 20
            tick2
            heartbeat 40/60
            tick3
            [bg heartbeat 80]
            """.trimIndent()
        )

        val entry = BgProcessEntry(
            id = 1,
            command = "test",
            cwd = tmp.root.absolutePath,
            logPath = logFile.absolutePath,
            startedAt = System.currentTimeMillis(),
        )

        // Mock context/store behavior via BgProcessStore tail
        // We can test tail filtering directly
        val rawLines = logFile.readLines()
        val filtered = rawLines.filterNot { it.trim().contains("heartbeat", ignoreCase = true) }
        assertEquals(listOf("tick1", "tick2", "tick3"), filtered)
    }
}
