package com.androidharness.app.data.storage

import com.androidharness.app.AppContainer
import kotlinx.coroutines.Dispatchers
import com.androidharness.app.skills.SkillSource
import com.androidharness.app.workspace.WorkspaceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.datastore.preferences.core.edit
import com.androidharness.app.data.settingsStore
import java.io.File

/** The full inventory plus what cleanup may remove. No SAF traversal or SSH connection is started by a scan. */
data class StorageInventory(
    val report: StorageReport,
    val managedTools: Map<String, ManagedTool>,
    /** Every installed Linux package and why it stays, or null when it can be removed. */
    val protections: Map<String, PackageProtection?>,
    /** Removal previews for the removable Linux packages. */
    val candidates: List<StorageCandidate>,
) {
    val bytes get() = report.total
    val incomplete get() = report.notMeasured.isNotEmpty() || report.partial > 0
    val plan: CleanupPlan by lazy { CleanupPlanner.plan(report, protections) }
}

/** One entry that could not be cleaned, with the reason. */
data class CleanupFailure(val entry: CleanupEntry, val message: String)
data class CleanupOutcome(val freedBytes: Long, val cleaned: List<CleanupEntry>, val failed: List<CleanupFailure>)

class StorageManager(private val container: AppContainer) {
    private val context get() = container.appContext
    private val env get() = container.linuxEnv
    private fun canonical(file: File) = file.canonicalFile
    // Resolve Android's app-data aliases, but never turn a managed-folder symlink into a scan root.
    private fun managed(file: File) = if (StorageFiles.contained(context.dataDir, file)) canonical(file) else
        file.parentFile?.let { parent -> runCatching { File(parent.canonicalFile, file.name) }.getOrNull() } ?: file.absoluteFile

    private val _last = MutableStateFlow<StorageInventory?>(null)
    /** The latest finished scan, kept so the screen can show it while a rescan runs. */
    val last = _last.asStateFlow()

    private suspend fun layout(): Pair<StorageLayout, Map<String, File>> {
        val projects = container.workspace.projects.first()
        val roots = linkedMapOf<String, File>()
        val workspaces = projects.map { project ->
            if (project.kind == WorkspaceManager.KIND_SSH) return@map StorageWorkspaceEntry(project.id, project.name, null, StorageGap.SSH_HOST)
            val root = runCatching { container.workspace.fsFor(project).shellRoot?.let(::canonical) }
            val folder = root.getOrNull()
            when {
                root.isFailure -> StorageWorkspaceEntry(project.id, project.name, null, StorageGap.SCAN_ERROR)
                folder == null -> StorageWorkspaceEntry(project.id, project.name, null, StorageGap.SAF_FOLDER)
                !folder.exists() -> StorageWorkspaceEntry(project.id, project.name, folder, StorageGap.MISSING)
                !folder.isDirectory || !folder.canRead() -> StorageWorkspaceEntry(project.id, project.name, folder, StorageGap.PERMISSION_DENIED)
                else -> StorageWorkspaceEntry(project.id, project.name, folder).also { roots[project.id] = folder }
            }
        }
        val external = context.getExternalFilesDir(null)?.let(::managed)
        val staging = File(context.getExternalFilesDir(null) ?: context.filesDir, "linux-deploy")
        val plugins = runCatching {
            val store = container.pluginManager.store
            store.list().map { record ->
                StoragePlugin(record.id, record.name, record.version, record.scope.takeIf { it != "global" }?.let { record.scopeLabel.ifBlank { it } },
                    record.enabled, managed(runCatching { store.directory(record) }.getOrElse { File(context.filesDir, "plugins/packages/${record.directory}") }))
            }
        }.getOrNull()
        val skills = runCatching { container.skills.list() }.getOrDefault(emptyList())
        val info = context.applicationInfo
        val linux = StorageLinuxLayout(
            prefix = managed(env.prefix), sharedCacheRoot = managed(env.sharedCacheRoot),
            toolRecords = managed(File(context.filesDir, "shared-tools")),
            installed = env.installedPackages(), records = env.managedTools(),
            staging = managed(staging),
            shellTier = File(env.tmpBase).takeIf { File(staging, ".harness-staged").exists() },
        )
        return StorageLayout(
            dataDir = canonical(context.dataDir), filesDir = managed(context.filesDir), cacheDir = managed(context.cacheDir),
            noBackupDir = managed(context.noBackupFilesDir), linux = linux,
            codeCacheDir = managed(context.codeCacheDir), externalFilesDir = external,
            externalCacheDir = context.externalCacheDir?.let(::managed),
            apkFiles = (listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty()).map(::File).distinct(),
            databases = listOf("harness.db", "harness.db-wal", "harness.db-shm", "harness.db-journal").map { managed(context.getDatabasePath(it)) },
            pluginRoot = managed(File(context.filesDir, "plugins")), plugins = plugins,
            userSkillsDir = managed(File(context.filesDir, "skills")),
            userSkillNames = skills.filter { it.source == SkillSource.USER }.associate { it.relativeDir to it.name },
            bundledSkills = skills.filter { it.source == SkillSource.BUNDLED }.map { it.name },
            modelRoot = managed(File(context.noBackupFilesDir, "local-models")),
            models = container.localModels.models.value.associate { it.id to it.title },
            gpuRuntime = managed(File(context.noBackupFilesDir, "gpu-runtime")),
            workspaces = workspaces,
            repositoriesRoot = managed(File(context.filesDir, "github-projects")),
        ) to roots
    }

