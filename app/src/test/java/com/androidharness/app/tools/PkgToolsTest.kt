package com.androidharness.app.tools

import com.androidharness.app.data.env.PackageIndex
import com.androidharness.app.data.env.PkgMeta
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PkgToolsTest {

    private val samplePackages = """
Package: jq
Version: 1.7.1
Installed-Size: 450
Depends: libonig, libandroid-support
Filename: dists/stable/main/binary-aarch64/jq_1.7.1_aarch64.deb
Size: 184320
SHA256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
Description: Command-line JSON processor
 jq is like sed for JSON data - you can use it to slice and
 filter and map and transform structured data with ease.

Package: ripgrep
Version: 14.1.0
Filename: dists/stable/main/binary-aarch64/ripgrep_14.1.0_aarch64.deb
Size: 1945600
SHA256: abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789
Description: Fast search tool that respects your gitignore
 ripgrep is a line-oriented search tool that recursively
 searches your current directory for a regex pattern.
""".trimIndent()

    @Test
    fun `package index parses metadata description and installed size`() {
        val parsed = PackageIndex.parse(samplePackages)
        assertEquals(2, parsed.size)

        val jq = parsed["jq"]
        assertNotNull(jq)
        assertEquals("jq", jq?.name)
        assertEquals("1.7.1", jq?.version)
        assertEquals(450L, jq?.installedSize)
        assertEquals(184320L, jq?.size)
        assertEquals(listOf("libonig", "libandroid-support"), jq?.depends)
        assertEquals("Command-line JSON processor", jq?.description)

        val rg = parsed["ripgrep"]
        assertNotNull(rg)
        assertEquals("ripgrep", rg?.name)
        assertEquals("14.1.0", rg?.version)
        assertEquals("Fast search tool that respects your gitignore", rg?.description)
        assertTrue(rg?.depends?.isEmpty() == true)
    }

    @Test
    fun `tool metadata and read-only attributes are correct`() {
        val installTool = PkgInstallTool(
            isReady = { false },
            installedPackages = { emptyList() },
            installPackages = {},
        )
        val searchTool = PkgSearchTool(
            searcher = { emptyList() },
            installedPackages = { emptyList() },
        )
        val listTool = PkgListTool(
            installedPackages = { emptyList() },
        )

        assertEquals("pkg_install", installTool.name)
        assertFalse("pkg_install must NOT be read-only so it triggers approvals", installTool.isReadOnly)

        assertEquals("pkg_search", searchTool.name)
        assertTrue("pkg_search must be read-only", searchTool.isReadOnly)

        assertEquals("pkg_list", listTool.name)
        assertTrue("pkg_list must be read-only", listTool.isReadOnly)
    }

    @Test
    fun `pkg_install fails cleanly when environment is not ready`() = runBlocking {
        val installTool = PkgInstallTool(
            isReady = { false },
            installedPackages = { emptyList() },
            installPackages = {},
        )
        val dummyContext = ToolContext(createMockWorkspace())

        val args = buildJsonObject {
            put("packages", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("jq")) })
        }
        val result = installTool.execute(args, dummyContext)
        assertFalse(result.ok)
        assertTrue(result.output.contains("not ready", ignoreCase = true))
    }

    @Test
    fun `pkg_install rejects empty package arguments`() = runBlocking {
        val installTool = PkgInstallTool(
            isReady = { true },
            installedPackages = { emptyList() },
            installPackages = {},
        )
        val dummyContext = ToolContext(createMockWorkspace())

        val args = buildJsonObject {
            put("packages", buildJsonArray { })
        }
        val result = installTool.execute(args, dummyContext)
        assertFalse(result.ok)
        assertTrue(result.output.contains("Missing package name", ignoreCase = true))
    }

    @Test
    fun `pkg_install installs packages successfully and reports newly installed`() = runBlocking {
        val installedState = mutableListOf("bash", "git")
        val installTool = PkgInstallTool(
            isReady = { true },
            installedPackages = { installedState },
            installPackages = { pkgs -> installedState.addAll(pkgs) },
        )
        val dummyContext = ToolContext(createMockWorkspace())

        val args = buildJsonObject {
            put("packages", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("jq")) })
            put("reason", "Needed to parse JSON files")
        }
        val result = installTool.execute(args, dummyContext)
        assertTrue(result.ok)
        assertTrue(result.output.contains("Successfully installed: jq"))
        assertTrue(installedState.contains("jq"))
    }

    @Test
    fun `pkg_search rejects empty query`() = runBlocking {
        val searchTool = PkgSearchTool(
            searcher = { emptyList() },
            installedPackages = { emptyList() },
        )
        val dummyContext = ToolContext(createMockWorkspace())

        val args = buildJsonObject {
            put("query", "")
        }
        val result = searchTool.execute(args, dummyContext)
        assertFalse(result.ok)
        assertTrue(result.output.contains("Query parameter is required", ignoreCase = true))
    }

    @Test
    fun `pkg_search formats matching packages and highlights installed`() = runBlocking {
        val dummyPackages = listOf(
            PkgMeta(
                name = "jq",
                version = "1.7.1",
                depends = emptyList(),
                filename = "jq.deb",
                size = 184320,
                sha256 = "abc",
                description = "Command-line JSON processor",
            ),
            PkgMeta(
                name = "jql",
                version = "7.1.0",
                depends = emptyList(),
                filename = "jql.deb",
                size = 1200000,
                sha256 = "def",
                description = "A JSON query language CLI tool",
            ),
        )
        val searchTool = PkgSearchTool(
            searcher = { query -> dummyPackages.filter { it.name.contains(query) } },
            installedPackages = { listOf("jq") },
        )
        val dummyContext = ToolContext(createMockWorkspace())

        val args = buildJsonObject {
            put("query", "jq")
        }
        val result = searchTool.execute(args, dummyContext)
        assertTrue(result.ok)
        assertTrue(result.output.contains("Found 2 package(s)"))
        assertTrue(result.output.contains("jq (1.7.1)"))
        assertTrue(result.output.contains("[installed]"))
        assertTrue(result.output.contains("Command-line JSON processor"))
    }

    @Test
    fun `pkg_list lists installed packages`() = runBlocking {
        val listTool = PkgListTool(
            installedPackages = { listOf("bash", "git", "jq") },
        )
        val dummyContext = ToolContext(createMockWorkspace())

        val result = listTool.execute(buildJsonObject {}, dummyContext)
        assertTrue(result.ok)
        assertTrue(result.output.contains("Installed packages (3)"))
        assertTrue(result.output.contains("bash, git, jq"))
    }

    private fun createMockWorkspace(): WorkspaceFs {
        val tempDir = File.createTempFile("harness-test", "").apply { delete(); mkdirs() }
        tempDir.deleteOnExit()
        return com.androidharness.app.workspace.FileFs(tempDir)
    }
}
