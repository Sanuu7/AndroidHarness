package com.androidharness.app.core

import org.junit.Assert.*
import org.junit.Test

class ProjectLaunchTest {
    @Test fun `reads actual scripts and prioritizes launch scripts`() {
        assertEquals(listOf("dev", "start", "build", "test:unit"), ProjectLaunch.scripts("""{"scripts":{"build":"vite build","start":"node app.js","dev":"vite","test:unit":"vitest","bad';touch x":"oops"}}"""))
        assertTrue(ProjectLaunch.scripts("not json").isEmpty())
        assertEquals("npm run 'test:unit'", ProjectLaunch.npmCommand("test:unit"))
    }
    @Test fun `only accepts loopback preview URLs with valid ports`() {
        assertEquals("http://localhost:5173/", ProjectLaunch.previewUrl("Local: http://localhost:5173/"))
        assertEquals("http://127.0.0.1:3000/", ProjectLaunch.previewUrl("http://0.0.0.0:3000/"))
        assertNull(ProjectLaunch.previewUrl("http://evil.example:3000/"))
        assertNull(ProjectLaunch.previewUrl("http://localhost:99999/"))
    }
    @Test fun `questions include selected before and after without mutation instructions`() {
        val prompt = ChangeQuestion.prompt("a.kt", "Why?", "ignored", Diff.UndoSection(0, "before", "after"))
        assertTrue(prompt.contains("before")); assertTrue(prompt.contains("after"))
        assertTrue(prompt.contains("Do not edit")); assertFalse(prompt.contains("ignored"))
    }
}
