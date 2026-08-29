package com.androidharness.app.tools.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpReconnectPolicyTest {

    private val stdio = McpServerConfig(name = "files", command = "npx", args = listOf("-y", "srv"))
    private val remote = McpServerConfig(name = "supa", type = "http", url = "https://x/mcp")
    private val disabled = McpServerConfig(name = "off", command = "old", enabled = false)

    @Test
    fun `reconnects exactly the enabled previously-connected servers`() {
        val stored = mapOf(
            "files" to McpStoredStatus(3, 1L),
            "supa" to McpStoredStatus(14, 2L),
        )
        val picked = McpReconnectPolicy.candidates(listOf(stdio, remote, disabled), stored)
        assertEquals(listOf(stdio, remote), picked)
    }

    @Test
    fun `servers that never connected are not auto-reconnected`() {
        val stored = mapOf("supa" to McpStoredStatus(14, 1L))
        val picked = McpReconnectPolicy.candidates(listOf(stdio, remote), stored)
        assertEquals(listOf(remote), picked)
    }

    @Test
    fun `empty stored markers yield no candidates`() {
        assertTrue(McpReconnectPolicy.candidates(listOf(stdio, remote), emptyMap()).isEmpty())
    }

    @Test
    fun `status map survives a serialize and parse round trip`() {
        val stored = mapOf(
            "files" to McpStoredStatus(3, 1_700_000_000_000),
            "supa" to McpStoredStatus(14, 1_700_000_001_000),
        )
        val text = McpReconnectPolicy.serializeStored(stored)
        assertEquals(stored, McpReconnectPolicy.parseStored(text))
    }

    @Test
    fun `garbage status files parse to empty instead of throwing`() {
        assertTrue(McpReconnectPolicy.parseStored("not json at all").isEmpty())
        assertTrue(McpReconnectPolicy.parseStored("").isEmpty())
        assertTrue(McpReconnectPolicy.parseStored("{\"files\": \"bogus\"}").isEmpty())
    }
}
