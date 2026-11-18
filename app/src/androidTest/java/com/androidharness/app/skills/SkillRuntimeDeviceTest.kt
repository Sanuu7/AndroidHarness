package com.androidharness.app.skills

import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.config.HarnessConfigRepository
import com.androidharness.app.data.env.ShellTierRouter
import com.androidharness.app.plugins.PluginManager
import com.androidharness.app.plugins.PluginOrigin
import com.androidharness.app.tools.ShellTool
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Installs a Claude Code skill package the way a user would and runs its bundled script with the shell sandbox on.
 *
 * Push a plugin repo checkout (`.claude-plugin/plugin.json`, `skills/founder-consumer/...`) to
 * `files/skill-runtime-fixture/` of the app under test first; the test is skipped without it. It installs
 * `python` into the app's Linux environment when that is missing, which needs network access.
 */
@RunWith(AndroidJUnit4::class)
class SkillRuntimeDeviceTest {
    private val c get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp).container

    @Test fun installedSkillRunsItsOwnScriptUnderTheSandboxAndCannotBeChanged() = runBlocking {
        val source = File(c.appContext.filesDir, "skill-runtime-fixture")
        assumeTrue("Push the skill package to ${source.path} first.", source.isDirectory)
        withTimeout(900_000) { c.linuxEnv.install(c.linuxEnv.corePackages + "python") }
        assertTrue("python is not installed", c.linuxEnv.isReady && c.linuxEnv.installedContains("python"))

        val root = File(c.appContext.cacheDir, "skill-runtime-${System.nanoTime()}").apply { mkdirs() }
        try {
            val context = object : ContextWrapper(c.appContext) {
                override fun getFilesDir() = root.resolve("app").apply { mkdirs() }
                override fun getCacheDir() = root.resolve("cache").apply { mkdirs() }
            }
            val manager = PluginManager(context, MutableStateFlow(0L), runtime = { null })
            val workspace = FileFs(root.resolve("workspace").apply { mkdirs() })
            val zip = root.resolve("fixture.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                source.walkTopDown().filter { it.isFile }.forEach { file ->
                    out.putNextEntry(ZipEntry("repo-envelope/" + file.relativeTo(source).invariantSeparatorsPath))
                    out.write(file.readBytes()); out.closeEntry()
                }
            }
            manager.install(manager.prepare(PluginOrigin("archive", Uri.fromFile(zip).toString())), manager.workspaceScope(workspace), "Fixture project")
            val config = HarnessConfigRepository(context, c.keys, manager, manager.revision).load(workspace)
            val store = c.skills.forWorkspace(workspace).configured(config).freeze()

            val viewed = store.view("founder-consumer").getOrThrow().content
            assertFalse(viewed, viewed.contains("\${CLAUDE_SKILL_DIR}"))
            assertTrue(viewed, viewed.contains("use this app's task tool"))
            val skillDir = store.folders().first { it.name == "founder-consumer" }
            val script = skillDir.resolve("panel.py")
            assertTrue(script.path, viewed.contains(script.path))

            val shell = ShellTool(c.linuxEnv, ShellTierRouter(c.appContext, c.shizuku, c.linuxEnv))
            val ctx = ToolContext(workspace, sandboxOff = false, skills = store)
            fun sh(command: String) = runBlocking { shell.execute(buildJsonObject { put("command", command) }, ctx) }
            fun snapshot() = skillDir.walkTopDown().filter { it.isFile && "__pycache__" !in it.path }.associate { it.path to it.length() }
            val before = snapshot()

            listOf(
                "mkdir -p founder",
                "cp \"${skillDir.path}/customer.example.json\" founder/customer.json",
                "cp \"${skillDir.path}/pitch.example.md\" founder/pitch.md",
                "python3 \"${script.path}\" init --customer founder/customer.json --pitch founder/pitch.md --n 20",
                "python3 \"${script.path}\" prompts",
                "python3 \"${script.path}\" status",
            ).forEach { command ->
                val result = sh(command)
                println("STEP ok=${result.ok} $command\n${result.output.take(500)}")
                assertTrue("$command\n${result.output}", result.ok)
            }
            val generated = workspace.shellRoot!!.resolve("founder/panel")
            assertTrue("founder/panel was not written to the workspace", generated.isDirectory && generated.walkTopDown().any { it.isFile })
            assertEquals("The script must not write into its own skill folder", before, snapshot())

            listOf(
                "rm \"${script.path}\"",
                "echo x > \"${skillDir.path}/note.txt\"",
                "cp founder/pitch.md \"${skillDir.path}/pitch.example.md\"",
                "cd \"${skillDir.path}\" && ls",
            ).forEach { command ->
                val result = sh(command)
                println("REFUSED ok=${result.ok} $command\n${result.output.take(200)}")
                assertFalse("$command should be refused", result.ok)
                assertTrue(result.output, result.output.startsWith("Blocked"))
            }
            assertEquals(before, snapshot())
        } finally { root.deleteRecursively() }
    }
}
