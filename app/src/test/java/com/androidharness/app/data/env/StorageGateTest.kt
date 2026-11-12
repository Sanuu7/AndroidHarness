package com.androidharness.app.data.env

import com.androidharness.app.tools.ToolFailure
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class StorageGateTest {
    private val pkg = "com.androidharness.app"
    private var granted = false
    private var clock = 0L

    private fun install() = StorageGate.install(pkg, granted = { granted }, now = { clock })

    @After fun tearDown() = StorageGate.reset()

    private fun blocked(block: () -> Unit): String = try {
        block(); fail("expected a storage block"); ""
    } catch (e: ToolFailure) { e.message.orEmpty() }

    @Test fun `shared storage aliases need the grant and the app's own folders do not`() {
        for (path in listOf("/sdcard", "/sdcard/Download/a.txt", "/storage/emulated/0/Documents", "/storage/emulated/10/DCIM",
            "/storage/1A2B-3C4D/Music", "/storage/self/primary/x", "/mnt/sdcard/x", "/data/media/0/x",
            "/storage/emulated/0/Android/data/other.app/files", "/data/user/0/$pkg/../../../../sdcard/Download")) {
            assertTrue(path, SharedStorage.needsGrant(path, pkg))
        }
        for (path in listOf("/storage/emulated/0/Android/data/$pkg/files/workspace", "/storage/emulated/0/Android/data/$pkg",
            "/storage/emulated/0/Android/media/$pkg/x", "/data/user/0/$pkg/files", "/data/local/tmp", "/system/bin", "/sdcardx", "/")) {
            assertFalse(path, SharedStorage.needsGrant(path, pkg))
        }
    }

    @Test fun `nothing is blocked before install or once access is granted`() {
        StorageGate.requireFiles("read_file", File("/sdcard/Download/a.txt"))
        install()
        granted = true
        StorageGate.requireFiles("read_file", File("/sdcard/Download/a.txt"))
        assertNull(StorageGate.request.value)
    }

    @Test fun `a file tool on shared storage is blocked and the user is asked`() {
        install()
        val message = blocked { StorageGate.requireFiles("read_file", File("/sdcard/Download/a.txt")) }
        assertTrue(message, message.startsWith("Blocked: read_file needs shared storage (/sdcard/Download/a.txt)"))
        assertTrue(message, "The app is asking the user" in message)
        assertEquals(StorageAccessRequest("/sdcard/Download/a.txt"), StorageGate.request.value)
        StorageGate.requireFiles("read_file", File("/storage/emulated/0/Android/data/$pkg/files/workspace/a.txt"))
    }

    @Test fun `declining stays quiet for a while, then asks again`() {
        install()
        blocked { StorageGate.requireFiles("list_dir", File("/sdcard")) }
        StorageGate.decline()
        assertNull(StorageGate.request.value)
        val message = blocked { StorageGate.requireFiles("list_dir", File("/sdcard")) }
        assertTrue(message, "chose not to grant" in message)
        assertNull("no second dialog right after Not now", StorageGate.request.value)
        clock += 11 * 60_000L
        blocked { StorageGate.requireFiles("list_dir", File("/sdcard")) }
        assertNotNull(StorageGate.request.value)
    }

    @Test fun `granting clears the dialog and the next call passes`() {
        install()
        blocked { StorageGate.requireFiles("list_dir", File("/sdcard")) }
        granted = true
        StorageGate.refresh()
        assertNull(StorageGate.request.value)
        StorageGate.requireFiles("list_dir", File("/sdcard"))
    }

    @Test fun `commands pass through a privileged shell and are blocked as the app user`() = runBlocking {
        install()
        StorageGate.installPrivilegedRoute { true }
        StorageGate.awaitCommand("shell", File("/sdcard/project"))
        assertNull(StorageGate.request.value)
        StorageGate.installPrivilegedRoute { false }
        // No screen is observing in a unit test, so the call fails at once instead of waiting.
        val message = try { StorageGate.awaitCommand("shell", File("/sdcard/project")); "" } catch (e: ToolFailure) { e.message.orEmpty() }
        assertTrue(message, "privileged shell" in message)
        assertNotNull(StorageGate.request.value)
        StorageGate.awaitCommand("shell", File("/storage/emulated/0/Android/data/$pkg/files/workspace"))
    }

    @Test fun `an app user command naming shared storage gets a note, and asks only when it failed`() {
        install()
        assertNull(StorageGate.commandNote("ls -la && cat notes.txt", failed = true))
        assertNull(StorageGate.commandNote("ls /storage/emulated/0/Android/data/$pkg/files", failed = true))
        assertNull(StorageGate.commandNote("grep -r my/sdcard/thing .", failed = true))
        assertNotNull(StorageGate.commandNote("ls /sdcard/Download", failed = false))
        assertNull(StorageGate.request.value)
        assertNotNull(StorageGate.commandNote("cp \"/storage/emulated/0/DCIM/a.jpg\" .", failed = true))
        assertEquals(StorageAccessRequest("/storage/emulated/0/DCIM/a.jpg"), StorageGate.request.value)
        granted = true
        assertNull(StorageGate.commandNote("ls /sdcard/Download", failed = true))
    }
}
