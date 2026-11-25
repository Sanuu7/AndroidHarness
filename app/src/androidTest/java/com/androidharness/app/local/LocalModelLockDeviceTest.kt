package com.androidharness.app.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Unloading or removing one model must not wait for a chat that another model is running. */
@RunWith(AndroidJUnit4::class)
class LocalModelLockDeviceTest {
    @Test(timeout = 180_000) fun unloadAndRemoveDoNotWaitForAnotherModel() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.filesDir, "local-cache-test.gguf")
        assumeTrue("Place a GGUF test fixture in app files", fixture.isFile)
        val root = File(context.noBackupFilesDir, "local-models").apply { mkdirs() }
        val busyId = "custom-1111111111111111111111111111aaaa"
        val idleId = "custom-2222222222222222222222222222bbbb"
        fun spec(id: String) = LocalModelSpec(id, "Lock test $id", "", "", "fixture.gguf", fixture.length(), "", 1, 1024,
            "Device test", custom = true, maxContext = 512, localFile = true)
        val registry = CustomModelRegistry(root)
        listOf(busyId, idleId).forEach { id ->
            check(registry.models.none { it.id == id })
            registry.add(spec(id))
            fixture.copyTo(File(root, "$id.gguf"))
        }
        val manager = LocalModelManager(context)
        try {
            withTimeout(10_000) { manager.installed.first { busyId in it && idleId in it } }
            manager.acknowledgeResources(spec(busyId), manager.limits(busyId), false)
            val request = """{"messages":[{"role":"user","content":"Count from one to five hundred."}],"tools":[],"enable_thinking":false}""".toByteArray()
            coroutineScope {
                val generation = async(Dispatchers.Default) {
                    runCatching { manager.generateChat(busyId, request, emptyArray(), 400, "lock-test") {} }
                }
                withTimeout(30_000) { manager.running.first { it == busyId } }
                withTimeout(2_000) { manager.gpuDevice() }
                withTimeout(2_000) { manager.stopAndUnload(idleId) }
                withTimeout(5_000) { manager.remove(idleId) }
                assertFalse("Removed model is still installed", idleId in manager.installed.value)
                assertFalse(File(root, "$idleId.gguf").exists())
                withTimeout(15_000) { manager.stopAndUnload(busyId) }
                generation.await()
                assertNull(manager.running.value)
                assertNull(manager.loaded.value)
            }
        } finally {
            manager.releaseCachedModel()
            listOf(busyId, idleId).forEach { id ->
                File(root, "$id.gguf").delete()
                File(root, "$id.json").delete()
                CustomModelRegistry(root).remove(id)
            }
        }
    }
}
