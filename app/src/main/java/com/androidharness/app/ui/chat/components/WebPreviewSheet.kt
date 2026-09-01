package com.androidharness.app.ui.chat.components

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

data class WebConsoleLog(
    val level: ConsoleMessage.MessageLevel,
    val message: String,
    val source: String?,
    val lineNumber: Int,
    val timestamp: Long = System.currentTimeMillis(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebPreviewSheet(
    initialUrlOrPath: String = "http://localhost:3000",
    workspace: WorkspaceFs? = null,
    messages: List<ChatMessage> = emptyList(),
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val statusColors = LocalStatusColors.current

    var currentTarget by remember { mutableStateOf(initialUrlOrPath) }
    var inputUrl by remember { mutableStateOf(initialUrlOrPath) }
    var isLoading by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("Web Preview") }
    var progress by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var activePorts by remember { mutableStateOf<List<Int>>(emptyList()) }
    var workspaceHtmlFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var chatLinks by remember { mutableStateOf<List<String>>(emptyList()) }
    var isFullscreen by remember { mutableStateOf(false) }
    var showConsole by remember { mutableStateOf(false) }
    var showSourceSelector by remember { mutableStateOf(false) }
    val consoleLogs = remember { mutableStateListOf<WebConsoleLog>() }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedSourceTab by remember { mutableIntStateOf(0) }

    // Load available sources on open
    LaunchedEffect(workspace, messages) {
        activePorts = LocalPortProbe.probe()
        workspaceHtmlFiles = WebResourceExtractor.findWorkspaceHtmlFiles(workspace)
        chatLinks = messages.flatMap { WebResourceExtractor.extractUrls(it.text) }.distinct()
    }

    val isWorkspaceHtml: (String) -> Boolean = { target ->
        target.endsWith(".html", ignoreCase = true) || target.endsWith(".htm", ignoreCase = true) ||
            (workspace != null && target in workspaceHtmlFiles)
    }

    val loadTarget: (String) -> Unit = { target ->
        currentTarget = target
        inputUrl = target
        errorMessage = null
        val view = webViewRef
        if (view != null) {
            if (isWorkspaceHtml(target)) {
                // Workspace file preview: load HTML text directly with virtual base URL
                val node = runCatching { workspace?.resolve(target) }.getOrNull()
                if (node != null && node.exists && node.isFile) {
                    val html = runCatching { node.readText() }.getOrDefault("")
                    pageTitle = target.substringAfterLast('/')
                    view.loadDataWithBaseURL("https://localhost/", html, "text/html", "UTF-8", null)
                } else {
                    errorMessage = "File '$target' does not exist in the active workspace."
                }
            } else {
                val norm = LocalPortProbe.normalizeLocalUrl(target)
                inputUrl = norm
                currentTarget = norm
                view.loadUrl(norm)
            }
        }
    }

    val reload: () -> Unit = {
        errorMessage = null
        if (isWorkspaceHtml(currentTarget)) {
            loadTarget(currentTarget)
        } else {
            webViewRef?.reload()
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
            // Top Navigation Bar
            Surface(
                color = scheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        IconButton(
                            onClick = { webViewRef?.let { if (it.canGoBack()) it.goBack() } },
                            enabled = canGoBack,
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                modifier = Modifier.size(18.dp),
                                tint = if (canGoBack) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                        }

                        IconButton(
                            onClick = { webViewRef?.let { if (it.canGoForward()) it.goForward() } },
                            enabled = canGoForward,
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Forward",
                                modifier = Modifier.size(18.dp),
                                tint = if (canGoForward) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                        }

                        IconButton(
                            onClick = reload,
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Reload",
                                modifier = Modifier.size(18.dp),
                                tint = scheme.onSurfaceVariant,
                            )
                        }

                        // Target input / address bar
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = scheme.surfaceContainerHighest,
                            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .padding(horizontal = 4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp),
                            ) {
                                Icon(
                                    if (isWorkspaceHtml(currentTarget)) Icons.Outlined.Description else Icons.Default.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = scheme.primary,
                                )
                                Spacer(Modifier.width(6.dp))
                                BasicTextField(
                                    value = inputUrl,
                                    onValueChange = { inputUrl = it },
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        color = scheme.onSurface,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                    ),
                                    singleLine = true,
                                    cursorBrush = SolidColor(scheme.primary),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Go,
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onGo = { loadTarget(inputUrl) },
                                    ),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        // Open external link
                        IconButton(
                            onClick = {
                                runCatching {
                                    if (!isWorkspaceHtml(currentTarget)) {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentTarget))
                                        context.startActivity(intent)
                                    }
                                }
                            },
                            enabled = !isWorkspaceHtml(currentTarget),
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = "Open in external browser",
                                modifier = Modifier.size(18.dp),
                                tint = if (!isWorkspaceHtml(currentTarget)) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.3f),
                            )
                        }

                        // Console logs drawer toggle
                        IconButton(
                            onClick = { showConsole = !showConsole },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Box {
                                Icon(
                                    Icons.Outlined.BugReport,
                                    contentDescription = "Console Logs",
                                    modifier = Modifier.size(18.dp),
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

                        IconButton(
                            onClick = { isFullscreen = !isFullscreen },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                if (isFullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                                contentDescription = "Toggle Fullscreen",
                                modifier = Modifier.size(18.dp),
                                tint = scheme.onSurfaceVariant,
                            )
                        }

                        IconButton(
                            onClick = {
                                scope.launch {
                                    sheetState.hide()
                                    onDismiss()
                                }
                            },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close preview",
                                modifier = Modifier.size(18.dp),
                                tint = scheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Source Tabs & Selector Chips
                    ScrollableTabRow(
                        selectedTabIndex = selectedSourceTab,
                        edgePadding = 8.dp,
                        divider = {},
                        containerColor = Color.Transparent,
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                    ) {
                        Tab(
                            selected = selectedSourceTab == 0,
                            onClick = { selectedSourceTab = 0 },
                            text = { Text("Localhost (${activePorts.size})", style = MaterialTheme.typography.labelMedium) },
                        )
                        Tab(
                            selected = selectedSourceTab == 1,
                            onClick = { selectedSourceTab = 1 },
                            text = { Text("Workspace HTML (${workspaceHtmlFiles.size})", style = MaterialTheme.typography.labelMedium) },
                        )
                        Tab(
                            selected = selectedSourceTab == 2,
                            onClick = { selectedSourceTab = 2 },
                            text = { Text("Chat Links (${chatLinks.size})", style = MaterialTheme.typography.labelMedium) },
                        )
                    }

                    // Chip list for the selected category
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        when (selectedSourceTab) {
                            0 -> {
                                val portsToShow = (activePorts + LocalPortProbe.COMMON_PORTS).distinct()
                                portsToShow.take(8).forEach { port ->
                                    val isOpen = port in activePorts
                                    val isSelected = currentTarget.contains(":$port")
                                    PreviewChip(
                                        label = ":$port",
                                        isSelected = isSelected,
                                        isLive = isOpen,
                                        onClick = { loadTarget("http://localhost:$port") },
                                    )
                                }
                                IconButton(
                                    onClick = { scope.launch { activePorts = LocalPortProbe.probe() } },
                                    modifier = Modifier.size(26.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.Sensors,
                                        contentDescription = "Scan Ports",
                                        tint = scheme.primary,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                            1 -> {
                                if (workspaceHtmlFiles.isEmpty()) {
                                    Text(
                                        "No .html files in workspace",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                    )
                                } else {
                                    workspaceHtmlFiles.forEach { file ->
                                        PreviewChip(
                                            label = file,
                                            isSelected = currentTarget == file,
                                            isLive = false,
                                            onClick = { loadTarget(file) },
                                        )
                                    }
                                }
                            }
                            2 -> {
                                if (chatLinks.isEmpty()) {
                                    Text(
                                        "No links found in chat",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                    )
                                } else {
                                    chatLinks.forEach { link ->
                                        PreviewChip(
                                            label = link.removePrefix("https://").removePrefix("http://").take(24),
                                            isSelected = currentTarget == link,
                                            isLive = false,
                                            onClick = { loadTarget(link) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (isLoading) {
                        ThinLinearProgress(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // Main Web View & Overlays
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            @SuppressLint("SetJavaScriptEnabled")
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
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
                                            currentTarget = it
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
                                        errorMessage = error?.description?.toString() ?: "Failed to connect"
                                    }
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): WebResourceResponse? {
                                    // Resolve workspace relative assets for local HTML previews
                                    val uri = request?.url ?: return null
                                    if (uri.host == "localhost" && workspace != null && isWorkspaceHtml(currentTarget)) {
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
                            loadTarget(currentTarget)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                // Error Overlay
                errorMessage?.let { err ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(scheme.surface.copy(alpha = 0.95f))
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
                                modifier = Modifier.size(42.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Cannot load target",
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
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = scheme.primary,
                                modifier = Modifier.clickable { reload() },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = scheme.onPrimary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Retry", color = scheme.onPrimary, style = MaterialTheme.typography.labelMedium)
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
                                IconButton(
                                    onClick = { consoleLogs.clear() },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "Clear logs",
                                        modifier = Modifier.size(14.dp),
                                        tint = scheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                IconButton(
                                    onClick = { showConsole = false },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Close Console",
                                        modifier = Modifier.size(14.dp),
                                        tint = scheme.onSurfaceVariant,
                                    )
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
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
        }
    }
}

@Composable
private fun PreviewChip(
    label: String,
    isSelected: Boolean,
    isLive: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val statusColors = LocalStatusColors.current

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = when {
            isSelected -> scheme.primaryContainer
            isLive -> statusColors.success.copy(alpha = 0.15f)
            else -> scheme.surfaceContainerHigh
        },
        border = BorderStroke(
            1.dp,
            if (isSelected) scheme.primary
            else if (isLive) statusColors.success.copy(alpha = 0.5f)
            else scheme.outlineVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            if (isLive) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(statusColors.success, CircleShape),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                fontFamily = FontFamily.Monospace,
                color = if (isSelected) scheme.onPrimaryContainer else scheme.onSurface,
                maxLines = 1,
            )
        }
    }
}
