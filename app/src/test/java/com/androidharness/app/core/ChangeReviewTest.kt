package com.androidharness.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeReviewTest {
    @Test fun withoutFilesTheAgentFindsTheChangesItself() {
        val prompt = ChangeReview.prompt()
        assertTrue(prompt.contains("git status and git diff"))
        assertTrue(prompt.contains("do not edit files"))
        assertTrue(prompt.contains("Fix nothing until I answer"))
        assertFalse(prompt.contains("Focus the review on"))
    }

    @Test fun listedFilesAreCappedAndCounted() {
        val paths = (1..45).map { "src/File$it.kt" }
        val prompt = ChangeReview.prompt(changedPaths = paths)
        assertTrue(prompt.contains("- src/File1.kt"))
        assertTrue(prompt.contains("- src/File40.kt"))
        assertFalse(prompt.contains("- src/File41.kt"))
        assertTrue(prompt.contains("- and 5 more"))
    }

    @Test fun focusIsQuotedFromTheUser() {
        assertTrue(ChangeReview.prompt(focus = "  security  ").contains("Focus the review on: security"))
    }
}
