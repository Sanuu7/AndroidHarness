package com.androidharness.app.data.env

import com.androidharness.app.agent.GrantPolicy
import com.androidharness.app.agent.PHONE_CONTROL_KEY
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneControlPreviewTest {
    @Test fun commonCommandsAreDescribedInPlainWords() {
        fun line(command: String) = PhoneControlPreview.describe(DeviceBackend.SHIZUKU, command).lineSequence().first()
        assertTrue(line("input tap 540 1200").endsWith("tap the screen."))
        assertTrue(line("/system/bin/pm uninstall com.example").endsWith("uninstall an app."))
        assertTrue(line("cmd package install -r /sdcard/app.apk").endsWith("install an app."))
        assertTrue(line("am start -n com.example/.Main").endsWith("open an app."))
        assertTrue(line("uiautomator dump /sdcard/ui.xml").endsWith("read what is on screen."))
        assertTrue(line("settings put global adb_enabled 1").endsWith("change a system setting."))
        assertTrue(line("ls /sdcard/Download").endsWith("work with phone files."))
    }

    @Test fun previewNamesTheBackendAndShowsTheCommand() {
        val root = PhoneControlPreview.describe(DeviceBackend.ROOT, "id")
        assertTrue(root.startsWith("The agent wants to use root"))
        assertTrue(root.endsWith("First command:\nid"))
        assertFalse(PhoneControlPreview.describe(DeviceBackend.DHIZUKU, null).contains("First command"))
    }

    @Test fun phoneControlIsNeverRememberedAsAToolGrant() {
        assertFalse(GrantPolicy.canSave("${PHONE_CONTROL_KEY}session-1"))
    }
}
