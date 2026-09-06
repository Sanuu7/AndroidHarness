package com.androidharness.app.phone

import org.junit.Assert.*
import org.junit.Test

class PhoneInputTest {
    private fun command(action: String, x: Int = 20, y: Int = 30, x2: Int = 40, y2: Int = 50, text: String = "") =
        PhoneInput.command(action, x, y, x2, y2, text, 1080, 2400)
    @Test fun sessionGateRejectsOtherChatsAndPausedSessions() {
        assertTrue(PhoneInput.sessionAllowed("chat", "chat", false))
        assertFalse(PhoneInput.sessionAllowed(null, "chat", false))
        assertFalse(PhoneInput.sessionAllowed("other", "chat", false))
        assertFalse(PhoneInput.sessionAllowed("chat", "chat", true))
        assertFalse(PhoneInput.sessionAllowed("", "", false))
    }
    @Test fun mouseSourceIsExplicit() {
        assertEquals(listOf("mouse", "tap", "20", "30"), command("click"))
        assertEquals(listOf("mouse", "motionevent", "MOVE", "20", "30"), command("move"))
        assertEquals("mouse", command("drag").first())
        assertEquals(listOf("mouse", "scroll", "20", "30", "--axis", "VSCROLL,-2"), command("scroll", text = "-2"))
    }
    @Test fun boundariesAndDragDestinationAreValidated() {
        for (call in listOf<() -> Unit>(
            { command("click", x = -1) }, { command("move", x = 1080) },
            { command("drag", y2 = 2400) }, { command("scroll", text = "11") },
            { command("scroll", text = "0") }, { command("key", text = "POWER") },
            { command("unknown") }, { command("type", text = "hello\nworld") },
            { command("type", text = "你好") }, { command("type", text = "%s") },
        )) assertThrows(IllegalArgumentException::class.java) { call() }
    }
    @Test fun textIsOneArgumentNotAShellCommand() {
        assertEquals(listOf("text", "hello%sworld;$(id)"), command("type", text = "hello world;$(id)"))
        assertEquals(listOf("keyevent", "KEYCODE_HOME"), command("key", text = "HOME"))
    }
}
