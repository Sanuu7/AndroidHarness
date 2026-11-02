package com.androidharness.app.agent

import com.androidharness.app.tools.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ToolFailurePolicyTest {
    @Test fun `missing assets dependencies and stale edits remain retryable after repair`() {
        for (failure in listOf("exit code: 1\nstderr: ENOENT: no such file site/base.css",
            "exit code: 127\nstderr: vite: command not found", "File does not exist: site.js",
            "old_string does not match current contents", "Shizuku is unavailable")) {
            assertNull(failure, immutableToolFailureHint(ToolResult(false, failure)))
        }
    }

    @Test fun `only invocation failures are cached and command output cannot impersonate them`() {
        assertNotNull(immutableToolFailureHint(ToolResult(false, "Invalid tool arguments: malformed JSON")))
        assertNotNull(immutableToolFailureHint(ToolResult(false, "Missing required argument: path")))
        assertNull(immutableToolFailureHint(ToolResult(false, "exit code: 1\nstdout: Invalid tool arguments: fixture")))
        assertNull(immutableToolFailureHint(ToolResult(true, "Unknown tool: fixture")))
    }

    @Test fun `empty arguments name the required fields and stay retryable`() {
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"path":{},"content":{}},"required":["path","content"]}""").jsonObject
        val hint = emptyArgumentsHint("write_file", " {} ", schema)!!
        assertTrue(hint, hint.startsWith("write_file received empty arguments") && hint.endsWith("Required: path, content."))
        assertNotNull(emptyArgumentsHint("write_file", "", schema))
        assertNull(immutableToolFailureHint(ToolResult(false, "$hint\nMissing required argument: path")))
        assertNull(emptyArgumentsHint("write_file", """{"path":"x"}""", schema))
        assertNull(emptyArgumentsHint("git_log", "{}", Json.parseToJsonElement("""{"type":"object","properties":{}}""").jsonObject))
    }
}
