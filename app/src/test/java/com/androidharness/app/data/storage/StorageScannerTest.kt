package com.androidharness.app.data.storage

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class StorageScannerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun File.bytes(n: Int): File { parentFile!!.mkdirs(); writeBytes(ByteArray(n)); return this }
    private fun stamp(path: String, size: Long) = StorageStamp(path, size, 0, null, false, "")

    private class App(root: File) {
        val data = File(root, "data").apply { mkdirs() }
        val files = File(data, "files")
        val cache = File(data, "cache")
        val noBackup = File(data, "no_backup")
        val prefix = File(files, "linux")
        val external = File(root, "external")
    }

    private fun layout(app: App, vararg extra: Pair<String, Any?>): StorageLayout {
        val map = extra.toMap()
        @Suppress("UNCHECKED_CAST")
        return StorageLayout(
            dataDir = app.data, filesDir = app.files, cacheDir = app.cache, noBackupDir = app.noBackup,
            linux = StorageLinuxLayout(app.prefix, File(app.files, "package-cache"), File(app.files, "shared-tools"),
                installed = map["installed"] as? List<String> ?: emptyList(),
                records = map["records"] as? Map<String, ManagedTool> ?: emptyMap()),
            externalFilesDir = app.external,
            databases = listOf(File(app.data, "databases/harness.db")),
            plugins = if (map.containsKey("plugins")) map["plugins"] as List<StoragePlugin>? else emptyList(),
            models = map["models"] as? Map<String, String> ?: emptyMap(),
            bundledSkills = map["bundled"] as? List<String> ?: emptyList(),
            workspaces = map["workspaces"] as? List<StorageWorkspaceEntry> ?: emptyList(),
        )
    }

    @Test fun `counter claims each file once, skips links and reports what was already counted`() = runBlocking {
        val root = temp.newFolder("tree")
        val a = File(root, "a").bytes(10)
        Files.createLink(File(root, "b").toPath(), a.toPath())
        Files.createSymbolicLink(File(root, "link").toPath(), temp.root.toPath())
        File(root, "sub/c").bytes(5)
        val counter = StorageCounter()
        val first = counter.measure(root, setOf(File(root, "sub").toPath().toAbsolutePath().normalize()))
        assertEquals(10L, first.bytes); assertEquals(1, first.files); assertEquals(1, first.alreadyCounted)
        val second = counter.measure(root)
        assertEquals(5L, second.bytes); assertEquals(2, second.alreadyCounted)
        assertFalse(counter.measure(File(root, "nope")).exists)
    }

    @Test fun `counter stops at the deadline and marks the result truncated`() = runBlocking {
        val root = temp.newFolder("slow")
        repeat(3) { File(root, "d$it/f").bytes(1) }
        var clock = 0L
        val counter = StorageCounter(deadline = 1, now = { clock++ })
        val result = counter.measure(root)
        assertTrue(result.truncated)
        assertTrue(counter.expired)
    }

    @Test fun `unreadable folder is reported as denied, not as zero`() = runBlocking {
        val root = temp.newFolder("locked")
        File(root, "secret").bytes(4)
        assumeReadable(root)
        try {
            val result = StorageCounter().measure(root)
            assertTrue(result.rootDenied)
        } finally { root.setReadable(true); root.setExecutable(true) }
    }

    private fun assumeReadable(root: File) {
        root.setReadable(false); root.setExecutable(false)
        org.junit.Assume.assumeFalse("Running as root ignores permissions", root.canRead())
    }

    @Test fun `package ownership splits exclusive and shared files and ignores unsafe paths`() {
        val records = mapOf(
            "a" to ManagedTool("a", "1", emptyList(), listOf(stamp("bin/a", 1), stamp("lib/shared.so", 1))),
            "b" to ManagedTool("b", "1", emptyList(), listOf(stamp("bin/b", 1), stamp("lib/shared.so", 1), stamp("../escape", 1))),
            "gone" to ManagedTool("gone", "1", emptyList(), listOf(stamp("bin/gone", 1))),
        )
        val (exclusive, shared) = StorageInventoryRules.splitOwnership(listOf("a", "b"), records)
        assertEquals(listOf("bin/a"), exclusive["a"])
        assertEquals(listOf("bin/b"), exclusive["b"])
        assertEquals(mapOf("lib/shared.so" to listOf("a", "b")), shared)
        assertNull(exclusive["gone"])
    }

    @Test fun `pip records, dist-info names and scoped npm packages parse`() {
        assertEquals(listOf("requests/__init__.py", "a,b.py", "../../../bin/req"),
            StorageInventoryRules.pipRecordPaths("requests/__init__.py,sha256=x,10\n\"a,b.py\",sha256=y,2\n../../../bin/req,,\n\n"))
        assertEquals("requests" to "2.32.3", StorageInventoryRules.distInfoName("requests-2.32.3.dist-info"))
        assertEquals("solo" to null, StorageInventoryRules.distInfoName("solo.dist-info"))
        val modules = temp.newFolder("node_modules")
        File(modules, "typescript").mkdirs(); File(modules, "@scope/tool").mkdirs(); File(modules, ".bin").mkdirs()
        assertEquals(listOf("@scope/tool", "typescript"), StorageInventoryRules.npmPackages(modules).map { it.first })
    }

    @Test fun `model files group by the longest matching id and leftovers stay separate`() {
        val (groups, other) = StorageInventoryRules.groupModelFiles(
            listOf("qwen.gguf", "qwen-vl.gguf", "qwen-vl-mmproj.gguf", "qwen-vl.gguf.part", "qwen-source", "custom.json", "stray.bin"),
            listOf("qwen", "qwen-vl"))
        assertEquals(ModelFiles(listOf("qwen.gguf"), listOf("qwen-source")), groups["qwen"])
        assertEquals(ModelFiles(listOf("qwen-vl-mmproj.gguf", "qwen-vl.gguf"), listOf("qwen-vl.gguf.part")), groups["qwen-vl"])
        assertEquals(listOf("custom.json", "stray.bin"), other)
    }

    @Test fun `full scan lists every component once and the total matches the files on disk`() = runBlocking {
        val app = App(temp.root)
        // Linux: two recorded packages sharing one library, one older package, npm and pip on top.
        File(app.prefix, "bin/git").bytes(100)
        File(app.prefix, "bin/node").bytes(200)
        File(app.prefix, "lib/libz.so").bytes(30)
        File(app.prefix, "lib/node_modules/npm/index.js").bytes(40)
        File(app.prefix, "lib/node_modules/typescript/tsc.js").bytes(50)
        val site = File(app.prefix, "lib/python3.12/site-packages")
        File(site, "requests/__init__.py").bytes(60)
        File(site, "requests-2.0.dist-info/RECORD").apply { parentFile!!.mkdirs(); writeText("requests/__init__.py,,\nrequests-2.0.dist-info/RECORD,,\n") }
        File(app.prefix, "home/.npm/_cacache/x").bytes(70)
        File(app.prefix, "home/.bashrc").bytes(3)
        File(app.prefix, "etc/untracked.conf").bytes(7)
        File(app.files, "package-cache/pip/wheel").bytes(80)
        val records = mapOf(
            "git" to ManagedTool("git", "2.4", emptyList(), listOf(stamp("bin/git", 100), stamp("lib/libz.so", 30)), true, setOf("Manual install")),
            "nodejs" to ManagedTool("nodejs", "22", emptyList(), listOf(stamp("bin/node", 200), stamp("lib/libz.so", 30),
                stamp("lib/node_modules/npm/index.js", 40)), true),
        )
        // Plugins: one global, one per workspace, one orphan folder.
        val packages = File(app.files, "plugins/packages")
        File(packages, "p1/plugin.json").bytes(11)
        File(packages, "p2/plugin.json").bytes(12)
        File(packages, "orphan/x").bytes(13)
        File(app.files, "plugins/installed.json").bytes(2)
        // Skills, models, caches, app data, workspaces.
        File(app.files, "skills/mine/SKILL.md").bytes(9)
        File(app.noBackup, "local-models/qwen.gguf").bytes(500)
        File(app.noBackup, "local-models/qwen.gguf.part").bytes(20)
        File(app.cache, "plugin-previews/p.zip").bytes(15)
        File(app.cache, "deb-download/x.deb").bytes(16)
        File(app.cache, "random").bytes(4)
        File(app.data, "databases/harness.db").bytes(64)
        File(app.data, "shared_prefs/a.xml").bytes(5)
        File(app.files, "datastore/b.pb").bytes(6)
        File(app.data, "app_webview/Default/Cookies").bytes(12)
        File(app.cache, "WebView/c").bytes(3)
        File(app.files, "models-dev.json").bytes(21)
        File(app.files, "unknown.bin").bytes(6)
        val ws = File(app.external, "workspace").apply { mkdirs() }
        File(ws, "src/Main.kt").bytes(90)
        File(ws, ".harness/skills/proj/SKILL.md").bytes(8)
        val nested = File(ws, "inner").apply { mkdirs() }
        File(nested, "n.txt").bytes(10)
        Files.createLink(File(ws, "hardlink.kt").toPath(), File(ws, "src/Main.kt").toPath())

        val report = StorageScanner(layout(app,
            "installed" to listOf("git", "nodejs", "oldpkg"), "records" to records,
            "plugins" to listOf(StoragePlugin("id1", "Global plugin", "1.0", null, true, File(packages, "p1")),
                StoragePlugin("id2", "Project plugin", "", "my-app", false, File(packages, "p2"))),
            "models" to mapOf("qwen" to "Qwen"), "bundled" to listOf("built-in"),
            "workspaces" to listOf(
                StorageWorkspaceEntry("w1", "Main", ws), StorageWorkspaceEntry("w2", "Inner", nested),
                StorageWorkspaceEntry("w3", "Alias", ws), StorageWorkspaceEntry("w4", "Picked", null, StorageGap.SAF_FOLDER),
                StorageWorkspaceEntry("w5", "Remote", null, StorageGap.SSH_HOST)),
        )).scan()

        fun item(role: StorageRole, key: String? = null) = report.items.single { it.role == role && (key == null || it.key == key) }
        assertEquals(100L, item(StorageRole.LINUX_PACKAGE, "git").bytes)
        assertEquals(240L, item(StorageRole.LINUX_PACKAGE, "nodejs").bytes)
        assertEquals(30L, item(StorageRole.LINUX_SHARED_FILES).bytes)
        assertEquals(listOf("git", "nodejs"), item(StorageRole.LINUX_SHARED_FILES).owners)
        assertEquals(StorageGap.UNTRACKED, item(StorageRole.LINUX_UNTRACKED_PACKAGE, "oldpkg").gap)
        assertEquals(listOf("typescript"), report.items.filter { it.role == StorageRole.NPM_GLOBAL }.map { it.name })
        assertEquals(50L, item(StorageRole.NPM_GLOBAL).bytes)
        assertEquals(60L + "requests/__init__.py,,\nrequests-2.0.dist-info/RECORD,,\n".length, item(StorageRole.PIP_PACKAGE).bytes)
        assertEquals("2.0", item(StorageRole.PIP_PACKAGE).version)
        assertEquals(70L, item(StorageRole.CACHE_NPM, File(app.prefix, "home/.npm").path).bytes)
        assertEquals(80L, item(StorageRole.CACHE_PIP, File(app.files, "package-cache/pip").path).bytes)
        assertEquals(3L, item(StorageRole.LINUX_HOME).bytes)
        assertEquals(7L, item(StorageRole.LINUX_OTHER).bytes)

        assertEquals(11L, item(StorageRole.PLUGIN, "id1").bytes)
        assertNull(item(StorageRole.PLUGIN, "id1").scope)
        assertEquals("my-app", item(StorageRole.PLUGIN, "id2").scope)
        assertEquals(false, item(StorageRole.PLUGIN, "id2").enabled)
        assertEquals(13L, item(StorageRole.PLUGIN_LEFTOVERS).bytes)
        assertEquals(2L, item(StorageRole.PLUGIN_REGISTRY).bytes)

        assertEquals(9L, item(StorageRole.SKILL_USER).bytes)
        assertEquals(8L, item(StorageRole.SKILL_PROJECT).bytes)
        assertEquals("Main", item(StorageRole.SKILL_PROJECT).scope)
        assertEquals(StorageGap.INSIDE_APP_PACKAGE, item(StorageRole.SKILL_BUNDLED).gap)
        assertFalse(item(StorageRole.SKILL_BUNDLED).notMeasured)

        assertEquals(500L, item(StorageRole.MODEL).bytes)
        assertEquals(20L, item(StorageRole.MODEL_PARTIAL).bytes)
        assertEquals(15L, item(StorageRole.CACHE_PLUGIN_PREVIEWS).bytes)
        assertEquals(16L, item(StorageRole.CACHE_DEB).bytes)
        assertEquals(4L, item(StorageRole.CACHE_APP_OTHER).bytes)
        assertEquals(64L, item(StorageRole.CHAT_DATABASE).bytes)
        // Related folders form one item with every existing location listed; missing ones are left out.
        assertEquals(11L, item(StorageRole.SETTINGS).bytes)
        assertEquals(File(app.data, "shared_prefs").path, item(StorageRole.SETTINGS).path)
        assertEquals(listOf(File(app.files, "datastore").path), item(StorageRole.SETTINGS).morePaths)
        assertEquals(15L, item(StorageRole.WEB_DATA).bytes)
        assertEquals(21L, item(StorageRole.CACHE_MODEL_CATALOG).bytes)
        assertTrue(report.items.none { it.role == StorageRole.BACKGROUND_WORK })

        // Nested workspace counted once, hard link counted once, project skill left to Skills.
        assertEquals(90L, item(StorageRole.WORKSPACE, "w1").bytes)
        assertEquals(10L, item(StorageRole.WORKSPACE, "w2").bytes)
        assertEquals(StorageGap.SAME_FOLDER, item(StorageRole.WORKSPACE, "w3").gap)
        assertEquals(item(StorageRole.WORKSPACE, "w1").id, item(StorageRole.WORKSPACE, "w3").countedIn)
        assertEquals(StorageGap.SAF_FOLDER, item(StorageRole.WORKSPACE, "w4").gap)
        assertEquals(StorageGap.SSH_HOST, item(StorageRole.WORKSPACE, "w5").gap)
        assertEquals(listOf("w1", "w2", "w3", "w4", "w5"), report.items.filter { it.role == StorageRole.WORKSPACE }.map { it.key })
        assertEquals(2, report.notMeasured.size)

        // Everything left over lands in Other app data, and the grand total equals the unique bytes on disk.
        assertEquals(6L, item(StorageRole.OTHER_APP_DATA).bytes)
        val unique = mutableSetOf<Any>()
        var disk = 0L
        temp.root.walkTopDown().filter { Files.isRegularFile(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) }.forEach {
            val key = Files.readAttributes(it.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey() ?: it.path
            if (unique.add(key)) disk += it.length()
        }
        assertEquals(disk, report.total)
        assertEquals(report.total, report.groups().sumOf { it.bytes })
        assertEquals(report.items.size, report.items.map { it.id }.distinct().size)
    }

    @Test fun `unreadable plugin registry and missing plugin folders are listed, not dropped`() = runBlocking {
        val app = App(temp.root)
        File(app.files, "plugins/packages/abc/x").bytes(5)
        val broken = StorageScanner(layout(app, "plugins" to null)).scan()
        assertEquals(StorageGap.REGISTRY_UNREADABLE, broken.items.single { it.role == StorageRole.PLUGIN_REGISTRY && it.gap != null }.gap)
        assertEquals(5L, broken.items.single { it.role == StorageRole.PLUGIN_LEFTOVERS }.bytes)
        val missing = StorageScanner(layout(app, "plugins" to listOf(StoragePlugin("id", "Gone", "1", null, true, File(app.files, "plugins/packages/gone"))))).scan()
        val gone = missing.items.single { it.role == StorageRole.PLUGIN }
        assertEquals(StorageGap.MISSING, gone.gap)
        assertTrue(gone.notMeasured)
    }

    @Test fun `items after the time budget are listed as timed out and excluded from the total`() = runBlocking {
        val app = App(temp.root)
        File(app.cache, "plugin-previews/a").bytes(5)
        File(app.data, "databases/harness.db").bytes(10)
        var clock = 0L
        val report = StorageScanner(layout(app), budgetMillis = 0, now = { clock++ }).scan()
        assertTrue(report.timedOut)
        val db = report.items.single { it.role == StorageRole.CHAT_DATABASE }
        assertEquals(StorageGap.TIMED_OUT, db.gap)
        assertNull(db.bytes)
        assertEquals(0L, report.total)
        assertTrue(report.notMeasured.contains(db))
    }

    @Test fun `scan reports progress and can be cancelled`() = runBlocking {
        val app = App(temp.root)
        File(app.data, "databases/harness.db").bytes(10)
        val seen = mutableListOf<StorageProgress>()
        StorageScanner(layout(app)).scan { seen += it }
        assertTrue(seen.size > 2)
        assertEquals(seen.last().total, seen.last().done)
        assertEquals(1f, seen.last().fraction)
        val job = launch(Dispatchers.IO) { StorageScanner(layout(app)).scan { throw CancellationException("stop") } }
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test fun `report groups in display order, sorts by size and filters by title path or owner`() {
        val items = listOf(
            StorageItem(StorageRole.CACHE_NPM, "a", path = "/x/npm", bytes = 5),
            StorageItem(StorageRole.PLUGIN, "p", "Big plugin", "/x/p", bytes = 50),
            StorageItem(StorageRole.PLUGIN, "q", "Small plugin", "/x/q", bytes = 1),
            StorageItem(StorageRole.PLUGIN, "r", "Broken", gap = StorageGap.MISSING),
            StorageItem(StorageRole.APP_PACKAGE, "apk", bytes = 100),
            StorageItem(StorageRole.LINUX_SHARED_FILES, "s", bytes = 3, owners = listOf("openssl", "curl")),
        )
        val report = StorageReport(items)
        assertEquals(listOf(StorageCategory.APP_DATA, StorageCategory.PLUGINS, StorageCategory.LINUX_PACKAGES, StorageCategory.CACHES),
            report.groups().map { it.category })
        assertEquals(listOf("p", "q", "r"), report.groups().single { it.category == StorageCategory.PLUGINS }.items.map { it.key })
        assertEquals(1, report.groups().single { it.category == StorageCategory.PLUGINS }.notMeasured)
        assertEquals(159L, report.total)
        assertEquals(listOf("apk", "p", "a"), report.largest(3).map { it.key })
        assertTrue(StorageReport.matches(items[0], "npm download cache", "NPM"))
        assertTrue(StorageReport.matches(items[1], "Big plugin", "/x/p"))
        assertTrue(StorageReport.matches(items[5], "Shared", "curl"))
        assertFalse(StorageReport.matches(items[2], "Small plugin", "big"))
    }
}
