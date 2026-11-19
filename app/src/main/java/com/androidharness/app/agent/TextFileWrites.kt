package com.androidharness.app.agent

import com.androidharness.app.core.ToolCallData
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Live file writes for models whose server holds a tool call back until it is
 * finished. Those servers still stream reply text, so the model writes the
 * file into its reply as a `file` block and then calls write_file with
 * [MARKER] as the content. The harness hides the block from the reply,
 * shows it in the live code card as it arrives, and fills the call in.
 */
internal object TextFileWrites {
    /** write_file content that means "use the file block I just wrote". */
    const val MARKER = "@file-block"

    /** A whole write delivered in one piece this large means the server buffered it. */
    const val BUFFERED_MIN_CHARS = 400

    /** Opening and closing fence. Five backticks keep ordinary ``` fences inside a file intact. */
    const val FENCE = "`````"

    fun modelKey(providerId: String, model: String) = "$providerId|$model"

    /** Hosts known to deliver a tool call only once it is complete. */
    fun buffersToolCalls(baseUrl: String): Boolean =
        runCatching { java.net.URI(baseUrl.trim()).host.orEmpty().lowercase() }.getOrDefault("")
            .let { it == "nvidia.com" || it.endsWith(".nvidia.com") }

    fun prompt(): String = """

## Writing files on this connection
This provider only delivers a tool call once it is complete, so the contents of a write_file call cannot be shown while you write them. To create or overwrite a file, write its full contents in your reply inside a block that opens with five backticks, the word file and the path, and closes with five backticks on their own line:

${FENCE}file src/example/Main.kt
(the complete file contents)
$FENCE

Then call write_file with that same path and content set to exactly "$MARKER". The harness fills in the block's contents. Write one block per file, right before its write_file call. Small changes still go through edit_file as usual.
"""

    /**
     * The call with [MARKER] replaced by its block's contents, or null when the
     * call does not use the marker or no finished block matches its path.
     */
    fun fill(call: ToolCallData, blocks: FileBlockStream): Pair<ToolCallData, FileBlockStream.Block>? {
        if (call.name != "write_file") return null
        val args = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(call.argumentsJson).jsonObject }.getOrNull() ?: return null
        if (args["content"]?.jsonPrimitive?.contentOrNull?.trim() != MARKER) return null
        val path = args["path"]?.jsonPrimitive?.contentOrNull ?: return null
        blocks.closePendingFence()
        val block = blocks.blocks.lastOrNull { it.closed && !it.consumed && samePath(it.path, path) } ?: return null
        block.consumed = true
        val filled = JsonObject(args + ("content" to JsonPrimitive(block.body.toString())))
        return call.copy(argumentsJson = filled.toString()) to block
    }

    /** A write_file call whose content is only [MARKER]. */
    fun isMarkerCall(call: ToolCallData): Boolean {
        if (call.name != "write_file") return false
        val args = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(call.argumentsJson).jsonObject }.getOrNull() ?: return false
        return args["content"]?.jsonPrimitive?.contentOrNull?.trim() == MARKER
    }

    /** Arguments a live preview can read while the block is still arriving. */
    fun previewArguments(block: FileBlockStream.Block): String {
        val content = JsonPrimitive(block.body.toString()).toString()
        return "{\"path\":${JsonPrimitive(block.path)},\"content\":" +
            (if (block.closed) "$content}" else content.dropLast(1))
    }

    private fun samePath(a: String, b: String) =
        a.trim().removePrefix("./").trimStart('/') == b.trim().removePrefix("./").trimStart('/')
}

/**
 * Splits streamed reply text into what the reader sees and the `file` blocks
 * inside it. Works line by line: a line that could still become a block
 * fence is held back until it is known, everything else passes through as
 * soon as it arrives.
 */
internal class FileBlockStream(private val draftPrefix: String) {
    class Block(val index: Int, val path: String, val draftId: String) {
        val body = StringBuilder()
        var closed = false
        var consumed = false
        /** Last time a live preview was sent, for throttling. */
        var previewAt = 0L
    }