    /** Measures everything off the main thread. Cancel the calling job to stop; nothing is kept from a stopped scan. */
    suspend fun scan(onProgress: (StorageProgress) -> Unit = {}): StorageInventory = withContext(Dispatchers.IO) {
        val (layout, _) = layout()
        val report = StorageScanner(layout).scan { onProgress(it.copy(total = it.total + 1)) }
        // Serialize with package installs so a preview never sees a half-written package.
        val (protections, candidates) = env.withPackageStorageLock { env.packageCleanup() }
        StorageInventory(report, env.managedTools(), protections, candidates).also { _last.value = it }
    }

    suspend fun uninstallToolchain() = withContext(Dispatchers.IO) { withIdle { env.uninstall() } }

    private suspend fun <T> withIdle(action: suspend () -> T): T = container.runManager.withIdleStorage {
        container.backgroundProcesses.withIdleStorage { container.terminal.withClosedStorage(action) }
    }

    /**
     * Folders cleanup never touches, whatever an entry claims. Linux caches and temp files are the only
     * exceptions inside the Linux environment.
     */
    private suspend fun guard(): StorageGuard {
        val home = File(env.prefix, "home")
        val workspaces = container.workspace.projects.first().mapNotNull { project ->
            runCatching { container.workspace.fsFor(project).shellRoot }.getOrNull()
        }
        val protectedRoots = listOf(
            File(context.filesDir, "9router"), env.prefix, File(context.filesDir, "shared-tools"),
            File(context.filesDir, "github-projects"), File(context.filesDir, "task-controls"),
            File(context.filesDir, "workspace-file-history"), File(context.noBackupFilesDir, "editor-drafts"),
            File(context.noBackupFilesDir, "editor-tabs"), File(context.filesDir, "telegram"),
            File(context.filesDir, "config-bindings.json"), File(context.noBackupFilesDir, "gpu-runtime"),
            File(context.dataDir, "shared_prefs"), File(context.filesDir, "datastore"),
            context.getDatabasePath("harness.db").parentFile!!, File(context.filesDir, "plugins/installed.json"),
            context.codeCacheDir,
        ) + workspaces
        val exceptions = listOf(
            File(env.prefix, "tmp"), File(home, ".cache"), File(home, ".npm"), File(home, ".gradle/caches"),
            File(home, ".gradle/wrapper/dists"), File(env.prefix, "var/cache/apt"),
        )
        return StorageGuard(protectedRoots, exceptions)
    }

    /**
     * Removes the chosen entries. Every entry is checked against the current scan's plan, so a kept item or a
     * stale id is never cleaned. Runs only while no task, background process or terminal can use the files.
     */
    suspend fun cleanUp(chosen: List<CleanupEntry>): CleanupOutcome = withContext(Dispatchers.IO) {
        val offered = _last.value?.plan?.entries.orEmpty().associateBy { it.id }
        val cleaned = mutableListOf<CleanupEntry>()
        val failed = mutableListOf<CleanupFailure>()
        withIdle {
            env.withPackageStorageLock {
                val guard = guard()
                // Packages go first and one at a time: each removal changes what the next one depends on.
                val ordered = chosen.distinctBy { it.id }.sortedBy { if (it.kind == CleanupKind.LINUX_PACKAGE) 0 else 1 }
                for (entry in ordered) {
                    if (offered[entry.id]?.kind != entry.kind) { failed += CleanupFailure(entry, "Scan again before cleaning this."); continue }
                    try { clean(entry, guard); cleaned += entry }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { failed += CleanupFailure(entry, e.message ?: e.javaClass.simpleName) }
                }
            }
        }
        CleanupOutcome(cleaned.sumOf { it.bytes }, cleaned, failed)
    }

