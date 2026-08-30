package com.androidharness.app.data.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellShimsTest {

    private val linker = "/system/bin/linker64"
    private val bash = "/p/bin/bash"

    private fun line(
        name: String,
        entryPath: String,
        isElf: Boolean,
        shebang: String? = null,
        nodeBin: String? = null,
        pythonBin: String? = null,
    ): String? = ShellShims.line(name, entryPath, isElf, shebang, linker, bash, nodeBin, pythonBin)

    @Test
    fun `elf entry routes through its own path`() {
        assertEquals(
            "ls() { command /system/bin/linker64 '/p/bin/ls' \"\$@\"; }\n",
            line("ls", "/p/bin/ls", isElf = true),
        )
    }

    @Test
    fun `multi-call applets keep argv0 via the entry path`() {
        // The pre-v2 generator resolved symlinks to the canonical bin/coreutils,
        // so the multi-call binary lost the invoked name: ls -la died with
        // "invalid option -- 'l'" and echo/printf emitted nothing. The entry
        // path itself is what preserves argv[0].
        assertEquals(
            "echo() { command /system/bin/linker64 '/p/bin/echo' \"\$@\"; }\n",
            line("echo", "/p/bin/echo", isElf = true),
        )
        assertEquals(
            "ash() { command /system/bin/linker64 '/p/bin/ash' \"\$@\"; }\n",
            line("ash", "/p/bin/ash", isElf = true),
        )
    }

    @Test
    fun `node script runs under the node binary`() {
        assertEquals(
            "serve() { command /system/bin/linker64 '/p/bin/node' '/p/bin/serve' \"\$@\"; }\n",
            line("serve", "/p/bin/serve", isElf = false, shebang = "#!/usr/bin/env node", nodeBin = "/p/bin/node"),
        )
    }

    @Test
    fun `python script falls back to bash without a python binary`() {
        assertEquals(
            "tool() { command /system/bin/linker64 '/p/bin/bash' '/p/bin/tool' \"\$@\"; }\n",
            line("tool", "/p/bin/tool", isElf = false, shebang = "#!/usr/bin/env python3"),
        )
        assertEquals(
            "tool() { command /system/bin/linker64 '/p/bin/python3' '/p/bin/tool' \"\$@\"; }\n",
            line("tool", "/p/bin/tool", isElf = false, shebang = "#!/usr/bin/python", pythonBin = "/p/bin/python3"),
        )
    }

    @Test
    fun `plain script runs under bash`() {
        assertEquals(
            "bzcmp() { command /system/bin/linker64 '/p/bin/bash' '/p/bin/bzcmp' \"\$@\"; }\n",
            line("bzcmp", "/p/bin/bzcmp", isElf = false, shebang = "#!/p/bin/bash"),
        )
    }

    @Test
    fun `single quotes in paths are escaped`() {
        val line = line("odd", "/p/we'ird/bin/odd", isElf = true)
        assertEquals(
            "odd() { command /system/bin/linker64 '/p/we'\\''ird/bin/odd' \"\$@\"; }\n",
            line,
        )
    }

    @Test
    fun `unusable names are skipped`() {
        assertNull(line("bad name", "/p/bin/bad", isElf = true))
        assertNull(line("-flag", "/p/bin/-flag", isElf = true))
    }
}
