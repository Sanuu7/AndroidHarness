package com.androidharness.app.tools.mcp

import com.androidharness.app.tools.ToolFailure
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class McpModelsTest {

    @Test
    fun `config parser reads the standard mcpServers format`() {
        val text = """
            {"mcpServers": {
                "fs": {"command": "npx", "args": ["-y", "server-fs", "/tmp"], "env": {"ROOT": "/a"}},
                "off": {"command": "slow", "disabled": true}
            }}
        """.trimIndent()
        val configs = McpConfigParser.parse(text)
        assertEquals(2, configs.size)
        val fs = configs.first { it.name == "fs" }
        assertEquals("npx", fs.command)
        assertEquals(listOf("-y", "server-fs", "/tmp"), fs.args)
        assertEquals(mapOf("ROOT" to "/a"), fs.env)
        assertTrue(fs.enabled)
        val off = configs.first { it.name == "off" }
        assertTrue(!off.enabled)
    }

    @Test
    fun `config parser survives garbage and non-command entries`() {
        assertEquals(emptyList<McpServerConfig>(), McpConfigParser.parse("not json"))
        assertEquals(emptyList<McpServerConfig>(), McpConfigParser.parse("{}"))
        assertEquals(
            emptyList<McpServerConfig>(),
            McpConfigParser.parse("""{"mcpServers": {"bad": {"args": ["no command"]}}}"""),
        )
    }

    @Test
    fun `tool names are sanitized to the mcp__server__tool shape`() {
        assertEquals(
            "mcp__github_tools__create_issue",
            McpNames.toolName("GitHub Tools!", "Create Issue?"),
        )
        assertEquals("mcp__x__x", McpNames.toolName("---", "***"))
        // Length caps keep provider-visible names bounded: 5 + 32 + 2 + 64.
        assertEquals(103, McpNames.toolName("s".repeat(100), "t".repeat(200)).length)
    }

    @Test
    fun `schemas are normalized to an object root`() {
        val empty = normalizeMcpSchema(buildJsonObject {})
        assertEquals("object", empty["type"]?.let { (it as JsonPrimitive).content })

        val noType = buildJsonObject { put("properties", buildJsonObject {}) }
        assertEquals("object", normalizeMcpSchema(noType)["type"]?.let { (it as JsonPrimitive).content })

        val withType = buildJsonObject { put("type", "object") }
        assertEquals(withType, normalizeMcpSchema(withType))
    }
}

class McpConnectionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * A minimal stdio MCP server in /bin/sh: echoes the request id back and
     * answers initialize, tools/list and tools/call. The JVM test host is a
     * Linux box, so a shell process is the cheapest protocol double.
     * "@D" becomes a shell dollar (Kotlin templating workaround).
     */
    private val fakeServer = """
        while IFS= read -r line; do
            id=@D(printf '%s' "@Dline" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
            case "@Dline" in
                *'"initialize"'*) printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"fake","version":"1"}}}\n' "@Did" ;;
                *'"tools/list"'*) printf '{"jsonrpc":"2.0","id":%s,"result":{"tools":[{"name":"echo","description":"Echoes","inputSchema":{"type":"object"}}]}}\n' "@Did" ;;
                *'"tools/call"'*) printf '{"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"text","text":"hello from fake"}]}}\n' "@Did" ;;
            esac
        done
    """.trimIndent().replace("@D", "$")

    @Test
    fun `handshake discovery and call work over stdio`() = runBlocking {
        val conn = McpConnection(
            serverName = "fake",
            config = McpServerConfig("fake", "sh"),
            processFactory = { _ ->
                ProcessBuilder("/bin/sh", "-c", fakeServer).start()
            },
        )
        try {
            conn.connect(tmp.root)
            assertEquals(listOf("echo"), conn.tools.map { it.name })
            val (text, isError) = conn.callTool(
                "echo",
                buildJsonObject { put("text", "hi") },
            )
            assertEquals("hello from fake", text)
            assertTrue(!isError)
        } finally {
            conn.close()
        }
    }

    @Test
    fun `a server that dies immediately fails the handshake with ToolFailure`() = runBlocking {
        val conn = McpConnection(
            serverName = "dead",
            config = McpServerConfig("dead", "sh"),
            processFactory = { _ ->
                ProcessBuilder("/bin/sh", "-c", "exit 0").start()
            },
            handshakeTimeoutMs = 5_000,
        )
        try {
            conn.connect(tmp.root)
            error("expected connect to fail")
        } catch (expected: ToolFailure) {
            assertTrue(expected.message.orEmpty().contains("dead"))
        } finally {
            conn.close()
        }
    }
}
