package com.androidharness.app.ui.chat.components

import com.androidharness.app.ui.common.AppLinearProgressIndicator
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.androidharness.app.R
import com.androidharness.app.artifacts.ArtifactSandbox
import com.androidharness.app.artifacts.ArtifactWebView
import com.androidharness.app.artifacts.ChatArtifact
import com.androidharness.app.ui.theme.HarnessMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun ConceptAttachmentChip(attachment: com.androidharness.app.artifacts.ArtifactAttachment, onRemove: () -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    InputChip(selected = true, onClick = onRemove,
        label = { Text(uiContext.getString(R.string.ui_concept, (attachment.title).toString()), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
        trailingIcon = { Icon(Icons.Filled.Close, uiContext.getString(R.string.ui_remove_concept_attachment), Modifier.size(14.dp)) },
        modifier = Modifier.padding(horizontal = 8.dp))
}

/** Native controls remain outside the generated document; expansion preserves the live page. */
@Composable
internal fun ArtifactCard(
    artifact: ChatArtifact,
    revisions: List<ChatArtifact> = listOf(artifact),
    loadRevisions: (suspend () -> List<ChatArtifact>)? = null,
    onRevise: (ChatArtifact) -> Unit = {},
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var savedVersions by remember(artifact.id) { mutableStateOf<List<ChatArtifact>>(emptyList()) }
    val versions = remember(revisions, artifact, savedVersions) { (savedVersions + revisions + artifact).distinctBy { it.revision } }
    var revision by rememberSaveable(artifact.revision) { mutableStateOf(artifact.revision) }
    val selected = versions.firstOrNull { it.revision == revision } ?: artifact
    var expanded by rememberSaveable { mutableStateOf(false) }
    var source by rememberSaveable { mutableStateOf(false) }
    var versionMenu by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var unavailable by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saveStatus by remember { mutableStateOf<String?>(null) }
    var exportingRevision by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val scheme = MaterialTheme.colorScheme
    val theme = com.androidharness.app.ui.chat.rich.rememberArtifactTheme()
    val web = remember(selected.revision, theme, reload) {
        ArtifactWebView(context, onLoading = { loading = it }, onError = { error = it },
            onUnavailable = { unavailable = true; error = it })
    }
    DisposableEffect(web) { onDispose { web.release() } }
    LaunchedEffect(web, selected.revision, theme) { error = null; unavailable = false; loading = true; web.show(selected, dark, theme = theme) }
    LaunchedEffect(artifact.revision) {
        if (loadRevisions != null) try {
            savedVersions = withContext(Dispatchers.IO) { loadRevisions().filter { it.id == artifact.id } }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { error = uiContext.getString(R.string.ui_couldn_t_load_older_versions_reopen_the) }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        val document = versions.firstOrNull { it.revision == exportingRevision }
        exportingRevision = null
        if (uri != null && document != null) scope.launch {
            saveStatus = try {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(ArtifactSandbox.sourceDocument(document, dark, theme = theme)) }
                }
                uiContext.getString(R.string.ui_html_saved)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { uiContext.getString(R.string.ui_couldn_t_save_try_again) }
        }
    }
    val header: @Composable (Boolean) -> Unit = { fullscreen ->
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_chat_sparkles), null, Modifier.size(18.dp), tint = scheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(selected.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(uiContext.getString(R.string.ui_interactive_concept), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            if (versions.size > 1) Box {
                TextButton(onClick = { versionMenu = true }) {
                    Text("v${versions.indexOf(selected) + 1}", style = MaterialTheme.typography.labelMedium)
                    Icon(Icons.Filled.KeyboardArrowDown, uiContext.getString(R.string.ui_concept_versions), Modifier.size(14.dp))
                }
                DropdownMenu(versionMenu, { versionMenu = false }) {
                    versions.forEachIndexed { index, version -> DropdownMenuItem(
                        text = { Text(uiContext.getString(R.string.ui_version, (index + 1).toString())) },
                        trailingIcon = { if (version.revision == selected.revision) Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) },
                        onClick = { revision = version.revision; versionMenu = false },
                    ) }
                }
            }
            IconButton(onClick = { expanded = !fullscreen }) {
                Icon(if (fullscreen) Icons.Filled.Close else Icons.Outlined.Fullscreen,
                    if (fullscreen) uiContext.getString(R.string.ui_close_concept) else uiContext.getString(R.string.ui_expand_concept), Modifier.size(20.dp))
            }
        }
    }
    val tabs: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !source, onClick = { source = false }, label = { Text(uiContext.getString(R.string.ui_preview)) })
            Spacer(Modifier.width(6.dp))
            FilterChip(selected = source, onClick = { source = true }, label = { Text(uiContext.getString(R.string.ui_code)) }, leadingIcon = { Icon(Icons.Outlined.Code, null, Modifier.size(16.dp)) })
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { error = null; reload++ }) { Icon(Icons.Outlined.Refresh, uiContext.getString(R.string.ui_reload_concept), Modifier.size(18.dp)) }
        }
    }
    val content: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier) {
            if (source) SelectionContainer {
                Text(selected.html, fontFamily = HarnessMono, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp))
            } else {
                if (unavailable) Column(Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(uiContext.getString(R.string.ui_preview_unavailable), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { reload++ }) { Text(uiContext.getString(R.string.ui_reload_preview)) }
                } else ArtifactViewHost(web, Modifier.fillMaxSize())
                if (loading) AppLinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
    val footer: @Composable () -> Unit = {
        Column {
            error?.let { Text(it, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = scheme.error) }
            saveStatus?.let { Text(it, Modifier.padding(horizontal = 14.dp), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { expanded = false; onRevise(selected) }) { Text(uiContext.getString(R.string.ui_revise)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    exportingRevision = selected.revision
                    save.launch(selected.title.replace(Regex("[^a-zA-Z0-9_-]+"), "-").take(64).ifBlank { "concept" } + ".html")
                }) { Icon(Icons.Outlined.Download, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(uiContext.getString(R.string.ui_save_html)) }
            }
        }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column {
            if (!expanded) {
                header(false); tabs(); HorizontalDivider(color = scheme.outlineVariant)
                content(Modifier.fillMaxWidth().height(300.dp))
                HorizontalDivider(color = scheme.outlineVariant); footer()
            } else Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                TextButton(onClick = { expanded = true }) { Text(uiContext.getString(R.string.ui_concept_is_expanded)) }
            }
        }
    }
    if (expanded) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val dialogView = androidx.compose.ui.platform.LocalView.current
        val dialogWindow = (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        DisposableEffect(dialogWindow, dialogView, dark, scheme.surface) {
            // Apply after Dialog's own window parameters so its default appearance cannot win.
            val updateBars = Runnable { dialogWindow?.let {
                it.setDimAmount(0f) // The fullscreen surface covers the app; dimming would also fade system navigation.
                it.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(scheme.surface.toArgb()))
                @Suppress("DEPRECATION")
                it.navigationBarColor = scheme.surface.toArgb()
                if (android.os.Build.VERSION.SDK_INT >= 29) it.isNavigationBarContrastEnforced = false
                androidx.core.view.WindowCompat.getInsetsController(it, dialogView).apply {
                    isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
                }
            } }
            dialogView.post(updateBars)
            onDispose { dialogView.removeCallbacks(updateBars) }
        }
        Surface(color = scheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                header(true); tabs(); HorizontalDivider(color = scheme.outlineVariant)
                content(Modifier.weight(1f).fillMaxWidth())
                HorizontalDivider(color = scheme.outlineVariant); footer()
            }
        }
    }
}

@Composable
private fun ArtifactViewHost(web: ArtifactWebView, modifier: Modifier) {
    AndroidView(factory = { context -> FrameLayout(context).apply {
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        if (!web.isReleased) addView(web, FrameLayout.LayoutParams(-1, -1))
    } }, modifier = modifier, update = { host ->
        if (!web.isReleased && web.parent !== host) {
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            host.removeAllViews(); host.addView(web, FrameLayout.LayoutParams(-1, -1))
        }
    }, onRelease = { host -> if (web.parent === host) host.removeView(web) })
}
