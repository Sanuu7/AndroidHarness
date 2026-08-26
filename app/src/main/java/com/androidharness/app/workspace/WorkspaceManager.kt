package com.androidharness.app.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.androidharness.app.data.db.HarnessDao
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.data.env.PathClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

/** Plain-language rendering of a project kind for the workspace UI. */
data class WorkspaceDescription(
    val kindLabel: String,
    val kindSub: String,
    val shellCapable: Boolean,
)

/** Result of checking whether a path could be a real-path workspace. */
data class PathAssessment(
    val directoryExists: Boolean,
    val region: PathClassifier.Region?,
)

/**
 * Project-aware workspace manager. A "project" is either the app-private
 * workspace (real filesystem, shell-capable) or a user-picked SAF folder.
 */
class WorkspaceManager(
    private val context: Context,
    private val dao: HarnessDao,
) {
    val appPrivateRoot: File =
        (context.getExternalFilesDir(null) ?: context.filesDir)
            .resolve("workspace")
            .apply { mkdirs() }

    val projects: Flow<List<ProjectEntity>> = dao.projectsFlow()

    val currentProject: Flow<ProjectEntity> = projects.map { list ->
        list.firstOrNull() ?: ensureDefaultProject(list)
    }

    val current: Flow<WorkspaceFs> = currentProject.map { fsFor(it) }

    suspend fun currentOnce(): WorkspaceFs = current.first()

    suspend fun currentProjectOnce(): ProjectEntity = currentProject.first()

    private suspend fun ensureDefaultProject(existing: List<ProjectEntity>): ProjectEntity {
        // migrate: first access creates the app workspace project
        val project = existing.firstOrNull() ?: ProjectEntity(
            id = UUID.randomUUID().toString(),
            name = "App workspace",
            kind = KIND_APP,
            uri = null,
            lastUsedAt = System.currentTimeMillis(),
        )
        if (existing.isEmpty()) dao.insertProject(project)
        return project
    }

    fun fsFor(project: ProjectEntity): WorkspaceFs = when {
        project.kind == KIND_SAF && project.uri != null -> {
            val treeUri = project.uri.toUri()
            val real = SafPathResolver.resolve(treeUri)?.let { java.io.File(it) }
            if (real != null && real.isDirectory) {
                FileFs(real)
            } else {
                runCatching { SafFs(context, treeUri) }.getOrElse { FileFs(appPrivateRoot) }
            }
        }
        project.kind == KIND_SHELL && project.uri != null ->
            FileFs(java.io.File(project.uri))
        else -> FileFs(appPrivateRoot)
    }

    suspend fun addSafProject(treeUri: Uri): ProjectEntity {
        val existing = findDuplicate(projects.first(), KIND_SAF, treeUri.toString())
        if (existing != null) {
            return reactivate(existing)
        }
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val name = treeUri.lastPathSegment
            ?.substringAfterLast(':')
            ?.ifBlank { null } ?: "Picked folder"
        val project = ProjectEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            kind = KIND_SAF,
            uri = treeUri.toString(),
            lastUsedAt = System.currentTimeMillis(),
        )
        dao.insertProject(project)
        setActiveProject(project.id)
        return project
    }

    /** Adds a folder chosen in the system picker. When the SAF tree maps to a
     * real path on shared storage the workspace is upgraded to a SHELL
     * project (full shell) instead of a file-tools-only SAF one.
     */
    suspend fun addPickedFolder(treeUri: Uri): ProjectEntity {
        val existing = findDuplicate(projects.first(), KIND_SAF, treeUri.toString())
        if (existing != null) {
            return reactivate(existing)
        }
        val path = SafPathResolver.resolve(treeUri)
        if (path != null && java.io.File(path).isDirectory) {
            return addShellProject(path)
        }
        return addSafProject(treeUri)
    }

    /**
     * Identity of a workspace folder for duplicate detection. A picked folder
     * that maps to a real path gets the SAME key as that path added via the
     * device browser, so "same folder twice through different doors" still
     * collides. Returns null only for the app workspace (never deduped).
     */
    private suspend fun reactivate(project: ProjectEntity): ProjectEntity {
        setActiveProject(project.id)
        return project
    }

    suspend fun setActiveProject(id: String) {
        dao.touchProject(id, System.currentTimeMillis())
    }

    /**
     * Deletes any non-app workspace (the app workspace is permanent). For a SAF
     * project this also releases the persisted tree-URI permission so the folder
     * stops being held open.
     */
    suspend fun deleteProject(project: ProjectEntity) {
        if (project.kind == KIND_APP) return
        if (project.kind == KIND_SAF && project.uri != null) {
            releaseSafPermission(project.uri)
        }
        dao.deleteProject(project)
    }

    private fun releaseSafPermission(uriString: String) {
        runCatching {
            val target = uriString.toUri()
            context.contentResolver.persistedUriPermissions
                .filter { it.uri == target }
                .forEach { perm ->
                    context.contentResolver.releasePersistableUriPermission(
                        perm.uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
        }
    }

    /** Adds a project backed by a real filesystem path (requires Shizuku or All files access). */
    suspend fun addShellProject(path: String): ProjectEntity {
        val existing = findDuplicate(projects.first(), KIND_SHELL, path)
        if (existing != null) {
            return reactivate(existing)
        }
        val project = ProjectEntity(
            id = java.util.UUID.randomUUID().toString(),
            name = path.substringAfterLast('/').ifBlank { path },
            kind = KIND_SHELL,
            uri = path,
            lastUsedAt = System.currentTimeMillis(),
        )
        dao.insertProject(project)
        setActiveProject(project.id)
        return project
    }

    /**
     * Plain-language description + shell capability for the workspace list UI.
     */
    fun describe(project: ProjectEntity): WorkspaceDescription {
        val kindLabel: String
        val kindSub: String
        val shellCapable: Boolean
        when (project.kind) {
            KIND_APP -> {
                kindLabel = "App workspace"
                kindSub = "Private app folder: shell always works, safest option"
                shellCapable = true
            }
            KIND_SHELL -> {
                kindLabel = "Device folder"
                kindSub = "Real path: full shell, needs All files access or Shizuku"
                shellCapable = true
            }
            else -> {
                val real = project.uri?.let { runCatching { SafPathResolver.resolve(it.toUri()) }.getOrNull() }
                if (real != null && java.io.File(real).isDirectory) {
                    kindLabel = "Picked folder"
                    kindSub = "Mapped to $real. File tools and shell share this tree."
                    shellCapable = true
                } else {
                    kindLabel = "Picked folder"
                    kindSub = "Cloud/SAF folder: file tools only. Shell cannot run here."
                    shellCapable = false
                }
            }
        }
        return WorkspaceDescription(kindLabel, kindSub, shellCapable)
    }

    /**
     * Checks whether [path] could be used as a real-path workspace and what it
     * would require. Pure filesystem + region check; permission state lives in
     * the settings UI via [com.androidharness.app.data.env.ShellTierRouter].
     */
    fun assessPath(path: String): PathAssessment {
        val trimmed = path.trimEnd('/')
        val f = File(if (trimmed.isEmpty()) path else trimmed)
        if (!f.isDirectory) {
            return PathAssessment(directoryExists = false, region = null)
        }
        val region = PathClassifier.regionOf(f.absolutePath, context.dataDir.absolutePath)
        return PathAssessment(directoryExists = true, region = region)
    }

    /** Releases all persisted SAF permissions (no longer used by the UI). */
    suspend fun releaseSafPermissions() {
        runCatching {
            context.contentResolver.persistedUriPermissions.forEach { perm ->
                context.contentResolver.releasePersistableUriPermission(
                    perm.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    companion object {
        const val KIND_APP = "APP"
        const val KIND_SAF = "SAF"
        const val KIND_SHELL = "SHELL"

        /**
         * The existing project pointing at the same folder as [kind]/[uri].
         */
        fun findDuplicate(
            projects: List<ProjectEntity>,
            kind: String,
            uri: String?,
            resolveSaf: (String) -> String? = { SafPathResolver.resolve(it.toUri()) },
        ): ProjectEntity? {
            val key = dedupeKey(kind, uri, resolveSaf) ?: return null
            return projects.firstOrNull { dedupeKey(it.kind, it.uri, resolveSaf) == key }
        }

        /**
         * Identity of a workspace folder for duplicate detection, pure so it
         * is unit-testable without Android. A picked folder that maps to a
         * real path gets the SAME key as that path added via the device
         * browser, so "same folder twice through different doors" still
         * collides. Returns null only for the app workspace (never deduped).
         */
        fun dedupeKey(
            kind: String,
            uri: String?,
            resolveSaf: (String) -> String? = { SafPathResolver.resolve(it.toUri()) },
        ): String? = when {
            kind == KIND_APP -> null
            kind == KIND_SAF && uri != null ->
                resolveSaf(uri)?.let { "$KIND_SHELL:${it.trimEnd('/').ifEmpty { "/" }}" }
                    ?: "$KIND_SAF:$uri"
            else -> "$KIND_SHELL:${uri?.trimEnd('/')?.ifEmpty { "/" }}"
        }
    }
}
