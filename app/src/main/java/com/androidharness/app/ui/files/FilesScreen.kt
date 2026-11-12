package com.androidharness.app.ui.files

import com.androidharness.app.R
import com.androidharness.app.ui.common.AppLinearProgressIndicator
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import com.androidharness.app.ui.common.ExpressiveDropdownMenu as DropdownMenu
import com.androidharness.app.ui.common.ExpressiveDropdownMenuItem as DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.androidharness.app.ui.theme.HarnessGlassModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.FolderOff
import com.androidharness.app.ui.common.ExpressiveEmptyState
import com.androidharness.app.ui.common.ExpressiveIconBadge
import com.androidharness.app.ui.common.ExpressiveIconButton
import com.androidharness.app.ui.common.ExpressiveListRow
import com.androidharness.app.ui.common.ExpressiveRefreshButton
import com.androidharness.app.ui.settings.ExpressiveSearchField
import com.androidharness.app.ui.settings.SettingsExpressiveTheme
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.data.db.SessionFileChangeEntity
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.theme.HarnessMono
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.workspace.FsNode
import com.androidharness.app.workspace.normalizeRelPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.androidharness.app.ui.common.Checkbox
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

/**
 * Workspace file manager: browse any directory, open files in the editor, and
 * manage them, rename, copy, move, delete, share to other apps, open
 * externally, create files/folders, filter.
 *
 * When [sessionId] is set the list also shows per-file "+N −M" badges for what
 * this chat did to each file (live via Room invalidation), plus a summary
 * strip linking to the GitHub-style [ChangesScreen].
 */
@OptIn(
    ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)
