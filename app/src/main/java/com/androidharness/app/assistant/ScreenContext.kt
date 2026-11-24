package com.androidharness.app.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.graphics.Bitmap
import android.text.InputType
import android.view.View

/** What Android shared about the screen behind the panel. Either part can be missing. */
internal data class ScreenSnapshot(
    val appName: String? = null,
    val packageName: String? = null,
    val webUri: String? = null,
    val text: String? = null,
    val screenshot: Bitmap? = null,
) {
    val hasContent: Boolean get() = !text.isNullOrBlank() || screenshot != null
}

/** Turns the raw view tree into a short, readable text block for the model. */
internal object ScreenText {
    const val MAX_CHARS = 6_000
    private val spaces = Regex("[ \\t\\u00A0]+")
    private val blankLines = Regex("\\n{3,}")

    /** Trims each piece, drops empties and repeats, and stops at [max] characters. */
    fun compact(pieces: Sequence<String>, max: Int = MAX_CHARS): String {
        val seen = HashSet<String>()
        val out = StringBuilder()
        for (raw in pieces) {
            val line = raw.replace(spaces, " ").replace(blankLines, "\n\n").trim()
            if (line.isEmpty() || !seen.add(line.lowercase())) continue
            if (out.isNotEmpty()) out.append('\n')
            if (out.length + line.length > max) {
                out.append(line.take((max - out.length).coerceAtLeast(0)).trimEnd()).append(" [cut]")
                break
            }
            out.append(line)
        }
        return out.toString()
    }

    /** The block the model reads; the chat shows it as a file chip. */
    fun describe(snapshot: ScreenSnapshot, note: String): String = buildString {
        appendLine(note)
        val app = listOfNotNull(snapshot.appName, snapshot.packageName?.takeIf { it != snapshot.appName })
        if (app.isNotEmpty()) appendLine("App: " + if (app.size == 2) "${app[0]} (${app[1]})" else app[0])
        snapshot.webUri?.let { appendLine("Page: $it") }
        snapshot.text?.takeIf { it.isNotBlank() }?.let { appendLine(); append(it) }
    }.trimEnd()

    /** Reads visible text in screen order, skipping password fields. */
    fun fromStructure(structure: AssistStructure): String {
        val pieces = sequence {
            for (w in 0 until structure.windowNodeCount) {
                val stack = ArrayDeque<AssistStructure.ViewNode>()
                stack.addLast(structure.getWindowNodeAt(w).rootViewNode ?: continue)
                while (stack.isNotEmpty()) {
                    val node = stack.removeLast()
                    if (node.visibility != View.VISIBLE) continue
                    if (!isPassword(node)) {
                        val text = node.text?.toString()
                        if (!text.isNullOrBlank()) yield(text)
                        else node.contentDescription?.toString()?.let { yield(it) }
                    }
                    for (i in node.childCount - 1 downTo 0) stack.addLast(node.getChildAt(i))
                }
            }
        }
        return compact(pieces)
    }

    private fun isPassword(node: AssistStructure.ViewNode): Boolean {
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        val klass = node.inputType and InputType.TYPE_MASK_CLASS
        return klass == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
            klass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    fun appName(context: Context, packageName: String?): String? {
        packageName ?: return null
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()
    }

    fun webUri(content: AssistContent?): String? = content?.webUri?.toString()?.takeIf { it.startsWith("http") }
}
