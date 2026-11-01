package com.androidharness.app.ui.files

import org.junit.Assert.*
import org.junit.Test

class MarkdownPreviewTest {
    @Test fun recognisesMarkdownExtensionsCaseInsensitively() {
        assertTrue(MarkdownPreview.supports("README.md"))
        assertTrue(MarkdownPreview.supports("docs/Guide.MD"))
        assertTrue(MarkdownPreview.supports("notes/plan.markdown"))
    }

    @Test fun ignoresOtherFilesAndDottedFolders() {
        assertFalse(MarkdownPreview.supports("src/Main.kt"))
        assertFalse(MarkdownPreview.supports("notes.md.bak"))
        assertFalse(MarkdownPreview.supports("docs.md/Makefile"))
        assertFalse(MarkdownPreview.supports("md"))
        assertFalse(MarkdownPreview.supports(""))
    }

    @Test fun opensRenderedOnlyForPlainReading() {
        assertTrue(MarkdownPreview.opensInPreview("README.md", editing = false, jumpToLine = false))
        assertFalse(MarkdownPreview.opensInPreview("README.md", editing = true, jumpToLine = false))
        assertFalse(MarkdownPreview.opensInPreview("README.md", editing = false, jumpToLine = true))
        assertFalse(MarkdownPreview.opensInPreview("Main.kt", editing = false, jumpToLine = false))
    }

    @Test fun keepsVeryLongFilesInSource() {
        assertTrue(MarkdownPreview.canRender("| a | b |\n|---|---|\n| 1 | 2 |"))
        assertTrue(MarkdownPreview.canRender("x".repeat(MarkdownPreview.MAX_CHARS)))
        assertFalse(MarkdownPreview.canRender("x".repeat(MarkdownPreview.MAX_CHARS + 1)))
        assertFalse(MarkdownPreview.canRender(null))
    }
}
