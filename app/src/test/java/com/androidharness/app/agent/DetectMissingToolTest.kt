package com.androidharness.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetectMissingToolTest {

    private fun detect(output: String) = detectMissingHeadlineTool(output)

    @Test
    fun `npm not found is detected`() {
        assertEquals("npm", detect("exit code: 127\n--- stderr ---\nbash: npm: command not found"))
    }

    @Test
    fun `bare npm not found is detected`() {
        assertEquals("npm", detect("npm: not found"))
    }

    @Test
    fun `node and git are detected`() {
        assertEquals("node", detect("sh: node: not found"))
        assertEquals("git", detect("/prefix/bin/sh: git: command not found"))
    }

    @Test
    fun `pip not found is detected`() {
        assertEquals("pip", detect("bash: pip: command not found"))
    }

    @Test
    fun `project-level vite failure is ignored`() {
        assertNull(detect("vite: not found"))
        assertNull(detect("sh: 1: tsc: not found"))
    }

    @Test
    fun `tool appearing in a path is not a failure`() {
        assertNull(detect("/data/data/com.termux/files/usr/bin/npm exists"))
    }

    @Test
    fun `unrelated errors are ignored`() {
        assertNull(detect("npm ERR! network timeout"))
        assertNull(detect("fatal: not a git repository"))
    }
}
