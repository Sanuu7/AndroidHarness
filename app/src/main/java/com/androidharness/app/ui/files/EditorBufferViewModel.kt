package com.androidharness.app.ui.files

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

/** Keeps the active editor responsive across rotation; EditorDraftStore handles durable recovery. */
internal class EditorBufferViewModel : ViewModel() {
    private var owner: String? = null
    var buffer = EditorBuffer()
        private set

    fun acquire(key: String): EditorBuffer {
        if (owner != key) { owner = key; buffer = EditorBuffer() }
        return buffer
    }
}

internal class EditorBuffer {
    var document: io.github.rosemoe.sora.text.Content? = null
    var selectionStart = 0
    var selectionEnd = 0
    var offsetX = 0
    var offsetY = 0
    val text = mutableStateOf<String?>(null)
    val baseline = mutableStateOf("")
    val saved = mutableStateOf("")
    val editing = mutableStateOf(false)
    /** Markdown view mode the user picked for this file; null until they choose. */
    val markdownPreview = mutableStateOf<Boolean?>(null)
    val dirty = mutableStateOf(false)
    val saving = mutableStateOf(false)
    val decoded = mutableStateOf<EditorFileCodec.Decoded?>(null)
    val diskVersion = mutableStateOf<String?>(null)
    var loaded = false
    val recovered = mutableStateOf(false)

    fun restore(draft: EditorDraft) {
        text.value = draft.text
        baseline.value = draft.baseline
        saved.value = draft.saved
        decoded.value = draft.decoded()
        diskVersion.value = draft.diskVersion
        editing.value = draft.editing
        dirty.value = draft.text != draft.saved
        recovered.value = dirty.value
        loaded = true
    }

    fun draft(): EditorDraft? {
        val current = text.value ?: return null
        val source = decoded.value ?: return null
        val version = diskVersion.value ?: return null
        if (current == saved.value) return null
        return EditorDraft(text = current, baseline = baseline.value, saved = saved.value, diskVersion = version,
            charset = source.charset.name(), bom = source.bom, eol = source.eol,
            missingTrailingNewline = source.missingTrailingNewline, editing = editing.value)
    }
}
