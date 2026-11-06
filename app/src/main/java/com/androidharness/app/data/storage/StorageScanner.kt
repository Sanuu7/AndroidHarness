package com.androidharness.app.data.storage

import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.Path

/** One installed plugin as the registry records it. A null [scope] means the global scope. */
internal data class StoragePlugin(val id: String, val name: String, val version: String, val scope: String?,
    val enabled: Boolean, val directory: File)

/** A saved workspace. [root] is null when the folder has no file path the app can walk; [gap] says why. */
internal data class StorageWorkspaceEntry(val id: String, val name: String, val root: File?, val gap: StorageGap? = null)

internal data class StorageLinuxLayout(
    val prefix: File,
    val sharedCacheRoot: File,
    val toolRecords: File,
    val installed: List<String> = emptyList(),
    val records: Map<String, ManagedTool> = emptyMap(),
    val staging: File? = null,
    /** The copy deployed for the Shizuku or ADB shell. Usually owned by the shell user, so the app may not read it. */
    val shellTier: File? = null,
)

/**
 * Every place the app keeps files. The manager fills it from the running app; tests build one in a temp folder.
 * Paths inside the app's data directory should already be resolved to their real location.
 */
internal data class StorageLayout(
    val dataDir: File,
    val filesDir: File,
    val cacheDir: File,
    val noBackupDir: File,
    val linux: StorageLinuxLayout,
    val codeCacheDir: File? = null,
    val externalFilesDir: File? = null,
    val externalCacheDir: File? = null,
    val apkFiles: List<File> = emptyList(),
    val databases: List<File> = emptyList(),
    val pluginRoot: File = File(filesDir, "plugins"),
    /** Null when the plugin registry could not be read. */
    val plugins: List<StoragePlugin>? = emptyList(),
    val userSkillsDir: File = File(filesDir, "skills"),
    /** Folder name to skill name, for user skills the skill store could parse. */
    val userSkillNames: Map<String, String> = emptyMap(),
    val bundledSkills: List<String> = emptyList(),
    val modelRoot: File = File(noBackupDir, "local-models"),
    /** Model id to its title. */
    val models: Map<String, String> = emptyMap(),
    val gpuRuntime: File = File(noBackupDir, "gpu-runtime"),
    val workspaces: List<StorageWorkspaceEntry> = emptyList(),
    val repositoriesRoot: File = File(filesDir, "github-projects"),
)

/** Files of one model id, split into finished files and leftovers of an unfinished transfer. */
internal data class ModelFiles(val complete: List<String> = emptyList(), val partial: List<String> = emptyList())

internal object StorageInventoryRules {
    /**
     * Splits recorded package files into the files only one installed package owns and the files several
     * installed packages claim. Shared files are listed once, under their own item, never under each owner.
     */
    fun splitOwnership(installed: Collection<String>, records: Map<String, ManagedTool>):
        Pair<Map<String, List<String>>, Map<String, List<String>>> {
        val owners = linkedMapOf<String, MutableList<String>>()
        installed.distinct().forEach { name ->
            val record = records[name] ?: return@forEach
            val paths = (record.files.filterNot { it.directory }.map { it.path } + record.ownedPaths)
                .filter { path -> path.isNotBlank() && !path.startsWith('/') && path.split('/').none { it == ".." } }.distinct()
            paths.forEach { owners.getOrPut(it) { mutableListOf() }.add(name) }
        }
        val exclusive = linkedMapOf<String, MutableList<String>>()
        val shared = linkedMapOf<String, List<String>>()
        owners.forEach { (path, names) ->
            if (names.size == 1) exclusive.getOrPut(names.single()) { mutableListOf() }.add(path) else shared[path] = names.sorted()
        }
        return exclusive to shared
    }