    private suspend fun clean(entry: CleanupEntry, guard: StorageGuard) {
        val item = entry.item
        val paths = listOfNotNull(item.path) + item.morePaths
        when (entry.kind) {
            CleanupKind.WEB_DATA -> {
                withContext(Dispatchers.Main) {
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    android.webkit.CookieManager.getInstance().apply { removeAllCookies(null); flush() }
                    android.webkit.WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
                    android.webkit.WebView(context).apply { clearCache(true); clearHistory(); destroy() }
                }
                // Cookies and site storage are cleared through WebView; only its caches are deleted as files.
                val profile = File(context.dataDir, "app_webview/Default")
                (listOf(File(context.cacheDir, "WebView")) + listOf("Cache", "Code Cache", "GPUCache", "Service Worker").map { File(profile, it) })
                    .forEach { StorageDelete.contents(it, guard) }
            }
            CleanupKind.CHAT_IMAGES, CleanupKind.DOWNLOADS, CleanupKind.SCRATCH, CleanupKind.CACHE ->
                paths.map(::File).filterNot { it == context.codeCacheDir }.forEach { file ->
                    if (file.isFile) StorageDelete.file(file, guard) else StorageDelete.contents(file, guard)
                }
            CleanupKind.CANVAS -> {
                com.androidharness.app.data.HarnessCanvasStore(context, container.settings).remove()
                StorageDelete.contents(com.androidharness.app.data.HarnessCanvasStore.directory(context), guard)
            }
            CleanupKind.MODEL -> cleanModel(item, guard)
            CleanupKind.CHAT_HISTORY -> container.sessions.deleteAllSessions()
            CleanupKind.SETTINGS -> resetSettings(guard)
            CleanupKind.PLUGIN -> {
                val store = container.pluginManager.store
                if (item.role == StorageRole.PLUGIN && store.list().any { it.id == item.key }) store.uninstall(item.key)
                store.pruneUnused()
            }
            CleanupKind.SKILL -> {
                require(item.key.isNotBlank() && '/' !in item.key && item.key != "..") { "Unexpected skill folder." }
                val folder = File(File(context.filesDir, "skills"), item.key)
                require(guard.allows(folder)) { "This skill is protected." }
                check(container.skills.deleteUser(item.key) || !folder.exists()) { "Could not delete the skill." }
            }
            CleanupKind.LINUX_PACKAGE -> {
                // A fresh preview, made under the package lock, so nothing changed since it was checked.
                val (protections, candidates) = env.packageCleanup()
                protections[item.key]?.let { error(it.text) }
                val candidate = candidates.firstOrNull { it.packageName == item.key } ?: error("Package files changed. Scan again.")
                env.removeUnusedPackage(candidate)
            }
        }
    }

    private suspend fun cleanModel(item: StorageItem, guard: StorageGuard) {
        val models = container.localModels
        val root = managed(File(context.noBackupFilesDir, "local-models"))
        val ids = models.models.value.map { it.id }
        val (grouped, leftovers) = StorageInventoryRules.groupModelFiles(root.list()?.toList().orEmpty(), ids)
        when (item.role) {
            StorageRole.MODEL -> models.remove(item.key)
            StorageRole.MODEL_PARTIAL -> {
                models.cancelDownload(item.key)
                grouped[item.key]?.partial.orEmpty().forEach { StorageDelete.tree(File(root, it), guard) }
            }
            else -> leftovers.forEach { StorageDelete.tree(File(root, it), guard) }
        }
    }

    /**
     * Resets app preferences and harness configuration files. Provider keys, provider and voice setups,
     * sign-ins, SSH and wireless ADB pairing, 9router and the configuration secrets list are kept, because they
     * live in stores this list does not name.
     */
    private suspend fun resetSettings(guard: StorageGuard) {
        val onboarding = androidx.datastore.preferences.core.booleanPreferencesKey("onboarding_done")
        context.settingsStore.edit { prefs ->
            val done = prefs[onboarding]
            prefs.clear()
            done?.let { prefs[onboarding] = it }
        }
        RESETTABLE_PREFERENCES.forEach { context.getSharedPreferences(it, android.content.Context.MODE_PRIVATE).edit().clear().commit() }
        val config = File(context.filesDir, "config")
        if (guard.allows(config)) StorageDelete.contents(config, guard)
        container.configuration.revision.value += 1
    }

    private companion object {
        /** Preference files that hold only app choices. Anything not listed here is kept. */
        val RESETTABLE_PREFERENCES = listOf(
            "notices", "shizuku_notices", "schedule_preferences", "subagent-bubble-position", "build_test_commands",
            "project_launch", "project_checks", "remembered_grants", "update-installation",
        )
    }
}

/** Deletes without following links. Each call is checked against the guard first. */
internal object StorageDelete {
    fun contents(dir: File, guard: StorageGuard) {
        if (!java.nio.file.Files.isDirectory(dir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        dir.listFiles().orEmpty().forEach { tree(it, guard) }
    }

    fun file(file: File, guard: StorageGuard) {
        check(guard.allows(file)) { "${file.name} is protected." }
        java.nio.file.Files.deleteIfExists(file.toPath())
    }

    fun tree(target: File, guard: StorageGuard) {
        check(guard.allows(target)) { "${target.name} is protected." }
        val path = target.toPath()
        if (!java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        // walkFileTree does not follow links: a link is deleted as a link, never what it points at.
        java.nio.file.Files.walkFileTree(path, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes) =
                java.nio.file.FileVisitResult.CONTINUE.also { java.nio.file.Files.deleteIfExists(file) }
            override fun visitFileFailed(file: java.nio.file.Path, exc: java.io.IOException) =
                java.nio.file.FileVisitResult.CONTINUE.also { java.nio.file.Files.deleteIfExists(file) }
            override fun postVisitDirectory(dir: java.nio.file.Path, exc: java.io.IOException?) =
                java.nio.file.FileVisitResult.CONTINUE.also { java.nio.file.Files.deleteIfExists(dir) }
        })
    }
}
