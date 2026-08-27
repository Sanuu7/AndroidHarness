package com.androidharness.app.data.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shebang repair for scripts shipped by Termux packages: language tooling
 * (pip, npm-cli.js, git helper scripts) must survive extraction with its
 * shebang rewritten into the deployed shell-tier prefix, while the
 * Android-bridge wrapper commands (pm, cmd, am, ...) are still dropped.
 */
class TermuxShebangTest {

    /** First line of Termux python-pip 26.x bin/pip3, verified against the repo. */
    private val pip3 = "#!/data/data/com.termux/files/usr/bin/python3.14"

    /** First line of Termux npm 11.x lib/node_modules/npm/bin/npm-cli.js. */
    private val npmCli = "#!/data/data/com.termux/files/usr/bin/env node"

    private val termuxSh = "#!/data/data/com.termux/files/usr/bin/sh"

    @Test
    fun `python interpreter shebang rewrites into the deployed prefix`() {
        assertEquals(
            "#!${TermuxShebangs.DEPLOYED_PREFIX}/bin/python3.14",
            TermuxShebangs.rewrittenFirstLine(pip3),
        )
    }

    @Test
    fun `env-style shebang routes through the busybox applet`() {
        assertEquals(
            "#!${TermuxShebangs.DEPLOYED_PREFIX}/bin/applets/env node",
            TermuxShebangs.rewrittenFirstLine(npmCli),
        )
    }

    @Test
    fun `rewritten lines never reference the termux prefix`() {
        assertFalse(TermuxShebangs.rewrittenFirstLine(pip3)!!.contains("/data/data/com.termux"))
        assertFalse(TermuxShebangs.rewrittenFirstLine(npmCli)!!.contains("/data/data/com.termux"))
    }

    @Test
    fun `non-termux shebangs are left alone`() {
        assertNull(TermuxShebangs.rewrittenFirstLine("#!/usr/bin/env node"))
        assertNull(TermuxShebangs.rewrittenFirstLine("#!/system/bin/sh"))
        assertNull(TermuxShebangs.rewrittenFirstLine(""))
    }

    @Test
    fun `termux paths outside files usr are not rewritable`() {
        assertNull(TermuxShebangs.rewrittenFirstLine("#!/data/data/com.termux/files/home/x"))
    }

    @Test
    fun `android-bridge wrappers are classified for dropping`() {
        assertTrue(TermuxShebangs.isWrapperScript("pm", termuxSh))
        assertTrue(TermuxShebangs.isWrapperScript("settings", termuxSh))
        // same name but a language-tooling shebang is not a wrapper
        assertFalse(TermuxShebangs.isWrapperScript("pm", pip3))
        // pip is not a wrapper even with its real shebang
        assertFalse(TermuxShebangs.isWrapperScript("pip3", pip3))
        assertFalse(TermuxShebangs.isWrapperScript("npm", npmCli))
        // non-termux shebangs are never wrappers
        assertFalse(TermuxShebangs.isWrapperScript("pm", "#!/system/bin/sh"))
    }

    @Test
    fun `deployed prefix matches the tmp toolchain location`() {
        assertEquals(
            LinuxEnvironmentManager.TMP_PREFIX_BASE + "/linux",
            TermuxShebangs.DEPLOYED_PREFIX,
        )
    }
}