    /** Paths listed in a pip RECORD file (CSV: path,hash,size). Quoted paths are supported. */
    fun pipRecordPaths(text: String): List<String> = text.lineSequence().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val path = if (line.startsWith('"')) line.drop(1).substringBefore('"') else line.substringBefore(',')
        path.takeIf { it.isNotBlank() }
    }.toList()

    /** "requests-2.32.3.dist-info" becomes ("requests", "2.32.3"). */
    fun distInfoName(folder: String): Pair<String, String?> {
        val base = folder.removeSuffix(".dist-info").removeSuffix(".egg-info")
        val split = base.indexOf('-')
        return if (split <= 0) base to null else base.substring(0, split) to base.substring(split + 1).ifBlank { null }
    }

    /** Top-level packages in a node_modules folder, including scoped ones such as @scope/name. */
    fun npmPackages(nodeModules: File): List<Pair<String, File>> = nodeModules.listFiles().orEmpty()
        .filter { !it.name.startsWith('.') && java.nio.file.Files.isDirectory(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) }
        .flatMap { entry ->
            if (entry.name.startsWith('@')) entry.listFiles().orEmpty()
                .filter { java.nio.file.Files.isDirectory(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) }
                .map { "${entry.name}/${it.name}" to it }
            else listOf(entry.name to entry)
        }.sortedBy { it.first }

    private val MODEL_COMPLETE = listOf(".gguf", "-mmproj.gguf")
    private val MODEL_PARTIAL = listOf(".gguf.part", "-mmproj.gguf.part", ".gguf.part.unquantized", "-source")

    /** Groups model-folder entries by model id. The longest matching id wins; anything else is a leftover. */
    fun groupModelFiles(names: Collection<String>, ids: Collection<String>): Pair<Map<String, ModelFiles>, List<String>> {
        val byLength = ids.sortedByDescending { it.length }
        val grouped = linkedMapOf<String, ModelFiles>()
        val other = mutableListOf<String>()
        names.sorted().forEach { name ->
            val id = byLength.firstOrNull { id -> name.startsWith(id) && name.removePrefix(id).let { it in MODEL_COMPLETE || it in MODEL_PARTIAL } }
            if (id == null) { other += name; return@forEach }
            val current = grouped[id] ?: ModelFiles()
            grouped[id] = if (name.removePrefix(id) in MODEL_COMPLETE) current.copy(complete = current.complete + name)
                else current.copy(partial = current.partial + name)
        }
        return grouped to other
    }

    fun normalized(file: File): Path = file.toPath().toAbsolutePath().normalize()
}

/**
 * Builds the full inventory. Items are measured in a fixed order and each file is claimed by the first item that
 * sees it: shared caches first, then models, plugins, skills, package files, workspaces, and finally the
 * leftover folders. The order is what keeps a file from being counted under two categories.
 */
