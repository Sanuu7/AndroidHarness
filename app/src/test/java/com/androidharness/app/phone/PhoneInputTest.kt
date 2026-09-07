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
        assertEquals(listOf("touchscreen", "tap", "20", "30"), command("click"))
        assertEquals(listOf("mouse", "motionevent", "MOVE", "20", "30"), command("move"))
        assertEquals("touchscreen", command("drag").first())
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
    @Test fun focusedPackageParsesCurrentWindowOnly() {
        val dump = """
            Window #4 Window{abcd u0 com.androidharness.app/com.androidharness.app.MainActivity}
              mCurrentFocus=Window{1234 u0 com.brave.browser/com.google.android.apps.chrome.Main}
        """.trimIndent()
        assertEquals("com.brave.browser", PhoneInput.focusedPackage(dump))
        assertNull(PhoneInput.focusedPackage("Window #1 com.androidharness.app/MainActivity"))
    }

    @Test fun currentFocusOverridesStaleFocusedAppAndUnknownFocusIsRejected() {
        assertEquals("com.brave.browser", PhoneInput.focusedPackage(
            "mFocusedApp=ActivityRecord{a com.androidharness.app/.MainActivity}\n" +
                "mCurrentFocus=Window{b u0 com.brave.browser/.Main}"))
        assertNull(PhoneInput.focusedPackage(
            "mCurrentFocus=null\nmFocusedApp=ActivityRecord{a com.brave.browser/.Main}"))
    }

    @Test fun observationBlocksAppSwitchDialogSwitchUnknownFocusAndRepeatedInput() {
        val gate = PhoneObservation()
        val brave = "Window{1 u0 com.brave.browser/.Main}"
        for (changed in listOf(null, "Window{2 u0 other.app/.Main}", "Window{3 u0 com.brave.browser/.Dialog}")) {
            gate.record(brave, 100)
            assertFalse(gate.consume(changed, 101))
            assertFalse(gate.consume(brave, 102))
        }
        gate.record(brave, 100)
        assertTrue(gate.consume(brave, 101))
        assertFalse(gate.consume(brave, 102))
        gate.record(brave, 100)
        assertFalse(gate.consume(brave, 30101))
        gate.record(brave, 100)
        gate.clear()
        assertFalse(gate.consume(brave, 101))
    }

    @Test fun resizedScreenshotCoordinatesScaleBothAxesAndRejectCrops() {
        assertEquals(540 to 1200, PhoneInput.scalePoint(270, 600, 540, 1200, 1080, 2400))
        assertEquals(1078 to 2398, PhoneInput.scalePoint(539, 1199, 540, 1200, 1080, 2400))
        assertEquals(1080 to 2400, PhoneInput.scalePoint(540, 1200, 540, 1200, 1080, 2400))
        assertEquals(17 to 23, PhoneInput.scalePoint(17, 23, -1, -1, 1080, 2400))
        assertThrows(IllegalArgumentException::class.java) { PhoneInput.scalePoint(2, 3, 540, -1, 1080, 2400) }
        assertThrows(IllegalArgumentException::class.java) { PhoneInput.scalePoint(2, 3, 540, 540, 1080, 2400) }
    }

    @Test fun movingPointerPreservesObservationForClickButCannotBypassFocusChange() {
        val gate = PhoneObservation()
        val target = "Window{48e9e7 u0 com.brave.browser/.Main}"
        gate.record(target, 100)
        assertTrue(gate.consume(target, 101, consume = false))
        assertTrue(gate.consume(target, 102, consume = false))
        assertTrue(gate.consume(target, 103))
        assertFalse(gate.consume(target, 104))
        gate.record(target, 100)
        assertFalse(gate.consume("Window{other u0 other.app/.Main}", 101, consume = false))
        assertFalse(gate.consume(target, 102))
    }

    @Test fun samsungDisplayFocusDumpIsParsed() {
        val dump = "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n" +
            "  mCurrentFocus=Window{48e9e7 u0 com.androidharness.app.debug/com.androidharness.app.MainActivity}\n" +
            "  mFocusedApp=ActivityRecord{51746179 u0 com.androidharness.app.debug/com.androidharness.app.MainActivity t25712}"
        assertEquals("com.androidharness.app.debug", PhoneInput.focusedPackage(dump))
        assertEquals("Window{48e9e7 u0 com.androidharness.app.debug/com.androidharness.app.MainActivity}",
            PhoneInput.focusedWindow(dump))
    }

    @Test fun captureVirtualDisplayCannotHidePhysicalDisplayFocus() {
        val phone = "Window{abc u0 com.brave.browser/.Main}"
        val virtual = "  Display: mDisplayId=29\n  mCurrentFocus=null\n"
        val physical = "  Display: mDisplayId=0 (organized)\n  mCurrentFocus=$phone\n"
        for (dump in listOf(virtual + physical, physical + virtual)) {
            assertEquals(phone, PhoneInput.focusedWindow(dump))
            val gate = PhoneObservation()
            gate.record(PhoneInput.focusedWindow(dump)!!, 100)
            assertTrue(gate.consume(PhoneInput.focusedWindow(dump), 101))
        }
        assertEquals(phone, PhoneInput.focusedWindow(
            virtual.replace("null", "Window{other u0 other.app/.Main}") + physical))
        assertNull(PhoneInput.focusedWindow(
            virtual.replace("null", "Window{other u0 other.app/.Main}") +
                "  Display: mDisplayId=0 (organized)\n  mCurrentFocus=null\n"))
        assertNull(PhoneInput.focusedWindow(virtual))
    }
}
