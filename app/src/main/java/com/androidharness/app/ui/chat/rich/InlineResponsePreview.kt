package com.androidharness.app.ui.chat.rich

import com.androidharness.app.ui.theme.HarnessGlassSurface as Surface

import com.androidharness.app.R
import com.androidharness.app.ui.common.AppLinearProgressIndicator
import com.androidharness.app.core.response.RichResponse

import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.androidharness.app.artifacts.ArtifactWebView
import com.androidharness.app.artifacts.ChatArtifact
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

/** Content-sized conversation surface; artifact versioning/export controls remain in ArtifactCard. */
@Composable
internal fun InlineResponsePreview(node: RichResponse.Node.Preview, onFollowUp: ((RichResponse.FollowUp) -> Unit)?) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val theme = rememberArtifactTheme()
    val artifact = remember(node) { ChatArtifact.create(node.title, node.html) }
    var height by remember(node) { mutableIntStateOf(220) }
    var summary by remember(node) { mutableStateOf("") }
    var loading by remember(node) { mutableStateOf(true) }
    var error by remember(node) { mutableStateOf<String?>(null) }
    var unavailable by remember(node) { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var expanded by rememberSaveable(node) { mutableStateOf(false) }
    val web = remember(artifact.revision, theme, reload) {
        ArtifactWebView(context, onLoading = { loading = it }, onError = { error = it }, onUnavailable = { unavailable = true; error = it },
            onResponseLayout = { measured, state -> height = measured; summary = state })
    }
    DisposableEffect(web) { onDispose { web.release() } }
    LaunchedEffect(web) { error = null; unavailable = false; summary = ""; web.show(artifact, dark, responseLayout = true, theme = theme) }
    val content: @Composable (Modifier) -> Unit = { modifier -> Box(modifier) {
        if (unavailable) Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(uiContext.getString(R.string.ui_preview_unavailable), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { reload++ }) { Text(uiContext.getString(R.string.ui_reload_preview)) }
        } else AndroidView(factory = { ctx -> FrameLayout(ctx).apply {
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            if (!web.isReleased) addView(web, FrameLayout.LayoutParams(-1, -1))
        } }, modifier = Modifier.fillMaxSize(), update = { host ->
            if (!web.isReleased && web.parent !== host) {
                (web.parent as? android.view.ViewGroup)?.removeView(web); host.removeAllViews()
                host.addView(web, FrameLayout.LayoutParams(-1, -1))
            }
        }, onRelease = { host -> if (web.parent === host) host.removeView(web) })
        if (loading) AppLinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    } }
    val controls: @Composable (Boolean) -> Unit = { fullscreen ->
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(node.title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { expanded = !fullscreen }) { Icon(if (fullscreen) Icons.Filled.Close else Icons.Outlined.Fullscreen,
                if (fullscreen) uiContext.getString(R.string.ui_close_response_preview) else uiContext.getString(R.string.ui_expand_response_preview), Modifier.size(19.dp)) }
        }
    }
    val footer: @Composable () -> Unit = {
        if (error != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(uiContext.getString(R.string.ui_some_preview_content_couldn_t_load), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            IconButton(onClick = { reload++ }) { Icon(Icons.Outlined.Refresh, uiContext.getString(R.string.ui_reload_response_preview), Modifier.size(18.dp)) }
        }
        onFollowUp?.let { follow ->
            TextButton(onClick = { expanded = false; follow(RichResponse.followUp("Help me explore this further.", node.title, summary)) }, Modifier.padding(horizontal = 2.dp)) { Text(uiContext.getString(R.string.ui_ask_about_this)) }
        }
    }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            controls(false)
            if (!expanded) { content(Modifier.fillMaxWidth().height(height.coerceIn(100, 900).dp)); footer() }
            else Text(uiContext.getString(R.string.ui_preview_expanded), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
    // A large sheet keeps the conversation/composer visible behind it. Follow-up returns to the same composer.
    if (expanded) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.92f).safeDrawingPadding(), shape = RoundedCornerShape(18.dp)) {
            Column {
                controls(true)
                content(Modifier.fillMaxWidth().weight(1f))
                footer()
            }
        }
    }
}
