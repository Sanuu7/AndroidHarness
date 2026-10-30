package com.androidharness.app.ui.chat.components

import com.androidharness.app.R
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.androidharness.app.browser.BrowserController
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.LocalPortProbe
import com.androidharness.app.core.WebResourceExtractor
import com.androidharness.app.ui.common.ThinLinearProgress
import com.androidharness.app.ui.theme.HarnessMono
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.theme.fastEffectsSpec
import com.androidharness.app.ui.theme.fastSpatialSpec
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

enum class WebPreviewStage {
    SOURCE_HUB,
    WEB_VIEW,
}

data class WebConsoleLog(
    val level: ConsoleMessage.MessageLevel,
    val message: String,
    val source: String?,
    val lineNumber: Int,
    val timestamp: Long = System.currentTimeMillis(),
)

/** Eruda mobile devtools script CDN loader with toggle support */
private const val ERUDA_INJECT_JS = """
(function () {
    if (window.eruda) {
        var el = document.querySelector('.eruda-container');
        if (el && el.style.display !== 'none' && window.eruda._isInit) {
            window.eruda.hide();
        } else if (window.eruda._isInit) {
            window.eruda.show();
        } else {
            window.eruda.init({
                tool: ['console', 'elements', 'network', 'resources', 'info', 'snippets']
            });
            window.eruda.show();
        }
        return;
    }
    var script = document.createElement('script');
    script.src = "https://cdn.jsdelivr.net/npm/eruda";
    script.onload = function () {
        if (window.eruda) {
            window.eruda.init({
                tool: ['console', 'elements', 'network', 'resources', 'info', 'snippets']
            });
            window.eruda.show();
        }
    };
    script.onerror = function() {
        console.error('Failed to load Eruda DevTools from CDN. Check network connection.');
    };
    document.head.appendChild(script);
})();
"""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebPreviewSheet(
    initialTarget: String? = null,
    startAtSources: Boolean = false,
    workspace: WorkspaceFs? = null,
    messages: List<ChatMessage> = emptyList(),
    additionalLinks: List<String> = emptyList(),
    browserController: com.androidharness.app.browser.BrowserController? = null,
    onSendPrompt: ((String) -> Unit)? = null,
    agentActivity: com.androidharness.app.browser.BrowserToolActivity? = null,
    agentRunning: Boolean = false,
    onStopAgent: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    val effectiveInitial = if (startAtSources) null else initialTarget ?: browserController?.getActiveUrl()
    var stage by remember {
        mutableStateOf(if (!effectiveInitial.isNullOrBlank()) WebPreviewStage.WEB_VIEW else WebPreviewStage.SOURCE_HUB)
    }
    var currentTarget by remember { mutableStateOf(effectiveInitial ?: "http://localhost:3000") }

    var isScanningSources by remember { mutableStateOf(true) }
    var activePorts by remember { mutableStateOf<List<Int>>(emptyList()) }
    var workspaceHtmlFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    val chatLinks = remember(messages, additionalLinks) {
        (messages.flatMap { WebResourceExtractor.extractUrls(it.text) } + additionalLinks).distinct()
    }

    var isFullscreen by remember { mutableStateOf(false) }
    var showConsole by remember { mutableStateOf(false) }
    val consoleLogs = remember { mutableStateListOf<WebConsoleLog>() }

    // Scan for all sources in background
    LaunchedEffect(workspace, messages) {
        isScanningSources = true
        activePorts = LocalPortProbe.probe()
        workspaceHtmlFiles = WebResourceExtractor.findWorkspaceHtmlFiles(workspace)
        isScanningSources = false
    }

    val openTarget: (String) -> Unit = { target ->
        val norm = if (target.endsWith(".html", ignoreCase = true) || target.endsWith(".htm", ignoreCase = true)) {
            target
        } else {
            LocalPortProbe.normalizeLocalUrl(target)
        }
        currentTarget = norm
        stage = WebPreviewStage.WEB_VIEW
    }

    val handleFixBug: (String) -> Unit = { bugDescription ->
        scope.launch {
            sheetState.hide()
            onDismiss()
            onSendPrompt?.invoke(bugDescription)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surface,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(if (isFullscreen) 1.0f else 0.88f)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            AnimatedContent(
                targetState = stage,
                transitionSpec = { fadeIn(fastEffectsSpec()) togetherWith fadeOut(fastEffectsSpec()) },
                label = "preview stage switch",
            ) { currentStage ->
                when (currentStage) {
                    WebPreviewStage.SOURCE_HUB -> {
                        SourceHubView(
                            isScanning = isScanningSources,
                            workspaceHtmlFiles = workspaceHtmlFiles,
                            chatLinks = chatLinks,
                            activePorts = activePorts,
                            onSelectTarget = openTarget,
                            onRescanPorts = {
                                scope.launch {
                                    activePorts = LocalPortProbe.probe()
                                }
                            },
                            onClose = {
                                scope.launch {
                                    sheetState.hide()
                                    onDismiss()
                                }
                            },
                        )
                    }
                    WebPreviewStage.WEB_VIEW -> {
                        WebPageView(
                            target = currentTarget,
                            workspace = workspace,
                            isFullscreen = isFullscreen,
                            showConsole = showConsole,
                            consoleLogs = consoleLogs,
                            browserController = browserController,
                            agentActivity = agentActivity,
                            agentRunning = agentRunning,
                            onStopAgent = onStopAgent,
                            onToggleFullscreen = { isFullscreen = !isFullscreen },
                            onToggleConsole = { showConsole = !showConsole },
                            onHideConsole = { showConsole = false },
                            onBackToHub = { stage = WebPreviewStage.SOURCE_HUB },
                            onFixBug = handleFixBug,
                            onClose = {
                                scope.launch {
                                    sheetState.hide()
                                    onDismiss()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Stage 1: Native Source Picker Hub (Smart Filtering)
// ---------------------------------------------------------------------------

@Composable
private fun SourceHubView(
    isScanning: Boolean,
    workspaceHtmlFiles: List<String>,
    chatLinks: List<String>,
    activePorts: List<Int>,
    onSelectTarget: (String) -> Unit,
    onRescanPorts: () -> Unit,
    onClose: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var customUrlInput by remember { mutableStateOf("") }

    val hasAnySources = workspaceHtmlFiles.isNotEmpty() || chatLinks.isNotEmpty() || activePorts.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surfaceContainerLow)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(
                Icons.Outlined.Language,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    uiContext.getString(R.string.ui_web_html_preview),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (isScanning) uiContext.getString(R.string.ui_scanning_workspace_live_ports) else uiContext.getString(R.string.ui_select_a_source_to_inspect),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Close, contentDescription = uiContext.getString(R.string.settings_action_close), tint = scheme.onSurfaceVariant)
            }
        }

        if (isScanning) {
            ThinLinearProgress(modifier = Modifier.fillMaxWidth())
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Workspace HTML files (only if present)
            if (workspaceHtmlFiles.isNotEmpty()) {
                CollapsibleSection(
                    title = uiContext.getString(R.string.ui_workspace_files_260),
                    count = workspaceHtmlFiles.size,
                    icon = Icons.Outlined.Folder,
                    defaultExpanded = true,
                ) {
                    workspaceHtmlFiles.forEach { file ->
                        SourceRowItem(
                            icon = Icons.Outlined.Description,
                            title = file.substringAfterLast('/'),
                            subtitle = file.substringBeforeLast('/', uiContext.getString(R.string.ui_workspace_root)),
                            badgeText = "HTML",
                            onClick = { onSelectTarget(file) },
                        )
                    }
                }
            }

            // 2. Chat Links (only if present in conversation)
            if (chatLinks.isNotEmpty()) {
                CollapsibleSection(
                    title = uiContext.getString(R.string.ui_links_in_chat),
                    count = chatLinks.size,
                    icon = Icons.Outlined.Link,
                    defaultExpanded = true,
                ) {
                    chatLinks.forEach { link ->
                        SourceRowItem(
                            icon = Icons.Outlined.Language,
                            title = link,
                            subtitle = if (LocalPortProbe.isLocalhostUrl(link)) uiContext.getString(R.string.ui_local_server) else uiContext.getString(R.string.ui_web_url),
                            badgeText = "OPEN",
                            onClick = { onSelectTarget(link) },
                        )
                    }
                }
            }

            // 3. Localhost servers (only show LIVE servers)
            if (activePorts.isNotEmpty()) {
                CollapsibleSection(
                    title = uiContext.getString(R.string.ui_live_local_servers),
                    count = activePorts.size,
                    icon = Icons.Outlined.Sensors,
                    defaultExpanded = true,
                    headerAction = {
                        IconButton(onClick = onRescanPorts, modifier = Modifier.size(40.dp)) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = uiContext.getString(R.string.ui_rescan_ports),
                                tint = scheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                ) {
                    activePorts.forEach { port ->
                        SourceRowItem(
                            icon = Icons.Outlined.Sensors,
                            title = "http://localhost:$port",
                            subtitle = uiContext.getString(R.string.ui_active_listening_server_on_port, (port).toString()),
                            badgeText = "LIVE",
                            isLive = true,
                            onClick = { onSelectTarget("http://localhost:$port") },
                        )
                    }
                }
            }

            // Clean Empty State when no automatic sources found
            if (!hasAnySources && !isScanning) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = scheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(20.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Sensors,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            uiContext.getString(R.string.ui_no_active_web_sources_detected),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            uiContext.getString(R.string.ui_start_a_dev_server_in_terminal_e),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }

            // Custom Direct URL Card
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = scheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        uiContext.getString(R.string.ui_enter_custom_address_or_port),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = customUrlInput,
                            onValueChange = { customUrlInput = it },
                            placeholder = { Text("https://...") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = HarnessMono),
                            shape = RoundedCornerShape(8.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    if (customUrlInput.isNotBlank()) onSelectTarget(customUrlInput.trim())
                                },
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (customUrlInput.isNotBlank()) scheme.primary else scheme.surfaceContainerHigh,
                            modifier = Modifier.clickable(enabled = customUrlInput.isNotBlank()) {
                                onSelectTarget(customUrlInput.trim())
                            },
                        ) {
                            Text(
                                uiContext.getString(R.string.ui_open),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (customUrlInput.isNotBlank()) scheme.onPrimary else scheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleSection(
    title: String,
    count: Int,
    icon: ImageVector,
    defaultExpanded: Boolean = true,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(defaultExpanded) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = fastEffectsSpec(),
        label = "section chevron",
    )

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (count > 0) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = scheme.surfaceContainerHighest,
                        modifier = Modifier.padding(end = 6.dp),
                    ) {
                        Text(
                            count.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = HarnessMono,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                headerAction?.invoke()
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(chevronRotation),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(fastSpatialSpec()) + fadeIn(fastEffectsSpec()),
                exit = shrinkVertically(fastSpatialSpec()) + fadeOut(fastEffectsSpec()),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.3f))
                    content()
                }
            }
        }
    }
}

@Composable
private fun SourceRowItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badgeText: String,
    isLive: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val statusColors = LocalStatusColors.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (isLive) statusColors.success else scheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = HarnessMono,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (isLive) statusColors.success.copy(alpha = 0.15f) else scheme.surfaceContainerHigh,
            border = BorderStroke(
                1.dp,
                if (isLive) statusColors.success.copy(alpha = 0.5f) else scheme.outlineVariant.copy(alpha = 0.4f),
            ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                if (isLive) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .background(statusColors.success, CircleShape),
                    )
                    Spacer(Modifier.width(3.dp))
                }
                Text(
                    badgeText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    fontFamily = HarnessMono,
                    color = if (isLive) statusColors.success else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Stage 2: Optimized WebView Execution + Mobile DevTools
// ---------------------------------------------------------------------------

@Composable
private fun WebPageView(
    target: String,
    workspace: WorkspaceFs?,
    isFullscreen: Boolean,
    showConsole: Boolean,
    consoleLogs: MutableList<WebConsoleLog>,
    browserController: com.androidharness.app.browser.BrowserController? = null,
    onToggleFullscreen: () -> Unit,
    onToggleConsole: () -> Unit,
    onHideConsole: () -> Unit,
    onBackToHub: () -> Unit,
    onFixBug: (String) -> Unit,
    onClose: () -> Unit,
    agentActivity: com.androidharness.app.browser.BrowserToolActivity? = null,
    agentRunning: Boolean = false,
    onStopAgent: (() -> Unit)? = null,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val statusColors = LocalStatusColors.current
    val context = LocalContext.current

    var currentUrl by remember { mutableStateOf(target) }
    var isLoading by remember { mutableStateOf(true) }
    var progress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf(target.substringAfterLast('/')) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val selectionScope = rememberCoroutineScope()
    var pickingElement by remember { mutableStateOf(false) }
    var selectedElement by remember { mutableStateOf<com.androidharness.app.browser.PreviewSelection?>(null) }
    var elementRequest by remember { mutableStateOf("") }
    var selectionScreenshot by remember { mutableStateOf<String?>(null) }
    var capturingSelection by remember { mutableStateOf(false) }

    selectedElement?.let { selection ->
        AlertDialog(
            onDismissRequest = { selectedElement = null },
            title = { Text(uiContext.getString(R.string.ui_fix_selected_element)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(selection.selector, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium, fontFamily = HarnessMono)
                    OutlinedTextField(value = elementRequest, onValueChange = { elementRequest = it },
                        label = { Text(uiContext.getString(R.string.ui_what_should_change)) }, minLines = 2, maxLines = 5,
                        modifier = Modifier.fillMaxWidth())
                    Text(if (capturingSelection) uiContext.getString(R.string.ui_capturing_page_context) else uiContext.getString(R.string.ui_includes_element_details_styles_and_available_page),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(enabled = elementRequest.isNotBlank() && !capturingSelection, onClick = {
                    val errors = consoleLogs.filter { it.level == ConsoleMessage.MessageLevel.ERROR }
                        .take(5).joinToString("\n") { "${it.message} (${it.source}:${it.lineNumber})" }
                    selectedElement = null
                    onFixBug(selection.prompt(currentUrl, elementRequest, errors, selectionScreenshot))
                }) { Text(uiContext.getString(R.string.ui_send_to_agent)) }
            },
            dismissButton = { TextButton(onClick = { selectedElement = null }) { Text(uiContext.getString(R.string.settings_action_cancel)) } },
        )
    }

    // Agent control state: banner strip + expandable activity trail
    var showAgentTrack by remember { mutableStateOf(false) }
    var browserToolbarMenu by remember { mutableStateOf(false) }
    val controlActiveFlow = remember(browserController) {
        browserController?.isAgentControlling ?: kotlinx.coroutines.flow.MutableStateFlow(false)
    }
    val trackedAgentControlling by controlActiveFlow.collectAsState()
    val agentControlling = agentActivity?.let { it.status == "In progress" || it.status == "Waiting" } ?: trackedAgentControlling
    val trackFlow = remember(browserController) {
        browserController?.actionTrack ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    }
    val agentTracks by trackFlow.collectAsState()

    val isWorkspaceHtml: (String) -> Boolean = { t ->
        com.androidharness.app.browser.BrowserToolActivity.workspacePath(t, workspace?.shellRoot?.absolutePath) != null
    }

    val load: (String) -> Unit = { t ->
        isLoading = true
        errorMessage = null
        val view = webViewRef
        if (view != null) {
            if (isWorkspaceHtml(t)) {
                val relative = requireNotNull(com.androidharness.app.browser.BrowserToolActivity.workspacePath(t, workspace?.shellRoot?.absolutePath))
                val node = runCatching { workspace?.resolve(relative) }.getOrNull()
                if (node != null && node.exists && node.isFile) {
                    pageTitle = relative.substringAfterLast('/')
                    // Load through the shared workspace origin instead of
                    // loadDataWithBaseURL: loadData creates a data: history
                    // entry, which pollutes back/forward for the agent.
                    workspace?.let { browserController?.prepareWorkspacePreview(relative, it) }
                    currentUrl = BrowserController.localFileUrl(relative) + BrowserController.splitLocalTarget(t).second
                    view.post {
                        view.loadUrl(currentUrl)
                    }
                } else if (workspace == null) {
                    // Workspace still resolving from container, keep loading state
                    isLoading = true
                } else {
                    isLoading = false
                    errorMessage = "File '$t' not found in active workspace."
                }
            } else {
                val norm = LocalPortProbe.normalizeLocalUrl(t)
                currentUrl = norm
                view.post {
                    view.loadUrl(norm)
                }
            }
        }
    }

    // Auto-reload when workspace finishes initializing from container flow
    LaunchedEffect(workspace, target) {
        if (workspace != null && isWorkspaceHtml(target) && webViewRef != null) {
            load(target)
        }
    }

    val reload: () -> Unit = {
        load(currentUrl)
    }

    val openDevTools: () -> Unit = {
        webViewRef?.evaluateJavascript(ERUDA_INJECT_JS, null)
    }

    Column(Modifier.fillMaxSize()) {
        // Browser toolbar. This used to be ten 38dp buttons in a single row,
        // which is both under the 48dp touch minimum and wider than a phone
        // (roughly 430dp of controls), so the trailing buttons sat off-screen.
        // Navigation and the error-badged console stay visible; the rest live
        // in the overflow.
        Surface(
            color = scheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBackToHub) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = uiContext.getString(R.string.ui_all_sources), tint = scheme.onSurface)
                    }

                    IconButton(
                        onClick = { webViewRef?.let { if (it.canGoBack()) it.goBack() } },
                        enabled = canGoBack,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = uiContext.getString(R.string.ui_history_back),
                            tint = if (canGoBack) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.3f),
                        )
                    }

                    IconButton(
                        onClick = { webViewRef?.let { if (it.canGoForward()) it.goForward() } },
                        enabled = canGoForward,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = uiContext.getString(R.string.ui_history_forward),
                            tint = if (canGoForward) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.3f),
                        )
                    }

                    IconButton(onClick = reload) {
                        Icon(Icons.Default.Refresh, contentDescription = uiContext.getString(R.string.ui_reload), tint = scheme.onSurfaceVariant)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // JS Console Drawer Toggle, kept visible because the error
                    // dot is a signal the user needs to see without opening a menu.
                    IconButton(onClick = onToggleConsole) {
                        Box {
                            Icon(
                                Icons.Outlined.BugReport,
                                contentDescription = uiContext.getString(R.string.ui_console),
                                tint = if (consoleLogs.any { it.level == ConsoleMessage.MessageLevel.ERROR }) {
                                    scheme.error
                                } else if (showConsole) {
                                    scheme.primary
                                } else {
                                    scheme.onSurfaceVariant
                                },
                            )
                            if (consoleLogs.any { it.level == ConsoleMessage.MessageLevel.ERROR }) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(scheme.error, CircleShape)
                                        .align(Alignment.TopEnd),
                                )
                            }
                        }
                    }

                    Box {
                        IconButton(onClick = { browserToolbarMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = uiContext.getString(R.string.ui_more_browser_actions), tint = scheme.onSurfaceVariant)
                        }
                        DropdownMenu(
                            expanded = browserToolbarMenu,
                            onDismissRequest = { browserToolbarMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (pickingElement) uiContext.getString(R.string.ui_cancel_element_selection) else uiContext.getString(R.string.ui_select_element_to_fix)) },
                                leadingIcon = { Icon(Icons.Filled.AutoFixHigh, contentDescription = null) },
                                enabled = !isLoading && errorMessage == null && !agentControlling,
                                onClick = {
                                    browserToolbarMenu = false
                                    pickingElement = !pickingElement
                                    onHideConsole()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(uiContext.getString(R.string.ui_devtools)) },
                                leadingIcon = { Icon(Icons.Outlined.DeveloperMode, contentDescription = null, tint = scheme.primary) },
                                onClick = {
                                    browserToolbarMenu = false
                                    openDevTools()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(uiContext.getString(R.string.ui_open_in_external_browser)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                                enabled = !isWorkspaceHtml(currentUrl),
                                onClick = {
                                    browserToolbarMenu = false
                                    runCatching {
                                        if (!isWorkspaceHtml(currentUrl)) {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                                            context.startActivity(intent)
                                        }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (isFullscreen) uiContext.getString(R.string.ui_exit_fullscreen) else uiContext.getString(R.string.ui_fullscreen)) },
                                leadingIcon = {
                                    Icon(
                                        if (isFullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    browserToolbarMenu = false
                                    onToggleFullscreen()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(uiContext.getString(R.string.settings_action_close)) },
                                leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                                onClick = {
                                    browserToolbarMenu = false
                                    onClose()
                                },
                            )
                        }
                    }
                }
            }
        }

        if (pickingElement) {
            Surface(color = scheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                    Text(uiContext.getString(R.string.ui_tap_the_element_you_want_to_change), modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onPrimaryContainer)
                    IconButton(onClick = { pickingElement = false }) {
                        Icon(Icons.Default.Close, contentDescription = uiContext.getString(R.string.ui_cancel_element_selection))
                    }
                }
            }
        }
        if (isLoading) {
            ThinLinearProgress(modifier = Modifier.fillMaxWidth())
        }

        // "Agent is controlling the browser" strip; tap to expand the action trail
        AnimatedVisibility(
            visible = agentActivity != null || agentControlling,
            enter = expandVertically(fastEffectsSpec()) + fadeIn(fastEffectsSpec()),
            exit = shrinkVertically(fastEffectsSpec()) + fadeOut(fastEffectsSpec()),
        ) {
            Surface(
                color = scheme.primaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        showAgentTrack = !showAgentTrack
                        if (showAgentTrack) onHideConsole()
                    },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    if (agentActivity?.status in listOf(uiContext.getString(R.string.ui_in_progress), uiContext.getString(R.string.ui_waiting)) || agentActivity == null) {
                        AgentPulsingDot(tint = scheme.onPrimaryContainer)
                    } else {
                        Icon(Icons.Outlined.Language, null, Modifier.size(16.dp), tint = scheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        agentActivity?.let { "${it.status} · ${it.action}" } ?: uiContext.getString(R.string.ui_agent_is_controlling_the_browser),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onPrimaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (onStopAgent != null) TextButton(onClick = onStopAgent, enabled = agentRunning,
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = scheme.error)) { Text(uiContext.getString(R.string.ui_stop_agent)) }
                    Icon(
                        if (showAgentTrack) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                        contentDescription = if (showAgentTrack) uiContext.getString(R.string.ui_hide_agent_activity) else uiContext.getString(R.string.ui_show_agent_activity),
                        tint = scheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // Hardware-Accelerated Smooth WebView with Touch Disallow
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )

                        // Smooth fluid scrolling: prevent BottomSheet gesture interception during touch
                        setOnTouchListener { v, event ->
                            if (pickingElement) {
                                if (event.action == MotionEvent.ACTION_UP) {
                                    val density = ctx.resources.displayMetrics.density
                                    @Suppress("DEPRECATION")
                                    val zoom = scale / density
                                    val x = (event.x / density / zoom).toDouble().coerceAtLeast(0.0)
                                    val y = (event.y / density / zoom).toDouble().coerceAtLeast(0.0)
                                    evaluateJavascript(com.androidharness.app.browser.PreviewSelection.script(x, y)) { raw ->
                                        val selection = com.androidharness.app.browser.PreviewSelection.parse(raw)
                                        if (selection != null) {
                                            elementRequest = ""
                                            selectionScreenshot = null
                                            selectedElement = selection
                                            capturingSelection = true
                                            selectionScope.launch {
                                                try {
                                                    selectionScreenshot = kotlinx.coroutines.withTimeoutOrNull(5000) {
                                                        browserController?.screenshot(workspace)?.relPath
                                                    }
                                                } finally { capturingSelection = false }
                                            }
                                        }
                                    }
                                    pickingElement = false
                                }
                                return@setOnTouchListener true
                            }
                            when (event.action) {
                                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                                    v.parent?.requestDisallowInterceptTouchEvent(true)
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    v.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                            }
                            false
                        }

                        // Hardware Acceleration & High-FPS Smooth Scrolling
                        setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        isVerticalScrollBarEnabled = true
                        isHorizontalScrollBarEnabled = false
                        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS

                        @SuppressLint("SetJavaScriptEnabled")
                        settings.apply {
                            javaScriptEnabled = true
                            setSupportMultipleWindows(true)
                            javaScriptCanOpenWindowsAutomatically = true
                            domStorageEnabled = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            allowFileAccess = false
                            allowContentAccess = false
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: android.webkit.RenderProcessGoneDetail?,
                            ): Boolean {
                                browserController?.unbindActiveWebView(view ?: return true)
                                isLoading = false
                                errorMessage = uiContext.getString(R.string.ui_browser_render_process_terminated_infinite_loop_or)
                                return true
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                // The agent waits on these for the navigation it
                                // triggered; this client is not the controller's,
                                // so without forwarding, an action driven against
                                // the visible WebView has no load signal at all.
                                browserController?.notePageStarted()
                                url?.let {
                                    if (!it.startsWith("data:") && !it.startsWith("https://localhost/")) {
                                        currentUrl = it
                                    }
                                }
                                canGoBack = canGoBack()
                                canGoForward = canGoForward()
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                browserController?.notePageFinished(url)
                                canGoBack = canGoBack()
                                canGoForward = canGoForward()
                                pageTitle = view?.title ?: currentUrl.substringAfterLast('/')
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    isLoading = false
                                    errorMessage = error?.description?.toString() ?: uiContext.getString(R.string.ui_failed_to_connect_to, (target).toString())
                                    browserController?.notePageError(errorMessage)
                                }
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                // Workspace pages served by the agent browser's asset
                                // loader (https://harness.workspace/ws/...) must resolve
                                // on this WebView too, so agent and user see one site.
                                browserController?.interceptWorkspaceRequest(request?.url)?.let { return it }
                                val uri = request?.url ?: return null
                                if ((uri.host == "localhost" || uri.host == "127.0.0.1") && workspace != null && isWorkspaceHtml(currentUrl)) {
                                    val path = uri.path?.trimStart('/') ?: return null
                                    if (path.isEmpty()) return null
                                    val baseDir = if ('/' in currentUrl) currentUrl.substringBeforeLast('/') else ""
                                    val fullPath = if (baseDir.isNotEmpty()) "$baseDir/$path" else path
                                    val node = runCatching { workspace.resolve(fullPath) }.getOrNull()
                                        ?: runCatching { workspace.resolve(path) }.getOrNull()
                                    if (node != null && node.exists && node.isFile) {
                                        val mime = when (node.name.substringAfterLast('.', "").lowercase()) {
                                            "css" -> "text/css"
                                            "js", "mjs" -> "application/javascript"
                                            "json" -> "application/json"
                                            "png" -> "image/png"
                                            "jpg", "jpeg" -> "image/jpeg"
                                            "svg" -> "image/svg+xml"
                                            "ico" -> "image/x-icon"
                                            "woff" -> "font/woff"
                                            "woff2" -> "font/woff2"
                                            "ttf" -> "font/ttf"
                                            else -> "text/plain"
                                        }
                                        val stream = node.openInputStream()
                                        if (stream != null) {
                                            return WebResourceResponse(mime, "UTF-8", stream)
                                        }
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }

                        webChromeClient = object : com.androidharness.app.browser.AgentWebChromeClient(browserController) {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                                if (newProgress == 100) isLoading = false
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                if (!title.isNullOrBlank()) pageTitle = title
                            }

                            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                if (consoleMessage != null) {
                                    browserController?.recordConsoleMessage(consoleMessage, currentUrl)
                                    consoleLogs.add(
                                        0,
                                        WebConsoleLog(
                                            level = consoleMessage.messageLevel(),
                                            message = consoleMessage.message(),
                                            source = consoleMessage.sourceId(),
                                            lineNumber = consoleMessage.lineNumber(),
                                        ),
                                    )
                                    if (consoleLogs.size > 200) consoleLogs.removeAt(consoleLogs.lastIndex)
                                }
                                return true
                            }
                        }

                        webViewRef = this
                        browserController?.bindActiveWebView(this)
                        load(target)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            DisposableEffect(webViewRef, browserController) {
                onDispose {
                    webViewRef?.let { wv ->
                        browserController?.unbindActiveWebView(wv)
                    }
                }
            }

            // Dedicated Loading Screen (smooth overlay)
            if (isLoading && errorMessage == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(scheme.surface.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.height(14.dp))
                        Text(
                            uiContext.getString(R.string.ui_loading_preview),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            target,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            fontFamily = HarnessMono,
                        )
                    }
                }
            }

            // Error Overlay with Fix Bug action
            errorMessage?.let { err ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(scheme.surface.copy(alpha = 0.96f))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Sensors,
                            contentDescription = null,
                            tint = scheme.error,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            uiContext.getString(R.string.ui_cannot_load_page),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            err,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = scheme.surfaceContainerHigh,
                                modifier = Modifier.clickable { onBackToHub() },
                            ) {
                                Text(
                                    uiContext.getString(R.string.ui_pick_source),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = scheme.errorContainer,
                                modifier = Modifier.clickable {
                                    onFixBug("Fix web server error when loading $currentUrl: $err")
                                },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    Icon(Icons.Filled.AutoFixHigh, contentDescription = null, tint = scheme.onErrorContainer, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(uiContext.getString(R.string.ui_fix_with_agent), color = scheme.onErrorContainer, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = scheme.primary,
                                modifier = Modifier.clickable { reload() },
                            ) {
                                Text(
                                    uiContext.getString(R.string.ui_retry),
                                    color = scheme.onPrimary,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Console Drawer Overlay with "Fix this bug" action button
            androidx.compose.animation.AnimatedVisibility(
                visible = showConsole,
                enter = expandVertically(fastEffectsSpec()) + fadeIn(fastEffectsSpec()),
                exit = shrinkVertically(fastEffectsSpec()) + fadeOut(fastEffectsSpec()),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val errorLogs = consoleLogs.filter { it.level == ConsoleMessage.MessageLevel.ERROR }

                Surface(
                    color = scheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(scheme.surfaceContainerLow)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                uiContext.getString(R.string.ui_js_console, (consoleLogs.size).toString()),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = HarnessMono,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )

                            // Quick "Fix this bug" if any JavaScript errors exist
                            if (errorLogs.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = scheme.errorContainer,
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .clickable {
                                            val errs = errorLogs.take(3).joinToString("\n") {
                                                "${it.message} (${it.source ?: "inline"}:${it.lineNumber})"
                                            }
                                            onFixBug("Fix JavaScript errors on $currentUrl:\n$errs")
                                        },
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.AutoFixHigh,
                                            contentDescription = null,
                                            tint = scheme.onErrorContainer,
                                            modifier = Modifier.size(13.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            uiContext.getString(R.string.ui_fix_bug_in_chat),
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                            color = scheme.onErrorContainer,
                                        )
                                    }
                                }
                            }

                            IconButton(onClick = { consoleLogs.clear() }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Outlined.Delete, contentDescription = uiContext.getString(R.string.ui_clear_logs), modifier = Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(2.dp))
                            IconButton(onClick = onToggleConsole, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.Close, contentDescription = uiContext.getString(R.string.ui_close_console), modifier = Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))

                        if (consoleLogs.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    uiContext.getString(R.string.ui_no_console_messages_logged),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    fontFamily = HarnessMono,
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                items(consoleLogs) { log ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                when (log.level) {
                                                    ConsoleMessage.MessageLevel.ERROR -> scheme.error.copy(alpha = 0.12f)
                                                    ConsoleMessage.MessageLevel.WARNING -> statusColors.warning.copy(alpha = 0.12f)
                                                    else -> Color.Transparent
                                                },
                                            )
                                            .padding(horizontal = 6.dp, vertical = 3.dp),
                                    ) {
                                        Text(
                                            when (log.level) {
                                                ConsoleMessage.MessageLevel.ERROR -> "[ERR]"
                                                ConsoleMessage.MessageLevel.WARNING -> "[WARN]"
                                                ConsoleMessage.MessageLevel.DEBUG -> "[DBG]"
                                                else -> "[LOG]"
                                            },
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            fontFamily = HarnessMono,
                                            fontWeight = FontWeight.Bold,
                                            color = when (log.level) {
                                                ConsoleMessage.MessageLevel.ERROR -> scheme.error
                                                ConsoleMessage.MessageLevel.WARNING -> statusColors.warning
                                                else -> scheme.primary
                                            },
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                log.message,
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                                fontFamily = HarnessMono,
                                                color = scheme.onSurface,
                                            )
                                            if (log.source != null) {
                                                Text(
                                                    "${log.source.substringAfterLast('/')}:${log.lineNumber}",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                    fontFamily = HarnessMono,
                                                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                )
                                            }
                                        }
                                        if (log.level == ConsoleMessage.MessageLevel.ERROR) {
                                            Spacer(Modifier.width(4.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = scheme.errorContainer,
                                                modifier = Modifier.clickable {
                                                    onFixBug("Fix JavaScript error on $currentUrl: ${log.message} at ${log.source ?: "inline"}:${log.lineNumber}")
                                                },
                                            ) {
                                                Text(
                                                    uiContext.getString(R.string.settings_action_fix),
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                                    color = scheme.onErrorContainer,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Agent activity trail: what the agent did in this browser, newest first
            androidx.compose.animation.AnimatedVisibility(
                visible = showAgentTrack,
                enter = expandVertically(fastEffectsSpec()) + fadeIn(fastEffectsSpec()),
                exit = shrinkVertically(fastEffectsSpec()) + fadeOut(fastEffectsSpec()),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Surface(
                    color = scheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(scheme.surfaceContainerLow)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                uiContext.getString(R.string.ui_agent_activity, (agentTracks.size).toString()),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = HarnessMono,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { browserController?.clearTrack() },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = uiContext.getString(R.string.ui_clear_trail), modifier = Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(2.dp))
                            IconButton(onClick = { showAgentTrack = false }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.Close, contentDescription = uiContext.getString(R.string.ui_close_activity), modifier = Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))

                        if (agentTracks.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    uiContext.getString(R.string.ui_the_agent_has_not_touched_this_browser),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    fontFamily = HarnessMono,
                                )
                            }
                        } else {
                            val timeFmt = remember {
                                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                            }
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                items(agentTracks.asReversed()) { entry ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                if (entry.ok) Color.Transparent else scheme.error.copy(alpha = 0.10f),
                                                RoundedCornerShape(6.dp),
                                            )
                                            .padding(horizontal = 4.dp, vertical = 3.dp),
                                    ) {
                                        Text(
                                            timeFmt.format(java.util.Date(entry.timestamp)),
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                            fontFamily = HarnessMono,
                                            color = scheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (entry.ok) scheme.secondaryContainer else scheme.errorContainer,
                                        ) {
                                            Text(
                                                entry.action,
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                                color = if (entry.ok) scheme.onSecondaryContainer else scheme.onErrorContainer,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                            )
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            entry.detail,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            fontFamily = HarnessMono,
                                            color = scheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { wv ->
                // Hand the WebView (with its history and page state) to the
                // browser controller instead of destroying it, so agent
                // navigation survives the sheet closing. Falls back to
                // destroy when no controller is wired.
                val bc = browserController
                if (bc != null) bc.adoptWebView(wv) else wv.destroy()
            }
        }
    }
}

/** Small pulsing dot used on the "Agent is controlling" strip. */
@Composable
private fun AgentPulsingDot(tint: Color) {
    val transition = rememberInfiniteTransition(label = "agent pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "agent pulse alpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(tint.copy(alpha = alpha), CircleShape),
    )
}