internal class StorageScanner(
    private val layout: StorageLayout,
    private val budgetMillis: Long = 120_000,
    private val now: () -> Long = System::nanoTime,
) {
    private class Task(
        val item: StorageItem,
        val roots: List<File> = emptyList(),
        val files: (() -> List<File>)? = null,
        /** Omit the item when nothing exists at its roots. */
        val optional: Boolean = true,
        /** Omit the item when every file it found already belongs to an earlier item. */
        val dropIfClaimed: Boolean = false,
        /** Final as built; no measurement. */
        val fixed: Boolean = false,
        /** One item over several locations: show the ones that exist as its paths. */
        val group: Boolean = false,
    )

    private val tasks = mutableListOf<Task>()
    private fun n(file: File) = StorageInventoryRules.normalized(file)
    private fun child(parent: File?, name: String) = parent?.let { File(it, name) }

    private fun add(role: StorageRole, root: File?, key: String = root?.path ?: role.name, name: String = "",
        optional: Boolean = true, shared: Boolean = false, owners: List<String> = emptyList()) {
        root ?: return
        tasks += Task(StorageItem(role, key, name, root.path, shared = shared, owners = owners), listOf(root), optional = optional)
    }

    /** One item measured across several locations, so related folders do not show up as look-alike rows. */
    private fun addGroup(role: StorageRole, roots: List<File?>, key: String = role.name) {
        val present = roots.filterNotNull().ifEmpty { return }
        tasks += Task(StorageItem(role, key, path = present.first().path), present, group = true)
    }

    private fun plan() {
        val l = layout
        val linux = l.linux
        val home = File(linux.prefix, "home")

        // Shared package-manager caches. They are claimed before the folders that contain them.
        add(StorageRole.CACHE_NPM, File(linux.sharedCacheRoot, "npm"), shared = true)
        add(StorageRole.CACHE_PIP, File(linux.sharedCacheRoot, "pip"), shared = true)
        add(StorageRole.CACHE_PACKAGE_OTHER, linux.sharedCacheRoot, shared = true)
        add(StorageRole.CACHE_NPM, File(home, ".npm"), shared = true)
        add(StorageRole.CACHE_PIP, File(home, ".cache/pip"), shared = true)
        add(StorageRole.CACHE_GRADLE, File(home, ".gradle/caches"), shared = true)
        add(StorageRole.CACHE_GRADLE_WRAPPER, File(home, ".gradle/wrapper/dists"), shared = true)
        add(StorageRole.CACHE_APT, File(linux.prefix, "var/cache/apt"), shared = true)
        add(StorageRole.CACHE_HOME_OTHER, File(home, ".cache"), shared = true)
        add(StorageRole.CACHE_DEB, File(l.cacheDir, "deb-download"), shared = true)
        add(StorageRole.CACHE_PLUGIN_PREVIEWS, File(l.cacheDir, "plugin-previews"))
        add(StorageRole.CACHE_SHARE, File(l.cacheDir, "shared"))
        add(StorageRole.CACHE_REMOTE_IMAGES, File(l.cacheDir, "remote-images"))
        add(StorageRole.CACHE_AUDIO, File(l.cacheDir, "audio_recordings"))
        add(StorageRole.CACHE_AUDIO, File(l.cacheDir, "tts"))
        add(StorageRole.CACHE_MCP_LOGS, File(l.cacheDir, "mcp"))

        // Local models, one item per model id.
        val modelNames = l.modelRoot.list()?.toList().orEmpty()
        val (models, leftovers) = StorageInventoryRules.groupModelFiles(modelNames, l.models.keys)
        models.forEach { (id, files) ->
            val title = l.models[id] ?: id
            if (files.complete.isNotEmpty()) tasks += Task(StorageItem(StorageRole.MODEL, id, title, File(l.modelRoot, files.complete.first()).path),
                files.complete.map { File(l.modelRoot, it) })
            if (files.partial.isNotEmpty()) tasks += Task(StorageItem(StorageRole.MODEL_PARTIAL, id, title, l.modelRoot.path),
                files.partial.map { File(l.modelRoot, it) })
        }
        if (leftovers.isNotEmpty() || l.modelRoot.exists()) add(StorageRole.MODEL_OTHER, l.modelRoot)
        add(StorageRole.GPU_RUNTIME, l.gpuRuntime)

        // Plugins, global and per workspace.
        val packages = File(l.pluginRoot, "packages")
        val registered = l.plugins
        if (registered == null) {
            tasks += Task(StorageItem(StorageRole.PLUGIN_REGISTRY, "registry-error", path = File(l.pluginRoot, "installed.json").path,
                gap = StorageGap.REGISTRY_UNREADABLE), fixed = true)
        }
        registered.orEmpty().forEach { plugin ->
            tasks += Task(StorageItem(StorageRole.PLUGIN, plugin.id, plugin.name, plugin.directory.path, scope = plugin.scope,
                version = plugin.version.ifBlank { null }, enabled = plugin.enabled), listOf(plugin.directory), optional = false)
        }
        val known = registered.orEmpty().map { n(it.directory) }.toSet()
        val orphans = packages.listFiles().orEmpty().filter { n(it) !in known }
        if (orphans.isNotEmpty()) tasks += Task(StorageItem(StorageRole.PLUGIN_LEFTOVERS, packages.path, path = packages.path), orphans)
        add(StorageRole.PLUGIN_REGISTRY, l.pluginRoot)

        // Skills: user folders, project folders inside each local workspace, and the ones bundled with the app.
        l.userSkillsDir.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.forEach { dir ->
            add(StorageRole.SKILL_USER, dir, key = dir.name, name = l.userSkillNames[dir.name] ?: dir.name)
        }
        val localRoots = l.workspaces.mapNotNull { it.root?.takeIf { _ -> it.gap == null } }.distinctBy { n(it) }
        localRoots.forEach { root ->
            val owner = l.workspaces.first { it.root != null && n(it.root) == n(root) }
            File(root, ".harness/skills").listFiles().orEmpty().filter { File(it, "SKILL.md").isFile }.sortedBy { it.name }.forEach { dir ->
                tasks += Task(StorageItem(StorageRole.SKILL_PROJECT, dir.path, dir.name, dir.path, scope = owner.name), listOf(dir))
            }
        }
        val appPackageId = StorageItem(StorageRole.APP_PACKAGE, "apk").id
        l.bundledSkills.distinct().sorted().forEach { name ->
            tasks += Task(StorageItem(StorageRole.SKILL_BUNDLED, name, name, gap = StorageGap.INSIDE_APP_PACKAGE, countedIn = appPackageId), fixed = true)
        }

        // Linux packages from their recorded file lists, then what npm and pip installed on top.
        val (exclusive, shared) = StorageInventoryRules.splitOwnership(linux.installed, linux.records)
        val otherLinux = StorageItem(StorageRole.LINUX_OTHER, linux.prefix.path).id
        linux.installed.distinct().sorted().forEach { name ->
            val record = linux.records[name]
            if (record == null) {
                tasks += Task(StorageItem(StorageRole.LINUX_UNTRACKED_PACKAGE, name, name, linux.prefix.path,
                    gap = StorageGap.UNTRACKED, countedIn = otherLinux), fixed = true)
            } else {
                val paths = exclusive[name].orEmpty()
                tasks += Task(StorageItem(StorageRole.LINUX_PACKAGE, name, name, linux.prefix.path, version = record.version.ifBlank { null },
                    owners = record.requirements.sorted()), files = { paths.map { File(linux.prefix, it) } }, optional = false)
            }
        }
        if (shared.isNotEmpty()) tasks += Task(StorageItem(StorageRole.LINUX_SHARED_FILES, linux.prefix.path + "#shared", path = linux.prefix.path,
            shared = true, owners = shared.values.flatten().distinct().sorted()), files = { shared.keys.map { File(linux.prefix, it) } }, optional = false)
        StorageInventoryRules.npmPackages(File(linux.prefix, "lib/node_modules")).forEach { (name, dir) ->
            tasks += Task(StorageItem(StorageRole.NPM_GLOBAL, name, name, dir.path), listOf(dir), dropIfClaimed = true)
        }
        sitePackages(linux).forEach { site ->
            site.listFiles().orEmpty().filter { it.name.endsWith(".dist-info") && File(it, "RECORD").isFile }.sortedBy { it.name }.forEach { info ->
                val (name, version) = StorageInventoryRules.distInfoName(info.name)
                tasks += Task(StorageItem(StorageRole.PIP_PACKAGE, info.path, name, site.path, version = version), files = {
                    val record = runCatching { File(info, "RECORD").readText() }.getOrDefault("")
                    StorageInventoryRules.pipRecordPaths(record).map { File(site, it) }
                        .filter { StorageFiles.contained(linux.prefix, it) }
                }, dropIfClaimed = true)
            }
        }
        add(StorageRole.CODEGRAPH_RUNTIME, File(linux.prefix, "codegraph"))
        add(StorageRole.PACKAGE_RECORDS, linux.toolRecords)
        add(StorageRole.LINUX_STAGING, linux.staging)
        linux.shellTier?.let { tasks += Task(StorageItem(StorageRole.SHELL_TIER_COPY, it.path, path = it.path), listOf(it)) }

        // Workspaces. Nested folders are measured first so the outer one leaves them out.
        val seen = mutableMapOf<Path, StorageItem>()
        val ordered = l.workspaces.sortedByDescending { it.root?.let(::n)?.nameCount ?: 0 }
        val planned = mutableMapOf<String, Task>()
        ordered.forEach { w ->
            val root = w.root
            val base = StorageItem(StorageRole.WORKSPACE, w.id, w.name, root?.path)
            planned[w.id] = when {
                w.gap != null || root == null -> Task(base.copy(gap = w.gap ?: StorageGap.SCAN_ERROR), fixed = true)
                seen[n(root)] != null -> Task(base.copy(gap = StorageGap.SAME_FOLDER, countedIn = seen.getValue(n(root)).id), fixed = true)
                else -> Task(base, listOf(root), optional = false).also { seen[n(root)] = base }
            }
        }
        // Keep the user's workspace order in the list, but measure in nesting order.
        tasks += ordered.mapNotNull { planned[it.id] }
        add(StorageRole.OTHER_REPOSITORIES, l.repositoriesRoot)

        // Leftover Linux folders, after packages and workspaces have claimed theirs.
        add(StorageRole.LINUX_HOME, home)
        add(StorageRole.LINUX_TMP, File(linux.prefix, "tmp"))
        add(StorageRole.LINUX_OTHER, linux.prefix, key = linux.prefix.path)

        // App data.
        if (l.apkFiles.isNotEmpty()) tasks += Task(StorageItem(StorageRole.APP_PACKAGE, "apk", path = l.apkFiles.first().parent), l.apkFiles)
        if (l.databases.isNotEmpty()) tasks += Task(StorageItem(StorageRole.CHAT_DATABASE, "db", path = l.databases.first().path), l.databases)
        add(StorageRole.CHAT_IMAGES, File(l.filesDir, "images"))
        add(StorageRole.UNDO_CHECKPOINTS, File(l.filesDir, "task-controls"))
        add(StorageRole.FILE_HISTORY, File(l.filesDir, "workspace-file-history"))
        addGroup(StorageRole.EDITOR_DRAFTS, listOf(File(l.noBackupDir, "editor-drafts"), File(l.noBackupDir, "editor-tabs")))
        add(StorageRole.CANVAS, File(l.filesDir, "harness-canvas"))
        addGroup(StorageRole.SETTINGS, listOf(File(l.dataDir, "shared_prefs"), File(l.filesDir, "datastore"), File(l.filesDir, "config")))
        addGroup(StorageRole.SCRATCH, listOf(File(l.filesDir, ".harness-scratch"), child(l.externalFilesDir, "shell-workspace"),
            File(l.filesDir, "shell-workspace")))
        add(StorageRole.ROUTER, File(l.filesDir, "9router"))
        add(StorageRole.TELEGRAM, File(l.filesDir, "telegram"))
        addGroup(StorageRole.WEB_DATA, listOf(File(l.dataDir, "app_webview"), File(l.cacheDir, "WebView")))
        addGroup(StorageRole.BACKGROUND_WORK, listOf("", "-shm", "-wal", "-journal").map { File(l.noBackupDir, "androidx.work.workdb$it") })
        add(StorageRole.CACHE_MODEL_CATALOG, File(l.filesDir, "models-dev.json"))

        // Whatever is left in the cache and data folders.
        addGroup(StorageRole.CACHE_APP_OTHER, listOf(l.cacheDir, l.externalCacheDir, l.codeCacheDir), key = l.cacheDir.path)
        addGroup(StorageRole.OTHER_APP_DATA, listOf(l.dataDir, l.externalFilesDir), key = l.dataDir.path)
    }

    private fun sitePackages(linux: StorageLinuxLayout): List<File> =
        listOf(File(linux.prefix, "lib"), File(linux.prefix, "home/.local/lib")).flatMap { lib ->
            lib.listFiles().orEmpty().filter { it.name.startsWith("python") }.map { File(it, "site-packages") }.filter { it.isDirectory }
        }

    suspend fun scan(onProgress: (StorageProgress) -> Unit = {}): StorageReport {
        tasks.clear()
        plan()
        val counter = StorageCounter(now() + budgetMillis * 1_000_000, now)
        val roots = tasks.flatMap { it.roots }.map(::n).toSet()
        val out = mutableListOf<StorageItem>()
        var timedOut = false
        tasks.forEachIndexed { index, task ->
            onProgress(StorageProgress(index, tasks.size, task.item.category, task.item.name.ifBlank { task.item.path.orEmpty() }))
            measure(task, counter, roots)?.let { item ->
                if (item.gap == StorageGap.TIMED_OUT) timedOut = true
                out += item
            }
        }
        onProgress(StorageProgress(tasks.size, tasks.size, null))
        // Workspaces return to the user's order; everything else keeps the planned order.
        val order = layout.workspaces.mapIndexed { i, w -> w.id to i }.toMap()
        val (workspaces, rest) = out.partition { it.role == StorageRole.WORKSPACE }
        return StorageReport(rest + workspaces.sortedBy { order[it.key] ?: Int.MAX_VALUE }, timedOut = timedOut)
    }

    private suspend fun measure(task: Task, counter: StorageCounter, allRoots: Set<Path>): StorageItem? {
        val item = task.item
        if (task.fixed) return item
        if (counter.expired) {
            val present = task.files != null || task.roots.any { it.exists() }
            return if (!present && task.optional) null else item.copy(gap = StorageGap.TIMED_OUT)
        }
        return try {
            val result = if (task.files != null) counter.measureFiles(task.files.invoke()) else {
                val own = task.roots.map(::n).toSet()
                val measures = task.roots.map { counter.measure(it, allRoots - own) }
                if (measures.isEmpty()) StorageMeasure(exists = false) else StorageMeasure(
                    bytes = measures.sumOf { it.bytes }, files = measures.sumOf { it.files },
                    alreadyCounted = measures.sumOf { it.alreadyCounted }, denied = measures.sumOf { it.denied },
                    errors = measures.sumOf { it.errors }, truncated = measures.any { it.truncated },
                    exists = measures.any { it.exists }, rootDenied = measures.all { it.rootDenied })
            }
            when {
                task.files == null && !result.exists -> if (task.optional) null else item.copy(gap = StorageGap.MISSING)
                result.rootDenied -> located(task, item).copy(gap = StorageGap.PERMISSION_DENIED)
                result.truncated -> item.copy(gap = StorageGap.TIMED_OUT, seenBytes = result.bytes)
                task.dropIfClaimed && result.files == 0 && result.alreadyCounted > 0 -> null
                task.files != null && task.optional && result.files == 0 && result.alreadyCounted == 0 && !result.partial -> null
                else -> located(task, item).copy(bytes = result.bytes, files = result.files, partial = result.partial)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { item.copy(gap = StorageGap.SCAN_ERROR) }
    }

    /** For a group, list only the locations that exist, so the shown path is never a missing folder. */
    private fun located(task: Task, item: StorageItem): StorageItem {
        if (!task.group) return item
        val present = task.roots.filter { it.exists() }.ifEmpty { return item }
        return item.copy(path = present.first().path, morePaths = present.drop(1).map { it.path })
    }
}
