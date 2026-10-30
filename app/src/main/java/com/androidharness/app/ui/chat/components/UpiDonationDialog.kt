package com.androidharness.app.ui.chat.components

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextDirection
import androidx.core.content.ContextCompat
import com.androidharness.app.R
import com.androidharness.app.ui.common.ExpressiveIconButton
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun UpiDonationRow() {
    var open by rememberSaveable { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.donate_upi_title)) { open = true }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(scheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_upi), null, Modifier.size(width = 18.dp, height = 26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.donate_upi_title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
    }
    if (open) UpiDonationDialog { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpiDonationDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showQr by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val bodyHeight = LocalConfiguration.current.screenHeightDp.dp * .65f
    val qrSize = (LocalConfiguration.current.screenHeightDp.dp * .4f / LocalDensity.current.fontScale.coerceAtLeast(1f))
        .coerceIn(120.dp, 240.dp)
    fun notify(message: Int) = Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            try {
                UpiDonation.saveQrToGallery(context)
                notify(R.string.donate_upi_qr_saved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("UpiDonation", "Could not publish donation QR to gallery", error)
                notify(R.string.donate_upi_save_failed)
            } finally {
                saving = false
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save() else notify(R.string.donate_upi_storage_required)
    }
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp - 48.dp),
            shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(R.drawable.ic_upi), null, Modifier.size(width = 22.dp, height = 30.dp))
                    Text(stringResource(R.string.donate_upi_title), style = MaterialTheme.typography.headlineSmall)
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = bodyHeight).verticalScroll(rememberScrollState())
                        .animateContentSize(animationSpec = expressiveSpatialSpec()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            SelectionContainer(Modifier.weight(1f)) {
                                Text(UpiDonation.UPI_ID, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr))
                            }
                            ExpressiveIconButton(onClick = {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("UPI ID", UpiDonation.UPI_ID))
                                notify(R.string.donate_upi_copied)
                            }) {
                                Icon(Icons.Outlined.ContentCopy, stringResource(R.string.donate_upi_copy_id))
                            }
                        }
                    }
                    OutlinedButton(onClick = { showQr = !showQr }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.QrCode2, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (showQr) R.string.donate_upi_hide_qr else R.string.donate_upi_show_qr))
                    }
                    AnimatedVisibility(showQr,
                        enter = expandVertically(animationSpec = expressiveSpatialSpec()) + fadeIn(animationSpec = expressiveEffectsSpec()),
                        exit = shrinkVertically(animationSpec = expressiveSpatialSpec()) + fadeOut(animationSpec = expressiveEffectsSpec())) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            // Keep QR colors independent of the app theme and leave a quiet zone for scanners.
                            Box(Modifier.widthIn(max = qrSize).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black).padding(16.dp)) {
                                Image(painterResource(R.drawable.donate_upi_qr), stringResource(R.string.donate_upi_qr_description),
                                    Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Fit)
                            }
                        }
                    }
                }
                if (showQr) {
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                            permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else save()
                    }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (saving) R.string.donate_upi_saving_qr else R.string.donate_upi_save_qr))
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.settings_action_close))
                }
            }
        }
    }
}
