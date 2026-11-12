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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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

    fun selectionForChat(sessionId: String?): Flow<ChatWorkspaceSelection?> = if (sessionId == null)
        currentProject.map { ChatWorkspaceSelection.single(it.id) }
    else flow {
        if (dao.session(sessionId)?.projectId == null) projectForChat(sessionId).first()
        emitAll(dao.sessionFlow(sessionId).map { saved -> saved?.let {
            it.workspaceSelectionJson?.let { raw -> runCatching { ChatWorkspaceSelection.decode(raw) }.getOrNull() }
                ?: if (it.workspaceSelectionJson == null) it.projectId?.let(ChatWorkspaceSelection::single) else null
        } })
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun forChat(sessionId: String?): Flow<WorkspaceFs?> = projectForChat(sessionId).flatMapLatest { project ->
        if (project == null) flowOf(null)
        else if (sessionId == null) flowOf(fsFor(project))
        else combine(dao.sessionFlow(sessionId), projects) { saved, available ->
            saved?.let { runCatching { scopedFs(it.workspaceSelectionJson, project, available) }.getOrNull() }
        }
    }

    /** Project tools must not change the recent workspace or create a chat just to browse files. */
    fun projectForTools(sessionId: String?, projectId: String?): Flow<ProjectEntity?> =
        if (sessionId != null || projectId == null) projectForChat(sessionId)
        else projects.map { list -> list.firstOrNull { it.id == projectId } }
            .distinctUntilChanged { previous, next -> previous?.copy(lastUsedAt = 0) == next?.copy(lastUsedAt = 0) }

    fun forTools(sessionId: String?, projectId: String?): Flow<WorkspaceFs?> = if (sessionId != null) forChat(sessionId)
        else projectForTools(null, projectId).map { project -> project?.let { runCatching { fsFor(it) }.getOrNull() } }

    suspend fun projectForSession(sessionId: String): ProjectEntity =
        projectForChat(sessionId).first() ?: error("This chat's workspace is unavailable. Choose a workspace for this chat.")

    suspend fun forSession(sessionId: String): WorkspaceFs {
        val primary = projectForSession(sessionId)
        val session = requireNotNull(dao.session(sessionId)) { "This chat no longer exists." }
        return scopedFs(session.workspaceSelectionJson, primary, projects.first())
    }

    private fun scopedFs(raw: String?, primary: ProjectEntity, available: List<ProjectEntity>): WorkspaceFs {
        if (raw == null) return fsFor(primary)
        val selection = ChatWorkspaceSelection.decode(raw)
        check(selection.primaryId == primary.id) { "This chat's primary workspace changed. Choose its workspaces again." }
        return ChatWorkspaceFs(selection.primaryId, selection.grants.map { grant ->
            val project = available.firstOrNull { it.id == grant.projectId }
                ?: error("A selected workspace is unavailable. Open Workspaces to review this chat's access.")
            check(project.kind != KIND_SHELL || project.uri?.let { File(it).isDirectory } == true) {
                "${project.name} is unavailable. Restore its folder or review this chat's workspace access."
            }
            ChatWorkspaceBinding(project.id, project.name, grant.canEdit, fsFor(project))
        })
    }

    /** Called under RunManager's guard, after the user saves the selection. */
    suspend fun assignSelection(sessionId: String, selection: ChatWorkspaceSelection) {
        val saved = requireNotNull(dao.session(sessionId)) { "This chat no longer exists." }
        validateSelection(selection)
        dao.saveChatWorkspaces(sessionId, selection.primaryId, ChatWorkspaceSelection.encode(selection), qualifyLegacy = saved.workspaceSelectionJson == null)
    }

    /** Restores use the same overlap and availability checks as the normal access editor. */
    suspend fun validateSelection(selection: ChatWorkspaceSelection) {
        val available = projects.first()
        val fs = scopedFs(ChatWorkspaceSelection.encode(selection),
            requireNotNull(available.firstOrNull { it.id == selection.primaryId }) { "The primary workspace is unavailable." }, available) as ChatWorkspaceFs
        fs.bindings.forEachIndexed { index, binding ->
            fs.bindings.drop(index + 1).forEach { other ->
                require(!workspacePathsOverlap(binding.fs.workspacePaths(), other.fs.workspacePaths())) {
                    "${binding.name} and ${other.name} overlap. Choose separate workspace folders."
                }
            }
        }
    }

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

    suspend fun addSafProject(treeUri: Uri, activate: Boolean = true): ProjectEntity {
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
            return reactivate(picked, activate)
        }
        val name = treeUri.lastPathSegment
            ?.substringAfterLast(':')
            ?.ifBlank { null } ?: "Picked folder"
        val project = ProjectEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            kind = KIND_SAF,
            uri = treeUri.toString(),
            lastUsedAt = if (activate) System.currentTimeMillis() else 0,
        )
        dao.insertProject(project)
        if (activate) setActiveProject(project.id)
        return project
    }

    /** Always retain the picker grant. [fsFor] enables shell access when the
     * real path is writable, and falls back to SAF if broad access is revoked. */
    suspend fun addPickedFolder(treeUri: Uri, activate: Boolean = true): ProjectEntity = addSafProject(treeUri, activate)

    private fun writablePickedFolder(treeUri: Uri): File? =
        directFolderOrNull(SafPathResolver.resolve(treeUri))

    /**
     * Identity of a workspace folder for duplicate detection. A picked folder
     * that maps to a real path gets the SAME key as that path added via the
     * device browser, so "same folder twice through different doors" still
     * collides. Returns null only for the app workspace (never deduped).
     */
    private suspend fun reactivate(project: ProjectEntity, activate: Boolean = true): ProjectEntity {
        if (activate) setActiveProject(project.id)
        return project
    }

    suspend fun setActiveProject(id: String) {
        dao.touchProject(id, System.currentTimeMillis())
    }

    /** Renaming a label must not change the workspace or the default for new chats. */
    suspend fun renameProject(id: String, name: String) {
        val label = name.trim()
        require(label.isNotEmpty() && label.length <= 100) { "Use a project name between 1 and 100 characters." }
        requireNotNull(dao.project(id)) { "This project is no longer available." }
        dao.renameProject(id, label)
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
        releaseProjectResources(project)
    }

    suspend fun projectChats(projectId: String) = dao.projectSessions(projectId)

    /** App metadata only; the workspace directory and its contents are never removed. */
    suspend fun deleteProjectAndChats(projectId: String, expectedChatIds: Set<String>) {
        val project = requireNotNull(dao.project(projectId)) { "This project is no longer available." }
        dao.deleteProjectAndChats(projectId, expectedChatIds)
        if (project.kind == KIND_SAF && project.uri != null) releaseSafPermission(project.uri)
        releaseProjectResources(project)
    }

    private suspend fun releaseProjectResources(project: ProjectEntity) {
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

    suspend fun addSshProject(connection: SshConnection, root: String, activate: Boolean = true): ProjectEntity {
        val location = SshLocation(connection.id, root.trimEnd('/').ifEmpty { "/" })
        val uri = kotlinx.serialization.json.Json.encodeToString(SshLocation.serializer(), location)
        requireNotNull(sshConnections).save(connection)
        val existing = projects.first().firstOrNull { it.kind == KIND_SSH && it.uri == uri }
        if (existing != null) return reactivate(existing, activate)
        val label = root.substringAfterLast('/').ifBlank { connection.host }
        val project = ProjectEntity(UUID.randomUUID().toString(),
            label + if (connection.termux) " · Termux SSH" else " · SSH",
            KIND_SSH, uri, if (activate) System.currentTimeMillis() else 0)
        dao.insertProject(project)
        if (activate) setActiveProject(project.id)
        return project
    }

    /** Adds a project backed by a real filesystem path (requires Shizuku or All files access). */
    suspend fun addShellProject(path: String, activate: Boolean = true): ProjectEntity {
        val existing = findDuplicate(projects.first(), KIND_SHELL, path)
        if (existing != null) {
            return reactivate(existing, activate)
        }
        val project = ProjectEntity(
            id = java.util.UUID.randomUUID().toString(),
            name = path.substringAfterLast('/').ifBlank { path },
            kind = KIND_SHELL,
            uri = path,
            lastUsedAt = if (activate) System.currentTimeMillis() else 0,
        )
        dao.insertProject(project)
        if (activate) setActiveProject(project.id)
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

        /**
         * A mapped path alone does not grant permission to read or write it. Without the
         * storage grant Android can still report a shared folder as readable and writable
         * while hiding other apps' files, so the picker grant stays the way in.
         */
        internal fun directFolderOrNull(path: String?): File? = runCatching {
            path?.let { File(it) }?.takeIf {
                !com.androidharness.app.data.env.StorageGate.needsGrant(it.path) &&
                    it.isDirectory && it.canRead() && it.canWrite()
            }
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
