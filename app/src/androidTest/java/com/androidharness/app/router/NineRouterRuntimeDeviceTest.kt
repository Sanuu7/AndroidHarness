package com.androidharness.app.router

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NineRouterRuntimeDeviceTest {
    @Test fun lazyNpmInstallsAndLifecycleShellsWorkWithoutChangingSharedLaunchers() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val env = (app.applicationContext as HarnessApp).container.linuxEnv
        assumeTrue("Install Node and npm before running the runtime regression",
            File(env.prefix, "bin/node").isFile && File(env.prefix, "lib/node_modules/npm/bin/npm-cli.js").isFile)
        val shared = listOf("bin/npm", "bin/npx", "bin/d8", "bin/r8", "bin/minify")
            .map { File(env.prefix, it) }.filter { it.isFile }.associateWith { it.readBytes() }
        val root = File(app.cacheDir, "9router-runtime-${System.nanoTime()}").apply { mkdirs() }
        try {
            // Local package fixtures exercise real npm installs without network or provider credentials.
            for ((name, version) in listOf("wreq-js" to "3.2.0", "playwright-core" to "1.62.1")) {
                File(root, name).mkdirs()
                File(root, "$name/package.json").writeText("""{"name":"$name","version":"$version","main":"index.js"}""")
                File(root, "$name/index.js").writeText("module.exports = 'local-install-fixture';\n")
            }
            val runtime = File(root, "runtime").apply { mkdirs() }
            File(runtime, "package.json").writeText("""{"name":"9router-runtime-fixture","private":true,"scripts":{"probe":"npm --version"}}""")
            val preload = File(root, "node-runtime.cjs").apply {
                writeText(NineRouterNodeRuntime.preload(Build.VERSION.SDK_INT >= 29))
            }
            val probe = File(root, "probe.cjs").apply { writeText("""
                const {spawn}=require('node:child_process');
                const path=require('node:path');
                function run(args){return new Promise((resolve,reject)=>{
                  const c=spawn('npm',args,{cwd:path.join(__dirname,'runtime'),stdio:'inherit'});
                  c.on('error',reject);c.on('close',code=>code===0?resolve():reject(new Error('npm exit '+code)));
                })}
                (async()=>{
                  await run(['install','--offline','--no-audit','--no-fund',
                    path.join(__dirname,'wreq-js'),path.join(__dirname,'playwright-core')]);
                  for(const name of ['wreq-js','playwright-core']){
                    if(require(path.join(__dirname,'runtime','node_modules',name))!=='local-install-fixture')throw new Error(name);
                  }
                  await run(['run','probe']);
                  console.log('NINE_ROUTER_RUNTIME_OK');
                })().catch(e=>{console.error(e);process.exit(1)});
            """.trimIndent()) }
            val quote = { value: String -> "'" + value.replace("'", "'\\''") + "'" }
            coroutineScope {
                val process = env.shellProcessBuilder("node --require ${quote(preload.absolutePath)} ${quote(probe.absolutePath)}")
                    .directory(root).redirectErrorStream(true).start()
                val output = async(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
                try {
                    assertTrue("The npm runtime probe timed out", process.waitFor(60, TimeUnit.SECONDS))
                    val text = output.await()
                    assertEquals(text, 0, process.exitValue())
                    assertTrue(text, text.contains("NINE_ROUTER_RUNTIME_OK"))
                } finally { process.destroyForcibly() }
            }
            shared.forEach { (file, before) -> assertArrayEquals("Changed shared launcher: $file", before, file.readBytes()) }
        } finally { root.deleteRecursively() }
    }
}
