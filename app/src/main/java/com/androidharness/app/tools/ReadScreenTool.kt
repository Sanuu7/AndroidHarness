package com.androidharness.app.tools

import com.androidharness.app.core.ImageRef
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * The screen the user called the assistant over. The assistant keeps it here for its chat, and
 * the model only receives it through [ReadScreenTool] when the question is about that screen.
 *
 * Android can deliver the screen a moment after the user sends a quick first question, so the
 * screen is loaded the first time the model asks for it.
 */
class SharedScreen(private val load: suspend () -> Content?) {
    class Content(
        /** The app, page and visible text, ready for the model. */
        val text: String,
        /** Saves the screenshot for the model; null when there is none. */
        val saveShot: (suspend () -> ImageRef?)?,
        /** Reads the text in the screenshot on the device, for models that cannot see it. Null when off or unavailable. */
        val readShot: (suspend () -> String?)? = null,
    )

    private val lock = Mutex()
    private var loaded = false
    private var content: Content? = null
    private var shotSaved = false
    private var shot: ImageRef? = null
    private var shotRead = false
    private var shotText: String? = null

    suspend fun content(): Content? = lock.withLock {
        if (!loaded) { content = load(); loaded = true }
        content
    }

    suspend fun screenshot(): ImageRef? {
        val saver = content()?.saveShot ?: return null
        return lock.withLock {
            if (!shotSaved) { shot = saver(); shotSaved = true }
            shot
        }
    }

    /** The screenshot's text, read once. Null when reading is off, failed or there is no screenshot. */
    suspend fun screenshotText(): String? {
        val reader = content()?.readShot ?: return null
        return lock.withLock {
            if (!shotRead) { shotText = reader(); shotRead = true }
            shotText
        }
    }
}

/** One assistant panel shares one screen at a time, so only the newest chat keeps one. */
object SharedScreens {
    @Volatile private var current: Pair<String, SharedScreen>? = null

    fun put(sessionId: String, screen: SharedScreen) { current = sessionId to screen }
    fun get(sessionId: String?): SharedScreen? = current?.takeIf { it.first == sessionId }?.second
    fun clear(sessionId: String) { if (current?.first == sessionId) current = null }
}

class ReadScreenTool : Tool {
    override val name = NAME
    override val description = "See the screen the user opened the assistant over: the app, its visible text and, when " +
        "available, a screenshot. Call it whenever the user's message is about what is on their screen, such as " +
        "\"what's this\", \"summarize this\", \"help me reply\", \"what does this say\" or a question about something " +
        "shown there. Do not tell the user you cannot see their screen without calling it first."
    override val parametersSchema = Schema.obj(emptyMap())
    override val isReadOnly = true

    override fun isAvailable(ctx: ToolContext): Boolean = SharedScreens.get(ctx.sessionId) != null

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val content = SharedScreens.get(ctx.sessionId)?.content()
            ?: return ToolResult(false, "Android did not share this screen with the assistant. The app may block it, " +
                "or Use text from screen and Use screenshot may be off in Android's digital assistant settings.")
        val shared = SharedScreens.get(ctx.sessionId)
        val image = if (ctx.imageInputAllowed && content.saveShot != null) shared?.screenshot() else null
        val read = if (image == null && content.saveShot != null) shared?.screenshotText() else null
        val note = when {
            image != null -> "The screenshot is attached."
            read != null -> "This model cannot see images, so the screenshot's text was read on the device instead.\n\n" +
                screenshotTextBlock(read)
            content.saveShot != null -> "This model cannot see images, so only the text is included."
            else -> "No screenshot is available (the app blocks it or Use screenshot is off), so only the text is included."
        }
        return ToolResult(true, content.text + "\n\n" + note, image = image)
    }

    companion object {
        const val NAME = "read_screen"

        /** The screenshot's text for a model that cannot see it. */
        fun screenshotTextBlock(text: String): String =
            if (text.isBlank()) "[No text was found in the screenshot.]"
            else "[Text read from the screenshot. Layout, pictures and colors are not included.]\n$text"
    }
}
