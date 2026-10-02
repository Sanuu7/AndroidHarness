package com.androidharness.app.local

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Tests conversion and inference with untrained tiny weights, not answer quality. */
@RunWith(AndroidJUnit4::class)
class LocalFileImportDeviceTest {
    @Test(timeout = 90_000) fun safetensorsFolderAndGgufFileImportPersistAndRun() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = (context.applicationContext as HarnessApp).container.localModels
        val imported = mutableListOf<String>()
        val warnings = launch { manager.consent.warning.filterNotNull().collect { it.continueAnyway() } }
        try {
            withTimeout(60_000) {
                val tree = DocumentsContract.buildTreeDocumentUri("com.androidharness.localimport.fixture", "fixture")
                val converted = manager.importFromFiles(tree, LocalModelFormat.SAFETENSORS) {}
                imported += converted.id
                assertTrue(converted.localFile)
                assertEquals(LocalModelFormat.SAFETENSORS, converted.format)
                assertTrue(converted.id in manager.installed.value)
                val root = File(context.noBackupFilesDir, "local-models")
                val source = File(root, "${converted.id}.gguf")
                assertTrue(source.length() > 24)
                val copied = manager.importFromFiles(Uri.fromFile(source), LocalModelFormat.GGUF) {}
                imported += copied.id
                assertTrue(source.isFile)
                assertTrue(copied.id in manager.installed.value)
                assertEquals(converted.bytes, copied.bytes)
                assertEquals(64, copied.sha256.length)
                val restored = CustomModelRegistry(root)
                assertNull(restored.loadError)
                imported.forEach { id -> assertTrue(restored.models.first { it.id == id }.localFile) }
                assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("file-import-") })
                manager.saveLimits(copied.id, LocalModelLimits(context = 512, input = 384, output = 128, threads = 2))
                val result = manager.generate(copied.id, arrayOf("user"), arrayOf("Hello".toByteArray()), 8) {}
                assertTrue("No prompt evaluated", result[0] > 0)
                assertTrue("No tokens generated", result[1] > 0)
                println("LOCAL_FILE_IMPORT_OK: safetensors converted, GGUF copied, persisted, ${result[1]} tokens")
            }
        } finally {
            warnings.cancelAndJoin()
            imported.asReversed().forEach { manager.remove(it) }
        }
    }
}
