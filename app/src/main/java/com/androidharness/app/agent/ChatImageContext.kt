package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageData
import com.androidharness.app.core.Role

/** Attach recent pictures actually shown in assistant messages to the next Chat request. */
internal object ChatImageContext {
    private val markdownImage = Regex("!\\[([^\\]]*)]\\((https?://(?:[^()\\s]|\\([^()\\s]*\\))+)\\)")

    suspend fun attach(history: List<ChatMessage>, resolve: (com.androidharness.app.core.ImageRef) -> ImageData? = { null }, load: suspend (String) -> ImageData?): List<ChatMessage> {
        val userIndex = history.indexOfLast { it.role == Role.USER }
        if (userIndex < 0) return history
        val available = (ContextHygiene.MAX_CONTEXT_IMAGES - history[userIndex].imageData.size).coerceAtLeast(0)
        if (available == 0) return history
        val inventory = com.androidharness.app.tools.ChatImageInventory.from(history)
        val candidates = com.androidharness.app.tools.ChatImageInventory.from(history.take(userIndex))
            .filter { it.displayRole != "user" }.take(available)
        if (candidates.isEmpty()) return history
        val found = candidates.mapNotNull { source ->
            val data = when (source) {
                is com.androidharness.app.tools.ChatImageSource.Remote -> load(source.url)
                is com.androidharness.app.tools.ChatImageSource.Stored -> resolve(source.ref)
            }
            data?.let { source to it }
        }
        val note = if (found.isEmpty()) "[Pictures shown earlier could not be loaded for visual inspection.]"
            else "[Images attached for context are pictures shown earlier in this chat, in this exact attachment order: " + found.joinToString { (source, _) ->
                "image_id=${source.id}, image_number=${inventory.indexOfFirst { it.id == source.id } + 1}, caption=${source.label}, url=" +
                    ((source as? com.androidharness.app.tools.ChatImageSource.Remote)?.url ?: (source as? com.androidharness.app.tools.ChatImageSource.Stored)?.ref?.sourceUrl.orEmpty())
            } + ". They are not new user uploads. Other listed images are not implicitly attached; inspect their image_id if needed.]"
        return history.toMutableList().also { result ->
            val user = result[userIndex]
            result[userIndex] = user.copy(text = user.text + "\n\n" + note, imageData = user.imageData + found.map { it.second })
        }
    }
}
