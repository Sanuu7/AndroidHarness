package com.androidharness.app.ui.files

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.data.db.SessionFileChangeEntity
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.workspace.FsNode
import com.androidharness.app.workspace.WorkspaceFs
import com.androidharness.app.workspace.normalizeRelPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val statusSuccess = LocalStatusColors.current.success
    val fs by container.workspace.current.collectAsStateWithLifecycle(initialValue = null)

    var currentPath by remember { mutableStateOf(".") }
    var refreshTick by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("") }
    var filterActive by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<FsNode>>(emptyList()) }

    var selecting by remember(fs, currentPath) { mutableStateOf(false) }
    var selectedPaths by remember(fs, currentPath) { mutableStateOf<Set<String>>(emptySet()) }
    var batchBusy by remember { mutableStateOf(false) }
    var batchAction by remember(fs) { mutableStateOf<String?>(null) }
    var batchTargets by remember(fs) { mutableStateOf<List<FsNode>>(emptyList()) }
    var batchErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    val visibleEntries = if (filter.isBlank()) entries else entries.filter { it.name.contains(filter, ignoreCase = true) }
    val selectedNodes = entries.filter { it.relPath in selectedPaths }
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
    BackHandler(enabled = selecting || batchBusy) { closeSelection() }

    // Session-change overlay; sentinel id keeps a live-but-empty flow flowing.
    val sessionForChanges = sessionId ?: "‹no-session›"
    val changes by container.sessions.fileChangesFor(sessionForChanges)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // Stacked per file: counts accumulate across every pass of this chat.
    val mergedChanges = remember(changes) { mergeSessionChanges(changes) }
    val activeChanges = mergedChanges.filter { !it.isDeleted }

    // ---- workspace switcher (same sheet the drawer and chat overflow use) ----
    val currentWorkspace by container.workspace.currentProject
        .collectAsStateWithLifecycle(initialValue = null)
    val allWorkspaces by container.workspace.projects
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    var showAddWorkspace by remember { mutableStateOf(false) }
    val safWorkspacePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { scope.launch { container.workspace.addPickedFolder(it) } } }

    // A workspace switch restarts the listing at its root.
    LaunchedEffect(fs) {
        currentPath = "."
        filter = ""
        filterActive = false
    }

    LaunchedEffect(fs, currentPath, refreshTick) {
        val f = fs ?: return@LaunchedEffect
        loading = true
        loadError = null
        entries = withContext(Dispatchers.IO) {
            runCatching {
                val dir = f.resolve(currentPath)
                if (!dir.isDirectory) emptyList() else dir.list()
            }.getOrElse { e ->
                loadError = e.message ?: "Could not list $currentPath"
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

    // ---- action targets ----
    var menuNode by remember { mutableStateOf<FsNode?>(null) }
    var renamingNode by remember { mutableStateOf<FsNode?>(null) }
    var deletingNode by remember { mutableStateOf<FsNode?>(null) }
    var pickCopyDest by remember { mutableStateOf<FsNode?>(null) }
    var pickMoveDest by remember { mutableStateOf<FsNode?>(null) }
    var creatingFile by remember { mutableStateOf(false) }
    var creatingFolder by remember { mutableStateOf(false) }
    var headerMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = scheme.surface,
        topBar = {
            AppHeader(
                title = if (selecting) "${selectedNodes.size} selected" else "Files",
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
                            Text(if (visibleEntries.isNotEmpty() && visibleEntries.all { it.relPath in selectedPaths }) "Deselect all" else "Select all")
                        }
                    } else {
                        IconButton(onClick = { showWorkspaceSheet = true }) {
                            Icon(
                                Icons.Outlined.Folder,
                                contentDescription = "Switch workspace",
                                tint = if (currentWorkspace != null) scheme.onSurfaceVariant else scheme.primary,
                            )
                        }
                        IconButton(onClick = { filterActive = !filterActive }) {
                            Icon(Icons.Outlined.Search, contentDescription = "Filter")
                        }
                        IconButton(onClick = { refreshTick++ }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                        }
                        Box {
                            IconButton(onClick = { headerMenuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = headerMenuOpen, onDismissRequest = { headerMenuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Select items") },
                                    onClick = { headerMenuOpen = false; selecting = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("New file") },
                                    leadingIcon = { Icon(Icons.Outlined.NoteAdd, null) },
                                    onClick = { headerMenuOpen = false; creatingFile = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("New folder") },
                                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                                    onClick = { headerMenuOpen = false; creatingFolder = true },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selecting) {
                Surface(color = scheme.surfaceContainerHigh, tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
                        if (batchBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
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
                                        batchErrors = listOf(error.message ?: "Share failed")
                                    } finally {
                                        batchBusy = false
                                    }
                                }
                            }) { Text("Share") }
                            TextButton(enabled = selectedNodes.isNotEmpty() && !batchBusy, onClick = {
                                batchTargets = selectedNodes.toList(); batchAction = "Copy"
                            }) { Text("Copy") }
                            TextButton(enabled = selectedNodes.isNotEmpty() && !batchBusy, onClick = {
                                batchTargets = selectedNodes.toList(); batchAction = "Move"
                            }) { Text("Move") }
                            TextButton(enabled = selectedNodes.isNotEmpty() && !batchBusy, onClick = {
                                batchTargets = selectedNodes.toList(); batchAction = "Delete"
                            }) { Text("Delete", color = if (selectedNodes.isNotEmpty() && !batchBusy) scheme.error else scheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            if (!selecting && sessionId != null && onOpenChanges != null && activeChanges.isNotEmpty()) {
                Surface(
                    color = scheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    onClick = onOpenChanges!!,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Difference,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            tint = scheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${activeChanges.size} files changed",
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

            if (filterActive) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    placeholder = { Text("Filter this folder", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

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
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (currentPath != ".") {
                        item(key = "__parent__") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !selecting && !batchBusy) { currentPath = parentOf(currentPath) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(19.dp),
                                    tint = scheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(14.dp))
                                Text("..", style = MaterialTheme.typography.bodyLarge)
                            }
                            HorizontalDivider(
                                color = scheme.outlineVariant.copy(alpha = 0.5f),
                                modifier = Modifier.padding(start = 49.dp),
                            )
                        }
                    }

                    val visible = visibleEntries

                    items(visible, key = { it.relPath }) { node ->
                        val nodeKey = normalizeRelPath(node.relPath)
                        val change = if (node.isFile) activeChanges.firstOrNull {
                            it.relPath == nodeKey ||
                                nodeKey.endsWith("/${it.relPath}") ||
                                it.relPath.endsWith("/$nodeKey")
                        } else null

                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(if (node.relPath in selectedPaths) scheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent)
                                .combinedClickable(
                                    enabled = !batchBusy,
                                    onClick = {
                                        if (selecting) {
                                            toggleSelection(node)
                                        } else if (node.isDirectory) {
                                            filter = ""
                                            filterActive = false
                                            currentPath = node.relPath
                                        } else {
                                            onOpenFile(node.relPath)
                                        }
                                    },
                                    onLongClick = { toggleSelection(node) },
                                ),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                            ) {
                                if (selecting) {
                                    Checkbox(
                                        checked = node.relPath in selectedPaths,
                                        enabled = !batchBusy,
                                        onCheckedChange = { toggleSelection(node) },
                                    )
                                }
                                Icon(
                                    iconFor(node),
                                    contentDescription = null,
                                    modifier = Modifier.size(19.dp),
                                    tint = tintFor(node, change, scheme, statusSuccess),
                                )
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        node.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (node.isFile && change != null) statusSuccess
                                        else Color.Unspecified,
                                    )
                                    Text(
                                        subtitleFor(node),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                                if (node.isFile && change != null) {
                                    DiffStatText(change.added, change.removed)
                                }
                                if (!selecting) {
                                    IconButton(onClick = { menuNode = node }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${node.name}")
                                    }
                                }
                                if (node.isDirectory && !selecting) {
                                    Spacer(Modifier.width(10.dp))
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    )
                                }
                            }
                            HorizontalDivider(
                                color = scheme.outlineVariant.copy(alpha = 0.5f),
                                modifier = Modifier.padding(start = 49.dp),
                            )
                        }
                    }

                    if (visible.isEmpty()) {
                        item {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 64.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    Icons.Outlined.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                                Text(
                                    if (filter.isNotBlank()) "Nothing matches \"${filter}\"."
                                    else "Empty directory.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- long-press sheet: fully expanded, divider-separated tap targets ----
    menuNode?.let { node ->
        ModalBottomSheet(onDismissRequest = { menuNode = null }) {
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
            val divider = @Composable { m: Modifier ->
                HorizontalDivider(m, color = scheme.outlineVariant.copy(alpha = 0.4f))
            }
            SheetAction(if (node.isDirectory) Icons.Outlined.Folder else Icons.Outlined.OpenInNew,
                if (node.isDirectory) "Open" else "Open in editor") {
                menuNode = null
                if (node.isDirectory) currentPath = node.relPath else onOpenFile(node.relPath)
            }
            divider(Modifier.padding(horizontal = 16.dp))
            SheetAction(Icons.Outlined.DriveFileRenameOutline, "Rename…") {
                menuNode = null
                renamingNode = node
            }
            divider(Modifier.padding(horizontal = 16.dp))
            SheetAction(Icons.Outlined.ContentCopy, "Copy to…") {
                menuNode = null
                pickCopyDest = node
            }
            divider(Modifier.padding(horizontal = 16.dp))
            SheetAction(Icons.Outlined.Folder, "Move to…") {
                menuNode = null
                pickMoveDest = node
            }
            divider(Modifier.padding(horizontal = 16.dp))
            SheetAction(Icons.Outlined.Delete, "Delete", tint = scheme.error) {
                menuNode = null
                deletingNode = node
            }
            if (node.isFile) {
                divider(Modifier.padding(horizontal = 16.dp))
                SheetAction(Icons.Outlined.Share, "Share…") {
                    menuNode = null
                    scope.launch {
                        runCatching { FileOps.share(context, node) }
                            .onFailure { toast(it.message ?: "Share failed") }
                    }
                }
                divider(Modifier.padding(horizontal = 16.dp))
                SheetAction(Icons.Outlined.OpenInNew, "Open externally…") {
                    menuNode = null
                    scope.launch {
                        runCatching { FileOps.openWith(context, node) }
                            .onFailure { toast(it.message ?: "No app can open this") }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    // ---- rename / create / delete / destination dialogs ----
    renamingNode?.let { node ->
        val parentRel = node.relPath.substringBeforeLast('/', "")
        NameDialog(
            title = "Rename “${node.name}”",
            initialName = node.name,
            takenNames = siblingNames(fs, parentRel) - setOf(node.name),
            confirmLabel = "Rename",
            onDismiss = { renamingNode = null },
            onConfirm = { newName ->
                perform("Renamed") {
                    withContext(Dispatchers.IO) { node.renameTo(newName) }
                    renamingNode = null
                }
            },
        )
    }

    if (creatingFolder) {
        NameDialog(
            title = "New folder",
            initialName = "",
            takenNames = siblingNames(fs, currentPath),
            confirmLabel = "Create",
            onDismiss = { creatingFolder = false },
            onConfirm = { name ->
                perform("Created $name") {
                    val dirNode = fs?.resolve(currentPath) ?: return@perform
                    dirNode.createDir(name)
                    creatingFolder = false
                }
            },
        )
    }
    if (creatingFile) {
        NameDialog(
            title = "New file",
            initialName = "",
            takenNames = siblingNames(fs, currentPath),
            confirmLabel = "Create",
            onDismiss = { creatingFile = false },
            onConfirm = { name ->
                perform("Created $name") {
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
            title = { Text("Delete ${node.name}?") },
            text = {
                Text(
                    if (node.isDirectory)
                        "The folder and everything inside it will be removed permanently."
                    else "This file will be removed permanently.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deletingNode = null
                    perform("Deleted ${node.name}") { node.delete() }
                }) { Text("Delete", color = scheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingNode = null }) { Text("Cancel") } },
        )
    }

    pickCopyDest?.let { src ->
        DestinationPickerDialog(
            fs = fs,
            startPath = currentPath,
            mustAvoidSubtreeOf = src.relPath,
            confirmLabel = "Copy here",
            onDismiss = { pickCopyDest = null },
            onPick = { destDir ->
                pickCopyDest = null
                perform("Copied ${src.name}") { FileOps.copy(src, destDir, src.name) }
            },
        )
    }
    pickMoveDest?.let { src ->
        DestinationPickerDialog(
            fs = fs,
            startPath = currentPath,
            mustAvoidSubtreeOf = src.relPath,
            confirmLabel = "Move here",
            onDismiss = { pickMoveDest = null },
            onPick = { destDir ->
                pickMoveDest = null
                perform("Moved ${src.name}") { FileOps.move(src, destDir, src.name) }
            },
        )
    }

    if (batchAction == "Delete") {
        AlertDialog(
            onDismissRequest = { batchAction = null },
            title = { Text("Delete ${batchTargets.size} items?") },
            text = {
                Text(
                    batchTargets.take(8).joinToString("\n") { it.name } +
                        (if (batchTargets.size > 8) "\n…and ${batchTargets.size - 8} more" else "") +
                        "\n\nThese items and any folder contents will be permanently deleted.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val nodes = batchTargets
                    batchAction = null
                    runBatch("Deleted", nodes) { node -> check(node.delete()) { "Could not delete this item." } }
                }) { Text("Delete", color = scheme.error) }
            },
            dismissButton = { TextButton(onClick = { batchAction = null }) { Text("Cancel") } },
        )
    }
    if (batchAction == "Copy" || batchAction == "Move") {
        val action = batchAction!!
        DestinationPickerDialog(
            fs = fs,
            startPath = currentPath,
            mustAvoidSubtrees = batchTargets.filter { it.isDirectory }.map { it.relPath },
            confirmLabel = "$action ${batchTargets.size} here",
            onDismiss = { batchAction = null },
            onPick = { destination ->
                val nodes = batchTargets
                batchAction = null
                runBatch(if (action == "Copy") "Copied" else "Moved", nodes) { node ->
                    if (action == "Copy") FileOps.copy(node, destination, node.name, preserveAll = true)
                    else FileOps.move(node, destination, node.name)
                }
            },
        )
    }
    if (batchErrors.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { batchErrors = emptyList() },
            title = { Text("Some items could not be processed") },
            text = {
                Text(
                    batchErrors.joinToString("\n\n"),
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { batchErrors = emptyList() }) { Text("OK") } },
        )
    }

    // ---- workspace switcher sheet + add/delete flows ----
    if (showWorkspaceSheet) {
        com.androidharness.app.ui.chat.components.WorkspaceSwitcherSheet(
            projects = allWorkspaces,
            currentProjectId = currentWorkspace?.id,
            describe = { container.workspace.describe(it) },
            onSelect = { id ->
                scope.launch { container.workspace.setActiveProject(id) }
                showWorkspaceSheet = false
            },
            onAdd = { showAddWorkspace = true },
            onDismiss = { showWorkspaceSheet = false },
            onDelete = { project ->
                scope.launch { container.workspace.deleteProject(project) }
            },
        )
    }
    if (showAddWorkspace) {
        com.androidharness.app.ui.common.AddWorkspaceDialog(
            container = container,
            onDismiss = { showAddWorkspace = false },
            onPickSaf = {
                showAddWorkspace = false
                safWorkspacePicker.launch(null)
            },
        )
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
                fontFamily = FontFamily.Monospace,
                color = colors.success,
            )
        }
        if (removed > 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                "−$removed",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = scheme.error,
            )
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, title: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp), tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

private fun iconFor(node: FsNode): ImageVector = when {
    node.isDirectory -> Icons.Outlined.Folder
    else -> when (node.name.substringAfterLast('.', "").lowercase()) {
        "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx", "c", "cpp", "h", "gradle" -> Icons.Outlined.Code
        "json", "xml", "yaml", "yml", "toml", "ini" -> Icons.Outlined.DataObject
        "png", "jpg", "jpeg", "gif", "webp", "svg" -> Icons.Outlined.Image
        "mp3", "wav", "ogg", "m4a", "flac" -> Icons.Outlined.MusicNote
        "mp4", "mov", "webm", "mkv", "avi" -> Icons.Outlined.Movie
        "zip", "jar", "tar", "gz", "7z", "rar" -> Icons.Outlined.FolderZip
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }
}

private fun subtitleFor(node: FsNode): String = when {
    node.isDirectory -> try { "${node.list().size} items" } catch (_: Exception) { "" }
    else -> formatFileSize(node.length)
}

internal fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

private fun tintFor(
    node: FsNode,
    change: SessionFileChangeEntity?,
    scheme: androidx.compose.material3.ColorScheme,
    success: Color,
): Color = when {
    change == null || node.isDirectory -> scheme.onSurfaceVariant
    change.isNew -> success
    else -> success.copy(alpha = 0.85f)
}

private fun parentOf(path: String): String {
    val segments = path.split('/').filter { it.isNotBlank() }
    return if (segments.size <= 1) "." else segments.dropLast(1).joinToString("/")
}

/** Names present next to [childRelPath] ("src" ⇒ children of root, "" ⇒ root). */
private fun siblingNames(fs: WorkspaceFs?, childRelPath: String): Set<String> =
    runCatching {
        val target = childRelPath.ifBlank { "." }
        fs?.resolve(target)?.list()?.mapTo(HashSet()) { it.name }
    }.getOrNull().orEmpty()

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
                        "Something named \"$trimmed\" already exists here.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else if (illegal && trimmed != initialName) {
                    Text(
                        if (trimmed.isEmpty()) "Enter a name."
                        else "That name isn't valid.",
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
