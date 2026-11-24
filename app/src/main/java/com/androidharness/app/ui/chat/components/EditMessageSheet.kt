package com.androidharness.app.ui.chat.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.core.ImageRef
import com.androidharness.app.ui.chat.MessageEditDraft
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.FilledTonalIconButton
import com.androidharness.app.ui.common.TextButton
import java.io.File

/**
 * Edits a sent message before it is resent: its text, plus photos and files
 * that can be removed or added here the same way the composer takes them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditMessageSheet(
    draft: MessageEditDraft,
    imageDir: File,
    /** The model's image capability in Chat (null when unknown); Code always passes true. */
    imageSupport: Boolean?,
    imagesAsText: Boolean,
    canResend: Boolean,
    onTextChange: (String) -> Unit,
    onPickImages: (List<Uri>) -> Unit,
    onPickFiles: (List<Uri>) -> Unit,
    onRemoveImage: (Int) -> Unit,
    onRemoveFile: (Int) -> Unit,
    onRemoveImport: (String) -> Unit,
    onOpenImage: (List<ImageRef>, Int) -> Unit,
    onResend: () -> Unit,
    onCancel: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(), onPickImages)
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents(), onPickFiles)
    val scheme = MaterialTheme.colorScheme
    val images = draft.images
    val files = draft.trayFiles

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        containerColor = scheme.surfaceContainerLow,
        modifier = Modifier.testTag("edit-message-sheet"),
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(bottom = 16.dp)) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(stringResource(R.string.ui_edit_message), style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface)
                Text(
                    stringResource(R.string.edit_message_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            AnimatedVisibility(images.isNotEmpty() || files.isNotEmpty() || draft.imports.isNotEmpty()) {
                Column(Modifier.padding(top = 12.dp)) {
                    ComposerAttachmentTray(
                        images = images.map { it.name },
                        files = files,
                        imports = draft.imports,
                        imageDir = imageDir,
                        imageSupport = imageSupport,
                        imagesAsText = imagesAsText,
                        onRemoveImage = onRemoveImage,
                        onRemoveFile = onRemoveFile,
                        onRemoveImport = onRemoveImport,
                        onOpenImage = { _, index -> onOpenImage(images, index) },
                        horizontalPadding = 20.dp,
                    )
                }
            }

            TextField(
                value = draft.text,
                onValueChange = onTextChange,
                placeholder = { Text(stringResource(R.string.ui_message)) },
                minLines = 3,
                maxLines = 10,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = scheme.surfaceContainerHighest,
                    unfocusedContainerColor = scheme.surfaceContainerHighest,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                    .heightIn(min = 96.dp)
                    .testTag("edit-message-text"),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
            ) {
                val addPhoto = stringResource(R.string.edit_message_add_photo)
                val addFile = stringResource(R.string.edit_message_add_file)
                FilledTonalIconButton(
                    onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.size(48.dp).testTag("edit-message-add-photo"),
                ) { Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = addPhoto) }
                FilledTonalIconButton(
                    onClick = { filePicker.launch("*/*") },
                    modifier = Modifier.size(48.dp).testTag("edit-message-add-file"),
                ) { Icon(Icons.Outlined.AttachFile, contentDescription = addFile) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_action_cancel)) }
                Button(
                    onClick = onResend,
                    enabled = canResend,
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("edit-message-resend"),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.edit_message_resend))
                }
            }
        }
    }
}
