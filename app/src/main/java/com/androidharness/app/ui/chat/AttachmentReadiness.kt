package com.androidharness.app.ui.chat

import com.androidharness.app.llm.ModelCapabilities
import com.androidharness.app.llm.ModelEntry

enum class AttachmentImportStatus { READING, FAILED, TOO_LARGE, UNSUPPORTED }
data class AttachmentImport(val id: String, val name: String, val status: AttachmentImportStatus = AttachmentImportStatus.READING, val detail: String? = null)

internal fun ChatUiState.imageInputSupport(): Boolean? {
    val provider = activeProvider ?: return null
    val entry = catalogs[provider.id].orEmpty().firstOrNull { it.id == effectiveModel } ?: ModelEntry(effectiveModel.orEmpty())
    val capability = ModelCapabilities.of(entry, provider).images
    return if (capability.source in setOf(ModelCapabilities.Source.PROVIDER, ModelCapabilities.Source.CUSTOM)) capability.supported else null
}

/**
 * A sent message open in the editor. Kept files are the message's original
 * blocks, resent verbatim; added files and photos were picked during this
 * edit and are cleaned up if it is abandoned.
 */
data class MessageEditDraft(
    val message: com.androidharness.app.core.ChatMessage,
    val text: String,
    val images: List<com.androidharness.app.core.ImageRef>,
    val keptFiles: List<FileAttachments.SentFile>,
    val addedFiles: List<FileAttachment> = emptyList(),
    val imports: List<AttachmentImport> = emptyList(),
) {
    /** Kept files first, then added ones, as the attachment tray lists them. */
    val trayFiles: List<FileAttachment> get() =
        keptFiles.map { FileAttachment(it.block.name, it.block.mime, it.block.sizeLabel, null, null) } + addedFiles

    /** The message text to resend, file blocks included, without its hidden context wrappers. */
    fun visibleMessage(): String =
        FileAttachments.rejoin(text.trim(), keptFiles.map { it.raw } + addedFiles.map(FileAttachments::block))

    val hasContent: Boolean get() = text.isNotBlank() || images.isNotEmpty() || keptFiles.isNotEmpty() || addedFiles.isNotEmpty()
}
