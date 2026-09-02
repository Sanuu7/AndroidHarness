package com.androidharness.app.ui.chat.components

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontFamily
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
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.theme.fastEffectsSpec
import com.androidharness.app.ui.theme.fastSpatialSpec
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.launch

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
    workspace: WorkspaceFs? = null,
    messages: List<ChatMessage> = emptyList(),
    browserController: com.androidharness.app.browser.BrowserController? = null,
    onSendPrompt: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    val effectiveInitial = initialTarget ?: browserController?.getActiveUrl()
    var stage by remember {
        mutableStateOf(if (!effectiveInitial.isNullOrBlank()) WebPreviewStage.WEB_VIEW else WebPreviewStage.SOURCE_HUB)
    }
    var currentTarget by remember { mutableStateOf(effectiveInitial ?: "http://localhost:3000") }

    var isScanningSources by remember { mutableStateOf(true) }
    var activePorts by remember { mutableStateOf<List<Int>>(emptyList()) }
    var workspaceHtmlFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var chatLinks by remember { mutableStateOf<List<String>>(emptyList()) }

    var isFullscreen by remember { mutableStateOf(false) }
    var showConsole by remember { mutableStateOf(false) }
    val consoleLogs = remember { mutableStateListOf<WebConsoleLog>() }

    // Scan for all sources in background
    LaunchedEffect(workspace, messages) {
        isScanningSources = true
        activePorts = LocalPortProbe.probe()
        workspaceHtmlFiles = WebResourceExtractor.findWorkspaceHtmlFiles(workspace)
        chatLinks = messages.flatMap { WebResourceExtractor.extractUrls(it.text) }.distinct()
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
                    "Web & HTML Preview",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (isScanning) "Scanning workspace & live ports…" else "Select a source to inspect",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = scheme.onSurfaceVariant)
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
                    title = "Workspace Files",
                    count = workspaceHtmlFiles.size,
                    icon = Icons.Outlined.Folder,
                    defaultExpanded = true,
                ) {
                    workspaceHtmlFiles.forEach { file ->
                        SourceRowItem(
                            icon = Icons.Outlined.Description,
                            title = file.substringAfterLast('/'),
                            subtitle = file.substringBeforeLast('/', "workspace root"),
                            badgeText = "HTML",
                            onClick = { onSelectTarget(file) },
                        )
                    }
                }
            }

            // 2. Chat Links (only if present in conversation)
            if (chatLinks.isNotEmpty()) {
                CollapsibleSection(
                    title = "Links in Chat",
                    count = chatLinks.size,
                    icon = Icons.Outlined.Link,
                    defaultExpanded = true,
                ) {
                    chatLinks.forEach { link ->
                        SourceRowItem(
                            icon = Icons.Outlined.Language,
                            title = link,
                            subtitle = if (LocalPortProbe.isLocalhostUrl(link)) "Local server" else "Web URL",
                            badgeText = "OPEN",
                            onClick = { onSelectTarget(link) },
                        )
                    }
                }
            }

            // 3. Localhost servers (only show LIVE servers)
            if (activePorts.isNotEmpty()) {
                CollapsibleSection(
                    title = "Live Local Servers",
                    count = activePorts.size,
                    icon = Icons.Outlined.Sensors,
                    defaultExpanded = true,
                    headerAction = {
                        IconButton(onClick = onRescanPorts, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Rescan ports",
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
                            subtitle = "Active listening server on port $port",
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
                            "No active web sources detected",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Start a dev server in Terminal (e.g. `python3 -m http.server 8000` or `npm run dev`), or enter an address below.",
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
                        "Enter Custom Address or Port",
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
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
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
                                "Open",
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
                            fontFamily = FontFamily.Monospace,
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
                fontFamily = FontFamily.Monospace,
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
                    fontFamily = FontFamily.Monospace,
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
) {
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

    // Agent control state: banner strip + expandable activity trail
    var showAgentTrack by remember { mutableStateOf(false) }
    val controlActiveFlow = remember(browserController) {
        browserController?.isAgentControlling ?: kotlinx.coroutines.flow.MutableStateFlow(false)
    }
    val agentControlling by controlActiveFlow.collectAsState()
    val trackFlow = remember(browserController) {
        browserController?.actionTrack ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    }
    val agentTracks by trackFlow.collectAsState()

    val isWorkspaceHtml: (String) -> Boolean = { t ->
        t.endsWith(".html", ignoreCase = true) || t.endsWith(".htm", ignoreCase = true)
    }

    val load: (String) -> Unit = { t ->
        isLoading = true
        errorMessage = null
        val view = webViewRef
        if (view != null) {
            if (isWorkspaceHtml(t)) {
                val node = runCatching { workspace?.resolve(t) }.getOrNull()
                if (node != null && node.exists && node.isFile) {
                    pageTitle = t.substringAfterLast('/')
                    // Load through the shared workspace origin instead of
                    // loadDataWithBaseURL: loadData creates a data: history
                    // entry, which pollutes back/forward for the agent.
                    currentUrl = BrowserController.localFileUrl(t)
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
        // Clean Title & Action Header Bar (Spaced controls with comfortable touch targets)
        Surface(
            color = scheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = onBackToHub, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All Sources", tint = scheme.onSurface, modifier = Modifier.size(20.dp))
                    }

                    IconButton(
                        onClick = { webViewRef?.let { if (it.canGoBack()) it.goBack() } },
                        enabled = canGoBack,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "History Back",
                            modifier = Modifier.size(19.dp),
                            tint = if (canGoBack) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.3f),
                        )
                    }

                    IconButton(
                        onClick = { webViewRef?.let { if (it.canGoForward()) it.goForward() } },
                        enabled = canGoForward,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "History Forward",
                            modifier = Modifier.size(19.dp),
                            tint = if (canGoForward) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.3f),
                        )
                    }

                    IconButton(onClick = reload, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload", modifier = Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Eruda DevTools Launcher
                    IconButton(onClick = openDevTools, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Outlined.DeveloperMode,
                            contentDescription = "DevTools",
                            modifier = Modifier.size(20.dp),
                            tint = scheme.primary,
                        )
                    }

                    // Open in external browser
                    IconButton(
                        onClick = {
                            runCatching {
                                if (!isWorkspaceHtml(currentUrl)) {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                                    context.startActivity(intent)
                                }
                            }
                        },
                        enabled = !isWorkspaceHtml(currentUrl),
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = "Open in external browser",
                            modifier = Modifier.size(20.dp),
                            tint = if (!isWorkspaceHtml(currentUrl)) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.3f),
                        )
                    }

                    // JS Console Drawer Toggle
                    IconButton(onClick = onToggleConsole, modifier = Modifier.size(38.dp)) {
                        Box {
                            Icon(
                                Icons.Outlined.BugReport,
                                contentDescription = "Console",
                                modifier = Modifier.size(20.dp),
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

                    IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(38.dp)) {
                        Icon(
                            if (isFullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                            contentDescription = "Fullscreen",
                            modifier = Modifier.size(20.dp),
                            tint = scheme.onSurfaceVariant,
                        )
                    }

                    IconButton(onClick = onClose, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (isLoading) {
            ThinLinearProgress(modifier = Modifier.fillMaxWidth())
        }

        // "Agent is controlling the browser" strip; tap to expand the action trail
        AnimatedVisibility(
            visible = agentControlling,
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
                    AgentPulsingDot(tint = scheme.onPrimaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Agent is controlling the browser",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (showAgentTrack) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                        contentDescription = if (showAgentTrack) "Hide agent activity" else "Show agent activity",
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

                        // Touch listener: lock and consume user touches when agent is driving
                        setOnTouchListener { v, event ->
                            if (browserController?.isAgentControlling?.value == true) {
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
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
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
                                    errorMessage = error?.description?.toString() ?: "Failed to connect to $target"
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

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                                if (newProgress == 100) isLoading = false
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                if (!title.isNullOrBlank()) pageTitle = title
                            }

                            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                if (consoleMessage != null) {
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
                            "Loading preview…",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            target,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
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
                            "Cannot load page",
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
                                    "Pick source",
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
                                    Text("Fix with Agent", color = scheme.onErrorContainer, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = scheme.primary,
                                modifier = Modifier.clickable { reload() },
                            ) {
                                Text(
                                    "Retry",
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
                                "JS CONSOLE (${consoleLogs.size})",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
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
                                            "Fix bug in chat",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                            color = scheme.onErrorContainer,
                                        )
                                    }
                                }
                            }

                            IconButton(onClick = { consoleLogs.clear() }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Clear logs", modifier = Modifier.size(14.dp), tint = scheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(6.dp))
                            IconButton(onClick = onToggleConsole, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close Console", modifier = Modifier.size(14.dp), tint = scheme.onSurfaceVariant)
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
                                    "No console messages logged",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
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
                                            fontFamily = FontFamily.Monospace,
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
                                                fontFamily = FontFamily.Monospace,
                                                color = scheme.onSurface,
                                            )
                                            if (log.source != null) {
                                                Text(
                                                    "${log.source.substringAfterLast('/')}:${log.lineNumber}",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                    fontFamily = FontFamily.Monospace,
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
                                                    "Fix",
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
                                "AGENT ACTIVITY (${agentTracks.size})",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { browserController?.clearTrack() },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Clear trail", modifier = Modifier.size(14.dp), tint = scheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(6.dp))
                            IconButton(onClick = { showAgentTrack = false }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close activity", modifier = Modifier.size(14.dp), tint = scheme.onSurfaceVariant)
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
                                    "The agent has not touched this browser yet",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
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
                                            fontFamily = FontFamily.Monospace,
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
                                            fontFamily = FontFamily.Monospace,
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