@Composable
fun FilesScreen(
    container: AppContainer,
    sessionId: String? = null,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenChanges: (() -> Unit)? = null,
    projectId: String? = null,
    onOpenFileAtLine: ((String, Int?) -> Unit)? = null,
    onOpenTerminal: (() -> Unit)? = null,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    com.androidharness.app.ui.common.ShizukuWorkspaceWarningEffect(container.shizuku)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val statusSuccess = LocalStatusColors.current.success
    val fs by remember(sessionId, projectId) { container.workspace.forTools(sessionId, projectId) }.collectAsStateWithLifecycle(initialValue = null)
    val currentWorkspace by remember(sessionId, projectId) { container.workspace.projectForTools(sessionId, projectId) }
        .collectAsStateWithLifecycle(initialValue = null)

    // Include the project location: picked folders can have identical display names.
    val workspaceKey = currentWorkspace?.let { project ->
        fs?.let { "${project.id}:${project.kind}:${project.uri}:${it.displayPath}" }
    }
    var lastWorkspaceKey by androidx.compose.runtime.saveable.rememberSaveable(sessionId, projectId) {
        mutableStateOf<String?>(null)
    }
    var currentPath by androidx.compose.runtime.saveable.rememberSaveable(sessionId, projectId) { mutableStateOf(".") }
    var refreshTick by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("") }
    var filterActive by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<FileRow>>(emptyList()) }

    var selecting by remember(sessionId, projectId, workspaceKey, currentPath) { mutableStateOf(false) }
    var selectedPaths by remember(sessionId, projectId, workspaceKey, currentPath) { mutableStateOf<Set<String>>(emptySet()) }
    var batchBusy by remember { mutableStateOf(false) }
    var batchAction by remember(sessionId, projectId, workspaceKey) { mutableStateOf<String?>(null) }
    var batchTargets by remember(sessionId, projectId, workspaceKey) { mutableStateOf<List<FsNode>>(emptyList()) }
    var batchErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    val visibleEntries = if (filter.isBlank()) entries else entries.filter { it.name.contains(filter, ignoreCase = true) }
    val selectedNodes = entries.filter { it.relPath in selectedPaths }.map { it.node }
    fun toggleSelection(node: FsNode) {
        if (batchBusy) return
        selecting = true
        selectedPaths = if (node.relPath in selectedPaths) selectedPaths - node.relPath else selectedPaths + node.relPath
    }
    fun closeSelection() {
        if (!batchBusy) {
            selecting = false
            selectedPaths = emptySet()
        }
    }
    BackHandler(enabled = com.androidharness.app.ui.LocalWorkspacePaneActive.current && (selecting || batchBusy)) { closeSelection() }

    // Session-change overlay; sentinel id keeps a live-but-empty flow flowing.
    val sessionForChanges = sessionId ?: "‹no-session›"
    val changes by container.sessions.fileChangesFor(sessionForChanges)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // Stacked per file: counts accumulate across every pass of this chat.
    val mergedChanges = remember(changes) { mergeSessionChanges(changes) }
    val activeChanges = mergedChanges.filter { !it.isDeleted }

    // ---- workspace switcher (same sheet the drawer and chat overflow use) ----
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    fun selectWorkspace(id: String) {
        scope.launch {
            try {
                if (sessionId == null) container.workspace.setActiveProject(id)
                else container.runManager.assignWorkspace(sessionId, id)
            } catch (e: Exception) { loadError = e.message }
        }
    }
    var githubProject by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Session updates can recreate fs without changing its workspace. Keep navigation
    // through those refreshes and the initial null emission after activity recreation.
    LaunchedEffect(workspaceKey) {
        val key = workspaceKey ?: return@LaunchedEffect
        if (lastWorkspaceKey != null && lastWorkspaceKey != key) {
            currentPath = "."
            filter = ""
            filterActive = false
        }
        lastWorkspaceKey = key
    }

    LaunchedEffect(fs, currentPath, refreshTick) {
        val f = fs ?: return@LaunchedEffect
        loading = true
        loadError = null
        entries = withContext(Dispatchers.IO) {
            runCatching {
                val dir = f.resolve(currentPath)
                if (!dir.isDirectory) emptyList() else dir.list().map { it.toRow() }
            }.getOrElse { e ->
                loadError = e.message ?: uiContext.getString(R.string.ui_could_not_list, (currentPath).toString())
                emptyList()
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }
        selectedPaths = selectedPaths.intersect(entries.map { it.relPath }.toSet())
        loading = false
    }

    fun toast(msg: String) =
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()

    fun perform(description: String, block: suspend () -> Unit) {
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { block() }.exceptionOrNull()?.let { e ->
                    e.message ?: "${e.javaClass.simpleName} during $description"
                }
            }
            toast(failure ?: "$description ✓".replace(" ✓", " done"))
            refreshTick++
        }
    }

    fun runBatch(action: String, nodes: List<FsNode>, operation: suspend (FsNode) -> Unit) {
        batchBusy = true
        scope.launch {
            try {
                val result = FileOps.batch(nodes, operation)
                selectedPaths = selectedPaths - result.completed
                batchErrors = result.failures.map { (path, reason) -> "$path: $reason" }
                if (result.failures.isEmpty()) {
                    selecting = false
                    selectedPaths = emptySet()
                }
                toast("$action ${result.completed.size} of ${nodes.size} items")
                refreshTick++
            } finally {
                batchBusy = false
            }
        }
    }

    var importing by remember { mutableStateOf(false) }
    fun importPicked(uris: List<android.net.Uri>, tree: Boolean) {
        val f = fs ?: return
        if (uris.isEmpty() || importing) return
        val target = currentPath
        importing = true
        scope.launch {
            try {
                val dir = withContext(Dispatchers.IO) { f.resolve(target) }
                val result = FileOps.importDocuments(context, uris, dir, tree)
                batchErrors = result.failures.map { (name, reason) -> "$name: $reason" }
                toast(uiContext.getString(R.string.files_import_done, result.completed.size.toString(), uris.size.toString()))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                batchErrors = listOf(error.message ?: uiContext.getString(R.string.files_import_failed))
            } finally {
                importing = false
                refreshTick++
            }
        }
    }
    val importFilesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importPicked(uris, tree = false)
    }
    val importFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { importPicked(listOf(it), tree = true) }
    }

    // ---- action targets ----
    var menuNode by remember { mutableStateOf<FsNode?>(null) }
    var renamingNode by remember { mutableStateOf<FsNode?>(null) }
    var deletingNode by remember { mutableStateOf<FsNode?>(null) }
    var pickCopyDest by remember { mutableStateOf<FsNode?>(null) }
    var pickMoveDest by remember { mutableStateOf<FsNode?>(null) }
    var creatingFile by remember { mutableStateOf(false) }
    var creatingFolder by remember { mutableStateOf(false) }
    var headerMenuOpen by remember { mutableStateOf(false) }
    var workspaceTools by remember(workspaceKey) { mutableStateOf<WorkspaceToolMode?>(null) }
    workspaceTools?.let { mode ->
        WorkspaceToolsSheet(container, sessionId, projectId, mode, onDismiss = { workspaceTools = null },
            onOpenFile = { path, line -> workspaceTools = null; onOpenFileAtLine?.invoke(path, line) ?: onOpenFile(path) },
            commands = listOf(
                WorkspaceCommand(uiContext.getString(R.string.ui_refresh_files), uiContext.getString(R.string.ui_reload_the_current_folder), Icons.Outlined.Refresh) { workspaceTools = null; refreshTick++ },
                WorkspaceCommand(uiContext.getString(R.string.ui_filter_current_folder), uiContext.getString(R.string.ui_filter_names_in_this_folder), Icons.Outlined.Search) { workspaceTools = null; filterActive = true },
            ) + listOfNotNull(
                currentWorkspace?.let { project -> WorkspaceCommand(uiContext.getString(R.string.ui_github_commit_push), uiContext.getString(R.string.ui_review_and_publish_workspace_changes), Icons.Outlined.Code) { workspaceTools = null; githubProject = project.id to project.name } },
                onOpenChanges?.let { action -> WorkspaceCommand(uiContext.getString(R.string.ui_show_changes), uiContext.getString(R.string.ui_review_changes_made_in_this_chat), Icons.Outlined.Difference) { workspaceTools = null; action() } },
                onOpenTerminal?.let { action -> WorkspaceCommand(uiContext.getString(R.string.ui_open_terminal), uiContext.getString(R.string.ui_terminal_for_this_workspace), Icons.Outlined.Code) { workspaceTools = null; action() } },
            ))
    }

    SettingsExpressiveTheme {
    Scaffold(
        modifier = Modifier.onPreviewKeyEvent { event ->
            if(event.isCtrlPressed && !event.isAltPressed) {
                val mode = when { event.key == Key.P && event.isShiftPressed -> WorkspaceToolMode.COMMANDS; event.key == Key.P -> WorkspaceToolMode.FILES; event.key == Key.F && event.isShiftPressed -> WorkspaceToolMode.SEARCH; else -> null }
                if(mode != null) { if(event.type == KeyEventType.KeyDown) workspaceTools = mode; true } else false
            } else false
        },
        containerColor = scheme.surface,
        topBar = {
            AppHeader(
                title = if (selecting) "${selectedNodes.size} selected"
                    else currentWorkspace?.name?.takeIf { projectId != null } ?: uiContext.getString(R.string.ui_files),
                subtitle = if (currentPath == ".") fs?.displayPath.orEmpty()
                else "${fs?.displayPath.orEmpty().substringAfterLast('/')}/$currentPath",
                onBack = { if (selecting) closeSelection() else onBack() },
                actions = {
                    if (selecting) {
                        TextButton(
                            enabled = !batchBusy && visibleEntries.isNotEmpty(),
                            onClick = {
                                val paths = visibleEntries.map { it.relPath }.toSet()
                                selectedPaths = if (selectedPaths.containsAll(paths)) selectedPaths - paths else selectedPaths + paths
                            },
                        ) {
                            Text(if (visibleEntries.isNotEmpty() && visibleEntries.all { it.relPath in selectedPaths }) uiContext.getString(R.string.ui_deselect_all) else uiContext.getString(R.string.ui_select_all))
                        }
                    } else {
                        if (projectId == null || sessionId != null) ExpressiveIconButton(onClick = { showWorkspaceSheet = true }) {
                            Icon(
                                Icons.Outlined.Folder,
                                contentDescription = uiContext.getString(R.string.ui_switch_workspace),
                                tint = if (currentWorkspace != null) scheme.onSurfaceVariant else scheme.primary,
                            )
                        }
                        ExpressiveIconButton(onClick = { workspaceTools = WorkspaceToolMode.SEARCH }) {
                            Icon(Icons.Outlined.Search, contentDescription = uiContext.getString(R.string.ui_search_project))
                        }
                        ExpressiveRefreshButton(uiContext.getString(R.string.ui_refresh), onClick = { refreshTick++ })
                        Box {
                            ExpressiveIconButton(onClick = { headerMenuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = uiContext.getString(R.string.ui_more))
                            }
                            DropdownMenu(expanded = headerMenuOpen, onDismissRequest = { headerMenuOpen = false }) {
                                DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_quick_open)) }, onClick = { headerMenuOpen = false; workspaceTools = WorkspaceToolMode.FILES })
                                DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_command_palette)) }, onClick = { headerMenuOpen = false; workspaceTools = WorkspaceToolMode.COMMANDS })
                                DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_filter_current_folder)) }, onClick = { headerMenuOpen = false; filterActive = !filterActive })
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(uiContext.getString(R.string.ui_github_commit_push)) },
                                    enabled = currentWorkspace != null,
                                    onClick = { headerMenuOpen = false; currentWorkspace?.let { githubProject = it.id to it.name } },
                                )
                                DropdownMenuItem(
                                    text = { Text(uiContext.getString(R.string.ui_select_items)) },
                                    onClick = { headerMenuOpen = false; selecting = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(uiContext.getString(R.string.ui_new_file)) },
                                    leadingIcon = { Icon(Icons.Outlined.NoteAdd, null) },
                                    onClick = { headerMenuOpen = false; creatingFile = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(uiContext.getString(R.string.ui_new_folder)) },
                                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                                    onClick = { headerMenuOpen = false; creatingFolder = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(uiContext.getString(R.string.files_import_files)) },
                                    leadingIcon = { Icon(Icons.Outlined.FileUpload, null) },
                                    enabled = fs != null && !importing,
                                    onClick = { headerMenuOpen = false; importFilesPicker.launch(arrayOf("*/*")) },
                                )
                                DropdownMenuItem(
                                    text = { Text(uiContext.getString(R.string.files_import_folder)) },
                                    leadingIcon = { Icon(Icons.Outlined.DriveFolderUpload, null) },
                                    enabled = fs != null && !importing,
                                    onClick = { headerMenuOpen = false; importFolderPicker.launch(null) },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selecting) {
                Surface(color = scheme.surfaceContainerHigh, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
                        if (batchBusy) AppLinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(enabled = selectedNodes.isNotEmpty() && !batchBusy, onClick = {
                                val nodes = selectedNodes.toList()
                                batchBusy = true
                                scope.launch {
                                    try {
                                        FileOps.shareMany(context, nodes)
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        batchErrors = listOf(error.message ?: uiContext.getString(R.string.ui_share_failed))
                                    } finally {
                                        batchBusy = false
                                    }
                                }
                            }) { Text(uiContext.getString(R.string.ui_share)) }
                            TextButton(enabled = selectedNodes.isNotEmpty() && !batchBusy, onClick = {
                                batchTargets = selectedNodes.toList(); batchAction = "Copy"
                            }) { Text(uiContext.getString(R.string.ui_copy)) }
                            TextButton(enabled = selectedNodes.isNotEmpty() && !batchBusy, onClick = {
                                batchTargets = selectedNodes.toList(); batchAction = "Move"
                            }) { Text(uiContext.getString(R.string.ui_move)) }
                            TextButton(enabled = selectedNodes.isNotEmpty() && !batchBusy, onClick = {
                                batchTargets = selectedNodes.toList(); batchAction = "Delete"
                            }) { Text(uiContext.getString(R.string.settings_action_delete), color = if (selectedNodes.isNotEmpty() && !batchBusy) scheme.error else scheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            if (!selecting && sessionId != null && onOpenChanges != null && activeChanges.isNotEmpty()) {
                Surface(
                    color = scheme.secondaryContainer,
                    contentColor = scheme.onSecondaryContainer,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(24.dp)),
                    onClick = onOpenChanges!!,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Difference,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            tint = scheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            uiContext.getString(R.string.ui_files_changed_758, (activeChanges.size).toString()),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        DiffStatText(
                            added = activeChanges.sumOf { it.added },
                            removed = activeChanges.sumOf { it.removed },
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            }

            AnimatedVisibility(filterActive,
                enter = expandVertically(expressiveSpatialSpec()) + fadeIn(expressiveEffectsSpec()),
                exit = shrinkVertically(expressiveSpatialSpec()) + fadeOut(expressiveEffectsSpec())) {
                ExpressiveSearchField(filter, { filter = it }, uiContext.getString(R.string.wp_files_filter),
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }

            if (importing) AppLinearProgressIndicator(Modifier.fillMaxWidth())

            loadError?.let { err ->
                Text(
                    err,
                    color = scheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (loading && entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    @OptIn(ExperimentalMaterial3ExpressiveApi::class) LoadingIndicator(Modifier.size(56.dp))
                }
            } else {
                val visible = visibleEntries
                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 24.dp)) {
                    if (currentPath != ".") {
                        item(key = "\u0000parent") {
                            val parent = parentOf(currentPath)
                            ExpressiveListRow(0, 1,
                                uiContext.getString(R.string.wp_files_parent, if (parent == ".") uiContext.getString(R.string.wp_files_root) else parent.substringAfterLast('/')),
                                dense = true, enabled = !selecting && !batchBusy, color = scheme.surfaceContainerHigh,
                                onClick = { currentPath = parent },
                                modifier = Modifier.padding(bottom = 6.dp)
                                    .animateItem(fadeInSpec = expressiveEffectsSpec(), placementSpec = expressiveSpatialSpec(), fadeOutSpec = expressiveEffectsSpec()),
                                leading = { ExpressiveIconBadge(Icons.AutoMirrored.Outlined.DriveFileMove, size = 36.dp,
                                    container = scheme.tertiaryContainer, tint = scheme.onTertiaryContainer) })
                        }
                    }

                    itemsIndexed(visible, key = { _, it -> it.relPath }) { index, row ->
                        val node = row.node
                        val nodeKey = normalizeRelPath(row.relPath)
                        val change = if (row.isFile) activeChanges.firstOrNull {
                            it.relPath == nodeKey ||
                                nodeKey.endsWith("/${it.relPath}") ||
                                it.relPath.endsWith("/$nodeKey")
                        } else null
                        val picked = row.relPath in selectedPaths
                        ExpressiveListRow(index, visible.size, row.name, subtitle = subtitleFor(row), dense = true,
                            selected = picked, enabled = !batchBusy,
                            titleColor = if (row.isFile && change != null) statusSuccess else Color.Unspecified,
                            modifier = Modifier.animateItem(fadeInSpec = expressiveEffectsSpec(), placementSpec = expressiveSpatialSpec(), fadeOutSpec = expressiveEffectsSpec()),
                            onClick = {
                                if (selecting) {
                                    toggleSelection(node)
                                } else if (row.isDirectory) {
                                    filter = ""
                                    filterActive = false
                                    currentPath = row.relPath
                                } else {
                                    onOpenFile(row.relPath)
                                }
                            },
                            onLongClick = { toggleSelection(node) },
                            leading = {
                                if (selecting) Checkbox(checked = picked, enabled = !batchBusy, onCheckedChange = { toggleSelection(node) })
                                if (row.isDirectory) ExpressiveIconBadge(iconFor(row), size = 36.dp, selected = picked)
                                else ExpressiveIconBadge(iconFor(row), size = 36.dp, selected = picked,
                                    container = scheme.surfaceContainerHighest, tint = tintFor(row, change, scheme, statusSuccess))
                            },
                            trailing = {
                                if (row.isFile && change != null) DiffStatText(change.added, change.removed)
                                if (!selecting) {
                                    ExpressiveIconButton(onClick = { menuNode = node }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = uiContext.getString(R.string.settings_provider_actions_cd, (row.name).toString()),
                                            tint = scheme.onSurfaceVariant)
                                    }
                                } else Spacer(Modifier.width(8.dp))
                            })
                    }

                    if (visible.isEmpty()) {
                        item(key = "\u0000empty") {
                            ExpressiveEmptyState(Icons.Outlined.FolderOff,
                                if (filter.isNotBlank()) uiContext.getString(R.string.ui_nothing_matches, (filter).toString())
                                else uiContext.getString(R.string.ui_empty_directory), seed = 6)
                        }
                    }
                }
            }
        }
    }

    // ---- long-press sheet: fully expanded, divider-separated tap targets ----
    menuNode?.let { node ->
        @OptIn(ExperimentalMaterial3Api::class) ModalBottomSheet(onDismissRequest = { menuNode = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Text(
                node.name,
                style = MaterialTheme.typography.titleMediumEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                node.relPath,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
            val divider = @Composable { _: Modifier -> Spacer(Modifier.height(2.dp)) }
            SheetAction(if (node.isDirectory) Icons.Outlined.Folder else Icons.Outlined.OpenInNew,
                if (node.isDirectory) uiContext.getString(R.string.ui_open) else uiContext.getString(R.string.ui_open_in_editor)) {
                menuNode = null
                if (node.isDirectory) currentPath = node.relPath else onOpenFile(node.relPath)
            }
            divider(Modifier.padding(horizontal = 16.dp))
            SheetAction(Icons.Outlined.DriveFileRenameOutline, uiContext.getString(R.string.ui_rename_764)) {
                menuNode = null
                renamingNode = node
            }
            divider(Modifier.padding(horizontal = 16.dp))
            SheetAction(Icons.Outlined.ContentCopy, uiContext.getString(R.string.ui_copy_to)) {
                menuNode = null
                pickCopyDest = node
            }
            divider(Modifier.padding(horizontal = 16.dp))
            SheetAction(Icons.Outlined.Folder, uiContext.getString(R.string.ui_move_to)) {
                menuNode = null
                pickMoveDest = node
            }
            divider(Modifier.padding(horizontal = 16.dp))
            SheetAction(Icons.Outlined.Delete, uiContext.getString(R.string.settings_action_delete), tint = scheme.error) {
                menuNode = null
                deletingNode = node
            }
            if (node.isFile) {
                divider(Modifier.padding(horizontal = 16.dp))
                SheetAction(Icons.Outlined.Share, uiContext.getString(R.string.ui_share_767)) {
                    menuNode = null
                    scope.launch {
                        runCatching { FileOps.share(context, node) }
                            .onFailure { toast(it.message ?: uiContext.getString(R.string.ui_share_failed)) }
                    }
                }
                divider(Modifier.padding(horizontal = 16.dp))
                SheetAction(Icons.Outlined.OpenInNew, uiContext.getString(R.string.ui_open_externally)) {
                    menuNode = null
                    scope.launch {
                        runCatching { FileOps.openWith(context, node) }
                            .onFailure { toast(it.message ?: uiContext.getString(R.string.ui_no_app_can_open_this)) }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    // ---- rename / create / delete / destination dialogs ----
    renamingNode?.let { node ->
        NameDialog(
            title = uiContext.getString(R.string.ui_rename_770, (node.name).toString()),
            initialName = node.name,
            takenNames = entries.mapTo(HashSet()) { it.name } - setOf(node.name),
            confirmLabel = uiContext.getString(R.string.ui_rename),
            onDismiss = { renamingNode = null },
            onConfirm = { newName ->
                perform(uiContext.getString(R.string.ui_renamed)) {
                    withContext(Dispatchers.IO) { node.renameTo(newName) }
                    renamingNode = null
                }
            },
        )
    }

    if (creatingFolder) {
        NameDialog(
            title = uiContext.getString(R.string.ui_new_folder),
            initialName = "",
            takenNames = entries.mapTo(HashSet()) { it.name },
            confirmLabel = uiContext.getString(R.string.ui_create),
            onDismiss = { creatingFolder = false },
            onConfirm = { name ->
                perform(uiContext.getString(R.string.ui_created, (name).toString())) {
                    val dirNode = fs?.resolve(currentPath) ?: return@perform
                    dirNode.createDir(name)
                    creatingFolder = false
                }
            },
        )
    }
    if (creatingFile) {
        NameDialog(
            title = uiContext.getString(R.string.ui_new_file),
            initialName = "",
            takenNames = entries.mapTo(HashSet()) { it.name },
            confirmLabel = uiContext.getString(R.string.ui_create),
            onDismiss = { creatingFile = false },
            onConfirm = { name ->
                perform(uiContext.getString(R.string.ui_created, (name).toString())) {
                    val dirNode = fs?.resolve(currentPath) ?: return@perform
                    dirNode.createFile(name)
                    creatingFile = false
                }
            },
        )
    }

    deletingNode?.let { node ->
        AlertDialog(
            onDismissRequest = { deletingNode = null },
            title = { Text(uiContext.getString(R.string.settings_bulk_delete_one_title, (node.name).toString())) },
            text = {
                Text(
                    if (node.isDirectory)
                        uiContext.getString(R.string.ui_the_folder_and_everything_inside_it_will)
                    else uiContext.getString(R.string.ui_this_file_will_be_removed_permanently),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deletingNode = null
                    perform(uiContext.getString(R.string.ui_deleted, (node.name).toString())) { node.delete() }
                }) { Text(uiContext.getString(R.string.settings_action_delete), color = scheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingNode = null }) { Text(uiContext.getString(R.string.settings_action_cancel)) } },
        )
    }

    pickCopyDest?.let { src ->
        DestinationPickerDialog(
            fs = fs,
            startPath = currentPath,
            mustAvoidSubtreeOf = src.relPath,
            confirmLabel = uiContext.getString(R.string.ui_copy_here),
            onDismiss = { pickCopyDest = null },
            onPick = { destDir ->
                pickCopyDest = null
                perform(uiContext.getString(R.string.ui_copied, (src.name).toString())) { FileOps.copy(src, destDir, src.name) }
            },
        )
    }
    pickMoveDest?.let { src ->
        DestinationPickerDialog(
            fs = fs,
            startPath = currentPath,
            mustAvoidSubtreeOf = src.relPath,
            confirmLabel = uiContext.getString(R.string.ui_move_here),
            onDismiss = { pickMoveDest = null },
            onPick = { destDir ->
                pickMoveDest = null
                perform(uiContext.getString(R.string.ui_moved, (src.name).toString())) { FileOps.move(src, destDir, src.name) }
            },
        )
    }

    if (batchAction == "Delete") {
        AlertDialog(
            onDismissRequest = { batchAction = null },
            title = { Text(uiContext.getString(R.string.ui_delete_items, (batchTargets.size).toString())) },
            text = {
                Text(
                    batchTargets.take(8).joinToString("\n") { it.name } +
                        (if (batchTargets.size > 8) "\n…and ${batchTargets.size - 8} more" else "") +
                        uiContext.getString(R.string.ui_these_items_and_any_folder_contents_will),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val nodes = batchTargets
                    batchAction = null
                    runBatch(uiContext.getString(R.string.ui_deleted_784), nodes) { node -> check(node.delete()) { uiContext.getString(R.string.ui_could_not_delete_this_item) } }
                }) { Text(uiContext.getString(R.string.settings_action_delete), color = scheme.error) }
            },
            dismissButton = { TextButton(onClick = { batchAction = null }) { Text(uiContext.getString(R.string.settings_action_cancel)) } },
        )
    }
    if (batchAction == "Copy" || batchAction == "Move") {
        val action = batchAction!!
        DestinationPickerDialog(
            fs = fs,
            startPath = currentPath,
            mustAvoidSubtrees = batchTargets.filter { it.isDirectory }.map { it.relPath },
            confirmLabel = uiContext.getString(R.string.ui_batch_destination_confirm,
                uiContext.getString(if (action == "Copy") R.string.ui_copy else R.string.ui_move), batchTargets.size.toString()),
            onDismiss = { batchAction = null },
            onPick = { destination ->
                val nodes = batchTargets
                batchAction = null
                runBatch(if (action == "Copy") uiContext.getString(R.string.settings_shizuku_copied) else uiContext.getString(R.string.ui_moved_786), nodes) { node ->
                    if (action == "Copy") FileOps.copy(node, destination, node.name, preserveAll = true)
                    else FileOps.move(node, destination, node.name)
                }
            },
        )
    }
    if (batchErrors.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { batchErrors = emptyList() },
            title = { Text(uiContext.getString(R.string.ui_some_items_could_not_be_processed)) },
            text = {
                Text(
                    batchErrors.joinToString("\n\n"),
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { batchErrors = emptyList() }) { Text(uiContext.getString(R.string.ui_ok)) } },
        )
    }

    // ---- workspace switcher sheet + add/delete flows ----
    githubProject?.let { (id, name) ->
        com.androidharness.app.ui.github.GitHubPublishDialog(container, id, name,
            onDismiss = { githubProject = null; refreshTick++ })
    }
    if (showWorkspaceSheet) {
        com.androidharness.app.ui.common.ShizukuWorkspaceWarningEffect(container.shizuku)
        com.androidharness.app.ui.chat.components.WorkspaceSwitcher(container, sessionId = sessionId,
            currentWorkspaceId = currentWorkspace?.id, onSelect = ::selectWorkspace,
            onDismiss = { showWorkspaceSheet = false }, onError = { toast(it) }, chatContext = sessionId != null)
    }
    }
}

// ---------------------------------------------------------------------------
// Diff badges & row helpers

/** GitHub-style "+N −M" mono badge: additions success-green, deletions red. */
@Composable
fun DiffStatText(added: Long, removed: Long, modifier: Modifier = Modifier) {
    if (added == 0L && removed == 0L) return
    val colors = LocalStatusColors.current
    val scheme = MaterialTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (added > 0) {
            Text(
                "+$added",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = HarnessMono,
                color = colors.success,
            )
        }
        if (removed > 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                "−$removed",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = HarnessMono,
                color = scheme.error,
            )
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, title: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val danger = tint == scheme.error
    ExpressiveListRow(0, 1, title, dense = true, onClick = onClick, titleColor = tint, modifier = Modifier.padding(horizontal = 16.dp),
        leading = { ExpressiveIconBadge(icon, size = 36.dp, container = if (danger) scheme.errorContainer else scheme.surfaceContainerHighest,
            tint = if (danger) scheme.onErrorContainer else scheme.onSurfaceVariant) })
}

/** One listing entry, read once on the IO thread so drawing never touches the filesystem. */
internal class FileRow(
    val node: FsNode,
    val relPath: String,
    val name: String,
    val isDirectory: Boolean,
    val isFile: Boolean,
    val size: Long,
    val childCount: Int?,
)

private fun FsNode.toRow(): FileRow {
    val directory = isDirectory
    // A real folder answers its child count with one read. SAF and SSH would need a full listing per row.
    val children = if (directory) (this as? com.androidharness.app.workspace.FileFsNode)?.file?.list()?.size else null
    return FileRow(this, relPath, name, directory, !directory && isFile, if (directory) 0L else length, children)
}

private fun iconFor(row: FileRow): ImageVector = when {
    row.isDirectory -> Icons.Outlined.Folder
    else -> when (row.name.substringAfterLast('.', "").lowercase()) {
        "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx", "c", "cpp", "h", "gradle" -> Icons.Outlined.Code
        "json", "xml", "yaml", "yml", "toml", "ini" -> Icons.Outlined.DataObject
        "png", "jpg", "jpeg", "gif", "webp", "svg" -> Icons.Outlined.Image
        "mp3", "wav", "ogg", "m4a", "flac" -> Icons.Outlined.MusicNote
        "mp4", "mov", "webm", "mkv", "avi" -> Icons.Outlined.Movie
        "zip", "jar", "tar", "gz", "7z", "rar" -> Icons.Outlined.FolderZip
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }
}

private fun subtitleFor(row: FileRow): String = when {
    row.node is com.androidharness.app.workspace.SshFs.RemoteNode && row.isDirectory -> "Remote folder"
    row.isDirectory -> row.childCount?.let { "$it items" } ?: "Folder"
    else -> formatFileSize(row.size)
}

internal fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

private fun tintFor(
    row: FileRow,
    change: SessionFileChangeEntity?,
    scheme: androidx.compose.material3.ColorScheme,
    success: Color,
): Color = when {
    change == null || row.isDirectory -> scheme.onSurfaceVariant
    change.isNew -> success
    else -> success.copy(alpha = 0.85f)
}

private fun parentOf(path: String): String {
    val segments = path.split('/').filter { it.isNotBlank() }
    return if (segments.size <= 1) "." else segments.dropLast(1).joinToString("/")
}

// ---------------------------------------------------------------------------
// Shared name-entry dialog (rename + new file/folder)

/**
 * Text entry validating against path-illegal characters and [takenNames].
 * Blank names never enable confirm; duplicates show an inline warning and
 * block submission.
 */
@Composable
fun NameDialog(
    title: String,
    initialName: String,
    takenNames: Set<String>,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var value by remember(title) { mutableStateOf(initialName) }
    val trimmed = value.trim()
    val isDuplicate = trimmed.isNotEmpty() &&
        takenNames.any { it.equals(trimmed, ignoreCase = false) } && trimmed != initialName
    val illegal = trimmed.isEmpty() || trimmed.contains('/') || trimmed.contains('\\') ||
        trimmed == "." || trimmed == ".."
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    isError = isDuplicate || illegal,
                )
                if (isDuplicate) {
                    Text(
                        uiContext.getString(R.string.ui_something_named_already_exists_here, (trimmed).toString()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else if (illegal && trimmed != initialName) {
                    Text(
                        if (trimmed.isEmpty()) uiContext.getString(R.string.ui_enter_a_name)
                        else uiContext.getString(R.string.ui_that_name_isn_t_valid),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !illegal && !isDuplicate,
                onClick = { onDismiss(); onConfirm(trimmed) },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiContext.getString(R.string.settings_action_cancel)) } },
    )
}
