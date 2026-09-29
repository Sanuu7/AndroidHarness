package com.androidharness.app.local

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.llm.LlmProvider
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.RequestOptions
import com.androidharness.app.llm.StreamEvent
import com.androidharness.app.llm.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

class LocalModelProvider(private val manager: LocalModelManager) : LlmProvider {
    companion object {
        const val CHAT_PROMPT = "You are a helpful local assistant running on the user's Android device. Answer in text. You cannot execute tools, browse, edit files, or run commands. Do not claim to have performed actions. Give code or instructions when asked."
    }
    override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
        messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions): Flow<StreamEvent> = flow {
        try {
            val id = config.id.removePrefix(LocalModelCatalog.PROVIDER_PREFIX)
            check(config.model == config.id) { "Choose this provider's installed model. Other model IDs cannot run locally." }
            check(messages.none { it.images.isNotEmpty() || it.imageData.isNotEmpty() }) { "Local models support text only. Remove image attachments." }
            val localSystem = if (tools.isEmpty()) systemPrompt else CHAT_PROMPT
            val history = listOf(ChatMessage(Role.SYSTEM, localSystem)) + messages.map {
                when {
                    it.role == Role.TOOL -> ChatMessage(Role.USER, "Previous tool result (${it.toolName.orEmpty()}):\n${it.text}")
                    it.toolCalls.isNotEmpty() -> it.copy(text = it.text + "\nPrevious tool calls: " + it.toolCalls.joinToString { call -> call.name }, toolCalls = emptyList())
                    else -> it
                }
            }
            check(history.sumOf { it.text.length.toLong() } <= 2_000_000) { "Chat is too large for local inference. Start a new chat." }
            val decoder = Utf8TokenDecoder()
            val counts = manager.generate(id, history.map { it.role.name.lowercase() }.toTypedArray(),
                history.map { it.text.toByteArray(Charsets.UTF_8) }.toTypedArray(), options.maxOutputTokens) { bytes ->
                decoder.append(bytes).takeIf { it.isNotEmpty() }?.let { emit(StreamEvent.TextDelta(it)) }
            }
            decoder.finish().takeIf { it.isNotEmpty() }?.let { emit(StreamEvent.TextDelta(it)) }
            emit(StreamEvent.Usage(counts[0], counts[1]))
            emit(StreamEvent.Done(if (counts[2] == 0) "stop" else "length"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(StreamEvent.Failure(e.message ?: "Local inference failed"))
        } catch (_: UnsatisfiedLinkError) {
            emit(StreamEvent.Failure("Local inference is unavailable for this device architecture."))
        }
    }
}

internal class Utf8TokenDecoder {
    private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)
    fun append(bytes: ByteArray): String = decode(bytes, false)
    fun finish(): String = decode(ByteArray(0), true)
    private fun decode(bytes: ByteArray, end: Boolean): String {
        val input = ByteBuffer.wrap(pending + bytes)
        val output = CharBuffer.allocate(input.remaining() + 2)
        decoder.decode(input, output, end)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        if (end) decoder.flush(output)
        output.flip()
        return output.toString()
    }
}
