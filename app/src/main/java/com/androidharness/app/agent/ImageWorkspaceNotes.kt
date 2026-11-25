package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role

/**
 * Tells the model where Code mode put the full-size copy of each attached image, so it can run
 * tesseract, python or a skill on the file without asking the user to save it first. The note
 * rides only in the model's copy of the message; the chat bubble never shows it.
 */
object ImageWorkspaceNotes {

    fun note(name: String, path: String): String =
        "[Attached image $name saved into the workspace at $path; open that file with shell tools (tesseract, python) when you need to read or process it.]"

    fun annotate(messages: List<ChatMessage>): List<ChatMessage> = messages.map { m ->
        if (m.role != Role.USER) return@map m
        val notes = m.images.mapNotNull { ref ->
            ref.workspacePath?.takeIf { it.isNotBlank() }?.let { note(ref.caption ?: ref.name, it) }
        }.filterNot { m.text.contains(it) }
        if (notes.isEmpty()) m
        else m.copy(text = if (m.text.isBlank()) notes.joinToString("\n") else m.text + "\n\n" + notes.joinToString("\n"))
    }
}
