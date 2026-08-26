package com.androidharness.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NpmOnSharedStorageTest {

    private val shared = File("/storage/emulated/0/Projects/app")
    private val appData = File("/data/user/0/com.androidharness.app/workspace")
    private val system = File("/system/etc")

    @Test
    fun `npm install on shared storage gains no-bin-links`() {
        val (cmd, note) = NpmOnSharedStorage.prepare("npm install", shared)
        assertEquals("npm install --no-bin-links", cmd)
        requireNotNull(note)
        assertTrue(note.contains("--no-bin-links"))
    }

    @Test
    fun `args after install stay in place`() {
        val (cmd, _) = NpmOnSharedStorage.prepare("npm install express --save", shared)
        assertEquals("npm install --no-bin-links express --save", cmd)
    }

    @Test
    fun `short forms i and ci also rewritten`() {
        assertEquals("npm i --no-bin-links", NpmOnSharedStorage.prepare("npm i", shared).first)
        assertEquals("npm ci --no-bin-links", NpmOnSharedStorage.prepare("npm ci", shared).first)
    }

    @Test
    fun `existing flag is not doubled`() {
        val (cmd, note) = NpmOnSharedStorage.prepare("npm install --no-bin-links", shared)
        assertEquals("npm install --no-bin-links", cmd)
        assertNull(note)
    }

    @Test
    fun `non-install npm commands untouched`() {
        val (cmd, note) = NpmOnSharedStorage.prepare("npm run build && npm test", shared)
        assertEquals("npm run build && npm test", cmd)
        assertNull(note)
    }

    @Test
    fun `pnpm does not match`() {
        val (cmd, note) = NpmOnSharedStorage.prepare("pnpm install", shared)
        assertEquals("pnpm install", cmd)
        assertNull(note)
    }

    @Test
    fun `app data and system dirs never rewrite`() {
        for (dir in listOf(appData, system)) {
            val (cmd, note) = NpmOnSharedStorage.prepare("npm install", dir)
            assertEquals("npm install", cmd)
            assertNull(note)
        }
    }

    @Test
    fun `chained installs all get the flag`() {
        val (cmd, _) = NpmOnSharedStorage.prepare("npm i && npm ci", shared)
        assertTrue(cmd.contains("npm i --no-bin-links"))
        assertTrue(cmd.contains("npm ci --no-bin-links"))
    }
}
