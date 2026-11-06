package com.androidharness.app.data.storage

import com.androidharness.app.AppContainer
import kotlinx.coroutines.Dispatchers
import com.androidharness.app.skills.SkillSource
import com.androidharness.app.workspace.WorkspaceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** The full inventory plus the cleanup previews. No SAF traversal or SSH connection is started by a scan. */
data class StorageInventory(
    val report: StorageReport,
    val managedTools: Map<String, ManagedTool>,
    val protections: Map<String, String?>,
    val candidates: List<StorageCandidate>,
) {
    val bytes get() = report.total
    val incomplete get() = report.notMeasured.isNotEmpty() || report.partial > 0
}

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
        val (layout, localRoots) = layout()
        val report = StorageScanner(layout).scan { onProgress(it.copy(total = it.total + 1)) }
        val candidates = cleanupCandidates(localRoots)
        val installed = env.installedPackages()
        StorageInventory(report, env.managedTools(), installed.associateWith(env::packageProtection), candidates)
            .also { _last.value = it }
    }

    private suspend fun cleanupCandidates(localRoots: Map<String, File>): List<StorageCandidate> {
        val candidates = mutableListOf<StorageCandidate>()
        val projects = container.workspace.projects.first()
        val roots = localRoots.values.distinct()
        val safeCacheRoot = StorageFiles.contained(context.filesDir, env.sharedCacheRoot)
        val safeDownloads = StorageFiles.contained(context.cacheDir, File(context.cacheDir, "deb-download"))
        val cacheRoot = managed(env.sharedCacheRoot)
        val downloads = managed(File(context.cacheDir, "deb-download"))
        val dayAgo = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        // Serialize with package downloads, including queued installations, when making a preview.
        env.withPackageStorageLock {
            candidates += env.packageCleanupCandidates()
            (if (safeDownloads) downloads.listFiles().orEmpty() else emptyArray()).filter { it.isFile && it.name.endsWith(".deb") }.forEach { file ->
                StorageFiles.candidate("download:${file.name}", file.name, "Interrupted tool download", "Downloads",
                    downloads, file, dayAgo)?.let(candidates::add)
            }
        }
        // These roots contain cache data only; credentials/configuration are outside them.
        (if (safeCacheRoot) listOf(File(cacheRoot, "npm/_cacache"), File(cacheRoot, "pip")) else emptyList()).forEach { file ->
            StorageFiles.candidate("cache:${file.name}", if (file.name == "pip") "pip download cache" else "npm download cache",
                "Shared app cache · downloads again when needed", "Caches", cacheRoot, file, dayAgo)?.let(candidates::add)
        }
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        roots.forEach { root ->
            // Only recognized Gradle output trees, never arbitrary folders named build or project caches.
            val modules = listOf(root) + root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }
            modules.forEach { module ->
                if (!File(module, "build.gradle").isFile && !File(module, "build.gradle.kts").isFile) return@forEach
                val build = File(module, "build")
                if (!File(build, "outputs").isDirectory && !File(build, "intermediates").isDirectory) return@forEach
                // A workspace can point inside another project's output. That entire tree stays protected.
                if (roots.any { it != root && (it.toPath().startsWith(build.toPath()) || build.toPath().startsWith(it.toPath())) }) return@forEach
                listOf("outputs", "intermediates", "tmp").forEach { name ->
                    val output = File(build, name)
                    StorageFiles.candidate("build:${output.path}", "${module.name}/build/$name",
                        "${projects.firstOrNull { localRoots[it.id] == root }?.name ?: root.name} · build output older than 7 days",
                        "Build outputs", root, output, weekAgo)?.let(candidates::add)
                }
            }
        }
        return candidates.distinctBy { it.id }
    }

    suspend fun uninstallToolchain() = withContext(Dispatchers.IO) { withIdle { env.uninstall() } }

    private suspend fun <T> withIdle(action: suspend () -> T): T = container.runManager.withIdleStorage {
        container.backgroundProcesses.withIdleStorage { container.terminal.withClosedStorage(action) }
    }

    suspend fun clean(candidates: List<StorageCandidate>): StorageCleanupResult = withContext(Dispatchers.IO) {
        // Hold all start gates until deletion completes; a queued task/process/install cannot slip in.
        withIdle {
            env.withPackageStorageLock {
                var packageBytes = 0L; var packageFiles = 0; var skipped = 0
                for (candidate in candidates.filter { it.packageName != null }) {
                    try { packageBytes += env.removeUnusedPackage(candidate); packageFiles += candidate.stamps.size }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { skipped++ }
                }
                val result = StorageFiles.clean(candidates.filter { it.packageName == null })
                result.copy(removedBytes = result.removedBytes + packageBytes,
                    removedFiles = result.removedFiles + packageFiles, skipped = result.skipped + skipped)
            }
        }
    }
}
