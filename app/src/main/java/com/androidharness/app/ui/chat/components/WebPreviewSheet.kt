package com.androidharness.app.ui.chat.components

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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

/** Eruda mobile devtools script CDN loader */
private const val ERUDA_INJECT_JS = """
(function () {
    if (window.__eruda_injected) {
        if (window.eruda) {
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
            window.__eruda_injected = true;
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
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    var stage by remember {
        mutableStateOf(if (!initialTarget.isNullOrBlank()) WebPreviewStage.WEB_VIEW else WebPreviewStage.SOURCE_HUB)
    }
    var currentTarget by remember { mutableStateOf(initialTarget ?: "http://localhost:3000") }
    var inputUrl by remember { mutableStateOf(currentTarget) }

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
        inputUrl = norm
        stage = WebPreviewStage.WEB_VIEW
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
                            onToggleFullscreen = { isFullscreen = !isFullscreen },
                            onToggleConsole = { showConsole = !showConsole },
                            onBackToHub = { stage = WebPreviewStage.SOURCE_HUB },
                            onTargetChanged = { newTarget ->
                                currentTarget = newTarget
                                inputUrl = newTarget
                            },
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
                            placeholder = { Text("e.g. 5173, localhost:8000, https://...") },
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
    onToggleFullscreen: () -> Unit,
    onToggleConsole: () -> Unit,
    onBackToHub: () -> Unit,
    onTargetChanged: (String) -> Unit,
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val statusColors = LocalStatusColors.current
    val context = LocalContext.current

    var inputUrl by remember { mutableStateOf(target) }
    var currentUrl by remember { mutableStateOf(target) }
    var isLoading by remember { mutableStateOf(true) }
    var progress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf(target.substringAfterLast('/')) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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
                    val html = runCatching { node.readText() }.getOrDefault("")
                    pageTitle = t.substringAfterLast('/')
                    view.loadDataWithBaseURL("https://localhost/", html, "text/html", "UTF-8", null)
                } else {
                    isLoading = false
                    errorMessage = "File '$t' not found in active workspace."
                }
            } else {
                val norm = LocalPortProbe.normalizeLocalUrl(t)
                inputUrl = norm
                currentUrl = norm
                view.loadUrl(norm)
            }
        }
    }

    val reload: () -> Unit = {
        load(currentUrl)
    }

    val openDevTools: () -> Unit = {
        webViewRef?.evaluateJavascript(ERUDA_INJECT_JS, null)
    }

    Column(Modifier.fillMaxSize()) {
        // Navigation Bar
        Surface(
            color = scheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            ) {
                IconButton(onClick = onBackToHub, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All Sources", tint = scheme.onSurface)
                }

                IconButton(
                    onClick = { webViewRef?.let { if (it.canGoBack()) it.goBack() } },
                    enabled = canGoBack,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "History Back",
                        modifier = Modifier.size(16.dp),
                        tint = if (canGoBack) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.3f),
                    )
                }

                IconButton(
                    onClick = { webViewRef?.let { if (it.canGoForward()) it.goForward() } },
                    enabled = canGoForward,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "History Forward",
                        modifier = Modifier.size(16.dp),
                        tint = if (canGoForward) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.3f),
                    )
                }

                IconButton(onClick = reload, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reload", modifier = Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                }

                // Address input bar
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = scheme.surfaceContainerHighest,
                    border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .padding(horizontal = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Icon(
                            if (isWorkspaceHtml(currentUrl)) Icons.Outlined.Description else Icons.Default.Language,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = scheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        BasicTextField(
                            value = inputUrl,
                            onValueChange = { inputUrl = it },
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                color = scheme.onSurface,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                            singleLine = true,
                            cursorBrush = SolidColor(scheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    currentUrl = inputUrl
                                    onTargetChanged(inputUrl)
                                    load(inputUrl)
                                },
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Eruda DevTools Launcher
                IconButton(onClick = openDevTools, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.DeveloperMode,
                        contentDescription = "DevTools",
                        modifier = Modifier.size(16.dp),
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
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = "Open in external browser",
                        modifier = Modifier.size(16.dp),
                        tint = if (!isWorkspaceHtml(currentUrl)) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.3f),
                    )
                }

                // JS Console Drawer Toggle
                IconButton(onClick = onToggleConsole, modifier = Modifier.size(32.dp)) {
                    Box {
                        Icon(
                            Icons.Outlined.BugReport,
                            contentDescription = "Console",
                            modifier = Modifier.size(16.dp),
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
                                    .size(5.dp)
                                    .background(scheme.error, CircleShape)
                                    .align(Alignment.TopEnd),
                            )
                        }
                    }
                }

                IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (isFullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                        contentDescription = "Fullscreen",
                        modifier = Modifier.size(16.dp),
                        tint = scheme.onSurfaceVariant,
                    )
                }

                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                }
            }
        }

        if (isLoading) {
            ThinLinearProgress(modifier = Modifier.fillMaxWidth())
        }

        // Hardware-Accelerated Smooth WebView
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )

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
                                        inputUrl = it
                                    }
                                }
                                canGoBack = canGoBack()
                                canGoForward = canGoForward()
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                canGoBack = canGoBack()
                                canGoForward = canGoForward()
                                pageTitle = view?.title ?: "Web Preview"
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
                                val uri = request?.url ?: return null
                                if (uri.host == "localhost" && workspace != null && isWorkspaceHtml(currentUrl)) {
                                    val path = uri.path?.trimStart('/') ?: return null
                                    val node = runCatching { workspace.resolve(path) }.getOrNull()
                                    if (node != null && node.exists && node.isFile) {
                                        val mime = when (node.name.substringAfterLast('.', "").lowercase()) {
                                            "css" -> "text/css"
                                            "js" -> "application/javascript"
                                            "json" -> "application/json"
                                            "png" -> "image/png"
                                            "jpg", "jpeg" -> "image/jpeg"
                                            "svg" -> "image/svg+xml"
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
                                    if (consoleLogs.size > 200) consoleLogs.removeLast()
                                }
                                return true
                            }
                        }

                        webViewRef = this
                        load(target)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

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

            // Error Overlay
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
                                    "Pick another source",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                )
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
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Console Drawer Overlay
            androidx.compose.animation.AnimatedVisibility(
                visible = showConsole,
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
                                "JS CONSOLE (${consoleLogs.size})",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
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
                                        Text(
                                            log.message,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            fontFamily = FontFamily.Monospace,
                                            color = scheme.onSurface,
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
            webViewRef?.destroy()
        }
    }
}
