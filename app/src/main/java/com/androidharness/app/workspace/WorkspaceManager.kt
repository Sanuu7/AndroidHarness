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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val sshConnections: SshConnections? = null,
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

    /** The recent workspace is only a default for new chats. Existing chats own their assignment. */
    fun projectForChat(sessionId: String?): Flow<ProjectEntity?> = if (sessionId == null) currentProject else flow {
        val session = dao.session(sessionId)
        if (session != null && session.projectId == null) {
            dao.bindSessionProject(sessionId, currentProjectOnce().id)
        }
        emitAll(combine(dao.sessionFlow(sessionId), projects) { saved, available ->
            available.firstOrNull { it.id == saved?.projectId }
        }.distinctUntilChanged { previous, next ->
            previous?.copy(lastUsedAt = 0) == next?.copy(lastUsedAt = 0)
        })
    }

    fun forChat(sessionId: String?): Flow<WorkspaceFs?> = projectForChat(sessionId)
        .map { project -> project?.let { runCatching { fsFor(it) }.getOrNull() } }

    suspend fun projectForSession(sessionId: String): ProjectEntity =
        projectForChat(sessionId).first() ?: error("This chat's workspace is unavailable. Choose a workspace for this chat.")

    suspend fun forSession(sessionId: String): WorkspaceFs = fsFor(projectForSession(sessionId))

    /** Call through RunManager, which guards active tasks and saved file changes. */
    suspend fun assignSession(sessionId: String, projectId: String) {
        requireNotNull(dao.session(sessionId)) { "This chat no longer exists" }
        requireNotNull(dao.project(projectId)) { "This workspace no longer exists" }
        dao.setSessionProject(sessionId, projectId)
        setActiveProject(projectId)
    }

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
        project.kind == KIND_SSH -> SshFs(requireNotNull(sshConnections),
            kotlinx.serialization.json.Json.decodeFromString<SshLocation>(requireNotNull(project.uri)))
        project.kind == KIND_SAF && project.uri != null -> {
            val treeUri = project.uri.toUri()
            val real = writablePickedFolder(treeUri)
            if (real != null) {
                FileFs(real)
            } else {
                SafFs(context, treeUri)
            }
        }
        project.kind == KIND_SHELL && project.uri != null ->
            FileFs(java.io.File(project.uri))
        else -> FileFs(appPrivateRoot)
    }

    suspend fun addSafProject(treeUri: Uri): ProjectEntity {
        val existing = findDuplicate(projects.first(), KIND_SAF, treeUri.toString())
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        if (existing != null) {
            // A previously direct folder may now need the picker grant. Keep its
            // identity and history, but retain the URI instead of an inaccessible path.
            val picked = existing.copy(kind = KIND_SAF, uri = treeUri.toString())
            dao.setProjectLocation(picked.id, picked.kind, requireNotNull(picked.uri))
            return reactivate(picked)
        }
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

    /** Always retain the picker grant. [fsFor] enables shell access when the
     * real path is writable, and falls back to SAF if broad access is revoked. */
    suspend fun addPickedFolder(treeUri: Uri): ProjectEntity = addSafProject(treeUri)

    private fun writablePickedFolder(treeUri: Uri): File? =
        directFolderOrNull(SafPathResolver.resolve(treeUri))

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
        check(dao.projectSessionCount(project.id) == 0) {
            "This workspace belongs to saved chats. Delete those chats before removing it."
        }
        if (project.kind == KIND_SAF && project.uri != null) {
            releaseSafPermission(project.uri)
        }
        dao.deleteProject(project)
        if (project.kind == KIND_SSH) {
            val id = runCatching { kotlinx.serialization.json.Json.decodeFromString<SshLocation>(requireNotNull(project.uri)).connectionId }.getOrNull()
            if (id != null && projects.first().none {
                it.kind == KIND_SSH && runCatching { kotlinx.serialization.json.Json.decodeFromString<SshLocation>(requireNotNull(it.uri)).connectionId }.getOrNull() == id
            }) sshConnections?.forget(id)
        }
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

    suspend fun addSshProject(connection: SshConnection, root: String): ProjectEntity {
        val location = SshLocation(connection.id, root.trimEnd('/').ifEmpty { "/" })
        val uri = kotlinx.serialization.json.Json.encodeToString(SshLocation.serializer(), location)
        requireNotNull(sshConnections).save(connection)
        val existing = projects.first().firstOrNull { it.kind == KIND_SSH && it.uri == uri }
        if (existing != null) return reactivate(existing)
        val label = root.substringAfterLast('/').ifBlank { connection.host }
        val project = ProjectEntity(UUID.randomUUID().toString(),
            label + if (connection.termux) " · Termux SSH" else " · SSH",
            KIND_SSH, uri, System.currentTimeMillis())
        dao.insertProject(project)
        setActiveProject(project.id)
        return project
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
            KIND_SSH -> {
                kindLabel = "SSH workspace"
                kindSub = "Files, terminal and Git on the connected host"
                shellCapable = true
            }
            KIND_APP -> {
                kindLabel = "App workspace"
                kindSub = "Private app folder: shell always works, safest option"
                shellCapable = true
            }
            KIND_SHELL -> {
                kindLabel = "Device folder"
                kindSub = "Real path: full shell, needs storage access or Shizuku"
                shellCapable = true
            }
            else -> {
                val real = project.uri?.let { writablePickedFolder(it.toUri()) }
                if (real != null) {
                    kindLabel = "Picked folder"
                    kindSub = "Mapped to $real. File tools and shell share this tree."
                    shellCapable = true
                } else {
                    kindLabel = "Picked folder"
                    kindSub = "Picker access: file tools only. Use the app workspace for shell commands."
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
        val region = PathClassifier.regionOf(
            f.absolutePath, context.dataDir.absolutePath, context.getExternalFilesDir(null)?.absolutePath,
        )
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
        const val KIND_SSH = "SSH"

        /** A mapped path alone does not grant permission to read or write it. */
        internal fun directFolderOrNull(path: String?): File? = runCatching {
            path?.let { File(it) }?.takeIf { it.isDirectory && it.canRead() && it.canWrite() }
        }.getOrNull()

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
            kind == KIND_SSH -> "$KIND_SSH:$uri"
            kind == KIND_SAF && uri != null ->
                resolveSaf(uri)?.let { "$KIND_SHELL:${it.trimEnd('/').ifEmpty { "/" }}" }
                    ?: "$KIND_SAF:$uri"
            else -> "$KIND_SHELL:${uri?.trimEnd('/')?.ifEmpty { "/" }}"
        }
    }
}
