package com.androidharness.app.data.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SharedToolStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val prefix get() = File(temp.root, "linux").apply { mkdirs() }
    private val metadata get() = File(temp.root, "metadata")
    private suspend fun tool(name: String, dependencies: List<String> = emptyList()): ManagedTool {
        val file = File(prefix, "bin/$name").apply { parentFile!!.mkdirs(); writeText(name) }
        val stamp = StorageFiles.snapshot(prefix, file).single().copy(path = "bin/$name")
        return ManagedTool(name, "1", dependencies, listOf(stamp), true)
    }
    @Test fun `one shared install remains protected until both workspace requirements are released`() = runBlocking {
        val store = SharedToolStore(metadata, prefix)
        store.put(tool("jq")); store.require(listOf("jq"), "Project A"); store.require(listOf("jq"), "Project B")
        val reloaded = SharedToolStore(metadata, prefix)
        assertEquals(setOf("Project A", "Project B"), reloaded.all()["jq"]!!.requirements)
        reloaded.release("jq", "Project A")
        assertNull(reloaded.preview("jq", setOf("jq"), emptySet()))
        assertTrue(reloaded.reason("jq", setOf("jq"), emptySet())!!.contains("Project B"))
        assertEquals("jq", File(prefix, "bin/jq").readText())
        reloaded.release("jq", "Project B")
        assertNotNull(reloaded.preview("jq", setOf("jq"), emptySet()))
    }
    @Test fun `app dependencies reverse dependencies unknown ownership and shared files are retained`() = runBlocking {
        val store = SharedToolStore(metadata, prefix)
        val lib = tool("lib"); store.put(lib); store.put(tool("tool", listOf("lib")))
        assertNotNull(store.reason("lib", setOf("lib", "tool"), emptySet()))
        assertNotNull(store.reason("lib", setOf("lib"), setOf("lib")))
        // A package installed before tracking no longer blocks others; it stays protected itself.
        assertNull(store.reason("lib", setOf("lib", "legacy"), emptySet()))
        assertEquals(PackageBlock.UNTRACKED, store.protection("legacy", setOf("lib", "legacy"), emptySet())!!.block)
        assertEquals(PackageBlock.DEPENDENTS, store.protection("lib", setOf("lib", "tool"), emptySet())!!.block)
        assertEquals(PackageBlock.REQUIRED, store.protection("lib", setOf("lib"), setOf("lib"))!!.block)
        store.put(ManagedTool("other", "1", emptyList(), lib.files, true))
        assertNotNull(store.reason("lib", setOf("lib", "other"), emptySet()))
        store.put(ManagedTool("incomplete", "1", emptyList()))
        assertNull(store.preview("incomplete", setOf("incomplete"), emptySet()))
    }
    @Test fun `stale requirement and changed package block deletion and do not change installed marker`() = runBlocking {
        val store = SharedToolStore(metadata, prefix); store.put(tool("jq"))
        val preview = requireNotNull(store.preview("jq", setOf("jq"), emptySet()))
        store.require(listOf("jq"), "Another project")
        var committed = false
        try { store.remove(preview, setOf("jq"), emptySet()) { committed = true }; fail() } catch (_: IllegalStateException) {}
        assertFalse(committed); assertTrue(File(prefix, "bin/jq").exists())
        store.release("jq", "Another project"); File(prefix, "bin/jq").writeText("changed")
        assertNull(store.preview("jq", setOf("jq"), emptySet()))
    }
    @Test fun `failed marker commit rolls all moved files back and successful removal is complete`() = runBlocking {
        val store = SharedToolStore(metadata, prefix); store.put(tool("jq"))
        val preview = requireNotNull(store.preview("jq", setOf("jq"), emptySet()))
        try { store.remove(preview, setOf("jq"), emptySet()) { error("disk full") }; fail() } catch (_: IllegalStateException) {}
        assertEquals("jq", File(prefix, "bin/jq").readText()); assertFalse(File(metadata, "removal.json").exists())
        val next = requireNotNull(store.preview("jq", setOf("jq"), emptySet()))
        var committed = false
        assertEquals(2L, store.remove(next, setOf("jq"), emptySet()) { committed = true })
        assertTrue(committed); assertFalse(File(prefix, "bin/jq").exists())
        assertFalse(File(metadata, "removing").exists())
    }
}
