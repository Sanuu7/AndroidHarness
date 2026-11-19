package com.androidharness.app.agent

import com.androidharness.app.core.ToolCallData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFileWritesTest {
    private val reply = "Writing it now.\n`````file src/Main.kt\nfun main() {\n    println(\"```\")\n}\n`````\nDone.\n"

    private fun feedInPieces(stream: FileBlockStream, text: String, size: Int): String =
        text.chunked(size).joinToString("") { stream.feed(it) } + stream.finish()

    @Test fun blocksAreHiddenFromTheReplyAtAnyChunkSize() {
        for (size in listOf(1, 2, 3, 7, 50, reply.length)) {
            val stream = FileBlockStream("d")
            assertEquals("chunk $size", "Writing it now.\nDone.\n", feedInPieces(stream, reply, size))
            val block = stream.blocks.single()
            assertEquals("src/Main.kt", block.path)
            assertEquals("fun main() {\n    println(\"```\")\n}\n", block.body.toString())
            assertTrue(block.closed)
        }
    }

    @Test fun ordinaryCodeFencesPassThrough() {
        val text = "Example:\n```kotlin\nval x = 1\n```\n```file not-a-block\n"
        val stream = FileBlockStream("d")
        assertEquals(text, feedInPieces(stream, text, 1))
        assertTrue(stream.blocks.isEmpty())
    }

    @Test fun bodyStreamsBeforeTheBlockCloses() {
        val stream = FileBlockStream("d")
        stream.feed("`````file a.txt\nhello wor")
        assertEquals("hello wor", stream.blocks.single().body.toString())
        assertFalse(stream.blocks.single().closed)
        assertTrue(TextFileWrites.previewArguments(stream.blocks.single()).startsWith("{\"path\":\"a.txt\",\"content\":\"hello wor"))
    }

    @Test fun markerCallIsFilledFromTheMatchingBlock() {
        val stream = FileBlockStream("d")
        // The closing fence arrives without its newline, right before the call.
        stream.feed("`````file ./src/Main.kt\nline one\n`````")
        val call = ToolCallData("c1", "write_file", """{"path":"src/Main.kt","content":"@file-block"}""")
        val (filled, block) = TextFileWrites.fill(call, stream)!!
        val args = Json.parseToJsonElement(filled.argumentsJson).jsonObject
        assertEquals("line one\n", args["content"]!!.jsonPrimitive.content)
        assertEquals("src/Main.kt", args["path"]!!.jsonPrimitive.content)
        assertTrue(block.consumed)
        // A block is used once.
        assertNull(TextFileWrites.fill(call.copy(id = "c2"), stream))
    }

    @Test fun normalCallsAndMissingBlocksAreLeftAlone() {
        val stream = FileBlockStream("d")
        stream.feed("`````file a.txt\nx\n`````\n")
        assertNull(TextFileWrites.fill(ToolCallData("c", "write_file", """{"path":"a.txt","content":"real"}"""), stream))
        assertNull(TextFileWrites.fill(ToolCallData("c", "write_file", """{"path":"b.txt","content":"@file-block"}"""), stream))
        assertNull(TextFileWrites.fill(ToolCallData("c", "edit_file", """{"path":"a.txt"}"""), stream))
    }

    @Test fun unclosedBlockKeepsItsTextOnFinish() {
        val stream = FileBlockStream("d")
        stream.feed("`````file a.txt\npartial")
        assertEquals("", stream.finish())
        assertEquals("partial", stream.blocks.single().body.toString())
        assertFalse(stream.blocks.single().closed)
    }

    @Test fun markerCallsAreRecognised() {
        assertTrue(TextFileWrites.isMarkerCall(ToolCallData("c", "write_file", """{"path":"a","content":" @file-block "}""")))
        assertFalse(TextFileWrites.isMarkerCall(ToolCallData("c", "write_file", """{"path":"a","content":"real"}""")))
        assertFalse(TextFileWrites.isMarkerCall(ToolCallData("c", "edit_file", """{"path":"a","content":"@file-block"}""")))
    }

    @Test fun nvidiaHostsUseFileBlocksFromTheStart() {
        assertTrue(TextFileWrites.buffersToolCalls("https://integrate.api.nvidia.com/v1"))
        assertTrue(TextFileWrites.buffersToolCalls("https://nvidia.com/v1"))
        assertFalse(TextFileWrites.buffersToolCalls("https://api.openai.com/v1"))
        assertFalse(TextFileWrites.buffersToolCalls("https://notnvidia.com/v1"))
        assertFalse(TextFileWrites.buffersToolCalls("not a url"))
    }

    @Test fun unusedBlocksStayVisibleWhenDrained() {
        val stream = FileBlockStream("d")
        val text = StringBuilder(stream.feed("Here:\n`````file a.txt\nbody"))
        val (removed, shown) = stream.drainInto(text)
        assertEquals(listOf("d-0"), removed)
        assertEquals("`````\nbody\n`````\n", shown)
        assertEquals("Here:\n`````\nbody\n`````\n", text.toString())
    }

    @Test fun stallWindowsFormatLikeThePicker() {
        assertEquals("90s", StreamRetrier.formatStall(90_000))
        assertEquals("5 min", StreamRetrier.formatStall(300_000))
        assertEquals("2 min 30s", StreamRetrier.formatStall(150_000))
        assertEquals("45s", StreamRetrier.formatStall(45_000))
        assertEquals(StreamRetrier.STALLED_STREAM_MESSAGE, StreamStalledException(90_000).message)
        assertTrue(StreamRetrier.isStallFailure(StreamStalledException(300_000).message))
        assertEquals(30_000L, StreamRetrier.stallTimeoutMs(5))
        assertEquals(900_000L, StreamRetrier.stallTimeoutMs(StreamRetrier.DEFAULT_STALL_TIMEOUT_SECONDS))
        assertEquals(StreamRetrier.NO_STALL_LIMIT_MS, StreamRetrier.stallTimeoutMs(StreamRetrier.STALL_TIMEOUT_NEVER))
    }
}
