package com.androidharness.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryTopicsTest {

    @Test
    fun `sanitize lowercases and strips unsafe characters`() {
        assertEquals("github-workflows", MemoryTopics.sanitize("GitHub Workflows!"))
        assertEquals("deploy-prod", MemoryTopics.sanitize("  Deploy / Prod?  "))
        assertEquals("a_b-c", MemoryTopics.sanitize("A_B-C"))
        assertEquals("mlaut-x", MemoryTopics.sanitize("Ümlaut ä x"))
    }

    @Test
    fun `sanitize rejects blank or punctuation-only topics`() {
        assertNull(MemoryTopics.sanitize("   "))
        assertNull(MemoryTopics.sanitize("///"))
        assertNull(MemoryTopics.sanitize("---"))
    }

    @Test
    fun `sanitize caps the length at 48 characters`() {
        val long = MemoryTopics.sanitize("a".repeat(100))!!
        assertEquals(48, long.length)
    }

    @Test
    fun `topicPath builds the md path under the memory dir`() {
        assertEquals(".harness/memory/deployment.md", MemoryTopics.topicPath("Deployment"))
        assertNull(MemoryTopics.topicPath("?!"))
    }
}
