package com.androidharness.app.data.env

import com.androidharness.app.data.env.PhoneCommandRisk.CONTROL
import com.androidharness.app.data.env.PhoneCommandRisk.DESTRUCTIVE
import com.androidharness.app.data.env.PhoneCommandRisk.READ
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCommandRiskTest {
    private fun check(expected: PhoneCommandRisk, vararg commands: String) = commands.forEach {
        assertEquals(it, expected, PhoneCommandRisk.of(it))
    }

    @Test fun phoneStatusChecksNeedNoCard() = check(READ,
        "dumpsys battery",
        "dumpsys battery | grep level",
        "dumpsys meminfo 2>&1 | head -40",
        "getprop ro.build.version.release",
        "pm list packages -3",
        "cmd package list packages",
        "pm path com.example",
        "settings get global airplane_mode_on",
        "cat /sys/class/power_supply/battery/capacity",
        "cat /proc/meminfo; uptime",
        "logcat -d -t 100",
        "wm size",
        "df -h /data 2>/dev/null",
        "ANDROID_DATA=/data getprop",
    )

    @Test fun actingOnThePhoneAsksOncePerChat() = check(CONTROL,
        "input tap 540 1200",
        "am start -n com.example/.Main",
        "am force-stop com.example",
        "pm install -r /sdcard/app.apk",
        "settings put global adb_enabled 1",
        "screencap -p /sdcard/s.png",
        "uiautomator dump",
        "cat /sdcard/Download/notes.txt",
        "cat /data/data/com.whatsapp/databases/msgstore.db",
        "dumpsys battery set level 5",
        "dumpsys battery > /sdcard/out.txt",
        "getprop $(cat /sdcard/x)",
        "logcat -c",
        "wm size 1080x1920",
        "sh /sdcard/script.sh",
        "",
    )

    @Test fun irreversibleCommandsAskEveryTime() = check(DESTRUCTIVE,
        "pm uninstall com.example",
        "/system/bin/pm uninstall --user 0 com.example",
        "cmd package uninstall com.example",
        "pm clear com.example",
        "pm disable-user com.example",
        "rm -rf /sdcard/DCIM",
        "dumpsys battery; rm /sdcard/a",
        "reboot",
        "svc power shutdown",
        "settings delete secure lock_screen",
        "am broadcast -a android.intent.action.FACTORY_RESET",
        "find /sdcard -name '*.tmp' -delete",
        "find /sdcard -exec rm {} ;",
        "ls /sdcard | xargs rm",
        "busybox rm -rf /sdcard/x",
        "timeout 10 pm uninstall com.example",
        "sh -c \"pm uninstall com.example\"",
        "locksettings clear --old 1234",
    )

    @Test fun destructiveCardAllowsOnlyThatCommand() {
        val card = PhoneControlPreview.describe(DeviceBackend.SHIZUKU, "pm uninstall com.example", DESTRUCTIVE)
        assertTrue(card, card.startsWith("The agent wants to use Shizuku to uninstall an app."))
        assertTrue(card, card.contains("runs only this command"))
        assertTrue(card, card.endsWith("Command:\npm uninstall com.example"))
        val chat = PhoneControlPreview.describe(DeviceBackend.SHIZUKU, "input tap 1 1")
        assertTrue(chat, chat.contains("still ask every time"))
    }
}
