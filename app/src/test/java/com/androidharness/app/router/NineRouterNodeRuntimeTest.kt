package com.androidharness.app.router

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NineRouterNodeRuntimeTest {
    @Test fun `npm children use the current Node and preserve arguments cwd env and exit status`() = fixture { root, node ->
        val prefix = File(root, "prefix with spaces").apply { mkdirs() }
        val bin = File(prefix, "bin").apply { mkdirs() }
        Files.createSymbolicLink(File(bin, "node").toPath(), File(node).toPath())
        val npm = File(prefix, "lib/node_modules/npm/bin/npm-cli.js").apply {
            parentFile.mkdirs()
            writeText("""
                console.log(JSON.stringify({args:process.argv.slice(2),cwd:process.cwd(),marker:process.env.PROBE_MARKER}));
                process.exit(23);
            """.trimIndent())
        }
        val launcher = File(bin, "npm").apply { writeText("#!/unreachable-shell-tier/bin/env node\n"); setExecutable(true) }
        val before = launcher.readBytes()
        val preload = File(root, "runtime with spaces.cjs").apply { writeText(NineRouterNodeRuntime.preload(false)) }
        for (command in listOf("npm", launcher.absolutePath)) {
            val script = File(root, "probe.cjs").apply { writeText("""
                const {spawn}=require('node:child_process');
                const child=spawn(process.env.PROBE_COMMAND,['install','name with spaces;literal'],{
                  cwd:process.env.PROBE_CWD,env:{...process.env,PROBE_MARKER:'kept'},stdio:'inherit'});
                child.on('error',e=>{console.error(e);process.exit(99)});
                child.on('close',code=>process.exit(code));
            """.trimIndent()) }
            val process = ProcessBuilder(node, "--require", preload.absolutePath, script.absolutePath)
                .apply { environment().putAll(mapOf("PREFIX" to prefix.absolutePath,
                    "PROBE_COMMAND" to command, "PROBE_CWD" to root.absolutePath)) }.redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(20, TimeUnit.SECONDS))
            assertEquals(output, 23, process.exitValue())
            assertTrue(output, output.contains("\"args\":[\"install\",\"name with spaces;literal\"]"))
            assertTrue(output, output.contains("\"cwd\":\"${root.absolutePath}\""))
            assertTrue(output, output.contains("\"marker\":\"kept\""))
        }
        assertArrayEquals("The shell-tier npm launcher must not be rewritten", before, launcher.readBytes())
        assertTrue(npm.isFile)
    }

    @Test fun `non npm D8 R8 and minify commands retain their original launch path`() = fixture { root, node ->
        val prefix = File(root, "prefix").apply { File(this, "bin").mkdirs() }
        Files.createSymbolicLink(File(prefix, "bin/node").toPath(), File(node).toPath())
        val bin = File(root, "commands").apply { mkdirs() }
        for (command in listOf("d8", "r8", "minify")) File(bin, command).apply {
            writeText("#!/bin/sh\nprintf '%s:<%s>\\n' '$command' \"\$1\"\nexit 17\n")
            setExecutable(true)
        }
        val preload = File(root, "runtime.cjs").apply { writeText(NineRouterNodeRuntime.preload(false)) }
        val script = File(root, "probe.cjs").apply { writeText("""
            const cp=require('node:child_process');
            (async()=>{for(const name of ['d8','r8','minify'])await new Promise((resolve,reject)=>{
              const c=cp.spawn(name,['argument with spaces'],{stdio:'inherit'});
              c.on('error',reject);c.on('close',code=>code===17?resolve():reject(new Error(name+': '+code)));
            })})().catch(e=>{console.error(e);process.exit(99)});
        """.trimIndent()) }
        val process = ProcessBuilder(node, "--require", preload.absolutePath, script.absolutePath)
            .apply { environment()["PREFIX"] = prefix.absolutePath; environment()["PATH"] = bin.absolutePath }
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
        for (command in listOf("d8", "r8", "minify")) assertTrue(output, output.contains("$command:<argument with spaces>"))
    }

    private fun fixture(block: (File, String) -> Unit) {
        val probe = runCatching { ProcessBuilder("node", "-p", "process.execPath").start() }.getOrNull()
        org.junit.Assume.assumeNotNull(probe)
        val node = requireNotNull(probe).inputStream.bufferedReader().readText().trim()
        assertEquals(0, probe.waitFor())
        val root = Files.createTempDirectory("nine-router-runtime").toFile()
        try { block(root, node) } finally { root.deleteRecursively() }
    }
}
