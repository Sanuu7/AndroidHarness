package com.androidharness.app.browser

import org.junit.Assert.*
import org.junit.Test

class PreviewSelectionTest {
    @Test fun `decodes WebView JSON and rejects empty picks`() {
        val raw = kotlinx.serialization.json.JsonPrimitive("""{"selector":"#cta","html":"<button>Go</button>","styles":"color: red"}""").toString()
        val selection = PreviewSelection.parse(raw)!!
        assertEquals("#cta", selection.selector)
        assertTrue(selection.prompt("https://harness.workspace/ws/index.html", "Make blue", "error", ".harness/screenshots/a.jpg").contains("read_image"))
        assertNull(PreviewSelection.parse("null"))
        assertNull(PreviewSelection.parse("{}"))
    }
    @Test fun `picker reads bounded DOM context without a native JavaScript bridge`() {
        val script = PreviewSelection.script(10.0, 20.0)
        assertTrue(script.contains("elementFromPoint(10.0, 20.0)"))
        assertTrue(script.contains("getComputedStyle"))
        assertFalse(script.contains("addJavascriptInterface"))
    }
}