    val blocks = mutableListOf<Block>()
    private val line = StringBuilder()
    private var passing = false
    private var open: Block? = null
    private var fenceLength = 0

    /** Blocks whose contents changed in the last [feed]. */
    val touched = linkedSetOf<Block>()

    fun reset() {
        blocks.clear(); line.setLength(0); passing = false; open = null; fenceLength = 0; touched.clear()
    }

    /** Takes a text delta and returns the part the reader should see now. */
    fun feed(delta: String): String {
        touched.clear()
        val out = StringBuilder()
        for (c in delta) {
            val block = open
            if (c == '\n') {
                if (block == null) {
                    if (passing) out.append('\n')
                    else {
                        val opened = opener(line.toString())
                        if (opened != null) {
                            val next = Block(blocks.size, opened.second, "$draftPrefix-${blocks.size}")
                            blocks += next; open = next; fenceLength = opened.first; touched += next
                        } else out.append(line).append('\n')
                    }
                } else {
                    if (!passing && closes(line.toString())) { block.closed = true; open = null }
                    else { if (!passing) block.body.append(line); block.body.append('\n') }
                    touched += block
                }
                line.setLength(0); passing = false
                continue
            }
            if (passing) {
                if (block == null) out.append(c) else { block.body.append(c); touched += block }
                continue
            }
            line.append(c)
            val stillPossible = if (block == null) couldOpen(line.trimStart().toString()) else line.trim().all { it == '`' }
            if (!stillPossible) {
                passing = true
                if (block == null) out.append(line) else { block.body.append(line); touched += block }
                line.setLength(0)
            }
        }
        return out.toString()
    }

    /** A closing fence often arrives without its newline right before the tool call. */
    fun closePendingFence() {
        val block = open ?: return
        if (!passing && line.isNotEmpty() && closes(line.toString())) {
            block.closed = true; open = null; line.setLength(0); touched += block
        }
    }

    /** Ends the stream; returns any held text the reader has not seen yet. */
    fun finish(): String {
        closePendingFence()
        val held = line.toString()
        line.setLength(0)
        val block = open
        if (block != null) { block.body.append(held); return "" }
        return if (passing) "" else held
    }

    /**
     * Ends the stream and appends to [text] what the reader still has to see:
     * held text, then every block no call used as a plain block. Returns the
     * drafts to drop and the appended text.
     */
    fun drainInto(text: StringBuilder): Pair<List<String>, String> {
        val start = text.length
        text.append(finish())
        val removed = blocks.filter { !it.consumed }.map { block ->
            if (text.isNotEmpty() && !text.endsWith("\n")) text.append('\n')
            text.append(TextFileWrites.FENCE).append('\n').append(block.body)
            if (!block.body.endsWith("\n")) text.append('\n')
            text.append(TextFileWrites.FENCE).append('\n')
            block.draftId
        }
        return removed to text.substring(start)
    }

    private fun closes(text: String): Boolean {
        val t = text.trim()
        return t.length >= fenceLength && t.all { it == '`' }
    }

    private fun couldOpen(t: String): Boolean {
        val ticks = t.takeWhile { it == '`' }.length
        if (ticks == t.length) return true
        if (ticks < 3) return false
        val rest = t.substring(ticks)
        if ("file".startsWith(rest)) return true
        return rest.startsWith("file") && (rest[4] == ' ' || rest[4] == ':')
    }

    /** Fence length and path for a complete opener line, else null. */
    private fun opener(text: String): Pair<Int, String>? {
        val t = text.trim()
        val ticks = t.takeWhile { it == '`' }.length
        if (ticks < 5) return null
        val rest = t.substring(ticks)
        if (!rest.startsWith("file")) return null
        val path = rest.substring(4).trimStart(':', ' ', '\t').trim()
        return if (path.isEmpty()) null else ticks to path
    }
}
