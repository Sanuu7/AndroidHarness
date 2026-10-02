package com.androidharness.app.chatgpt

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.llm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ChatGptProvider(
    private val token: suspend (String, Boolean) -> String,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).followRedirects(false).build(),
    private val modelEntry: (String, String) -> ModelEntry? = { _, _ -> null },
) : LlmProvider {
    /** Diagnostic type only, never credentials, request bodies or exception messages. */
    @Volatile internal var lastFailureType: String? = null
        private set
    private val responses = OpenAiResponsesProvider(client, ProviderFactory.json)
    private class StreamFinished : RuntimeException(null, null, false, false)

    internal fun body(config: ProviderConfig, system: String, messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions): JsonObject {
        val body = responses.buildRequestBody(config, system, messages, tools, options).toMutableMap()
        body.remove("max_output_tokens")
        body.remove("user")
        body.remove("reasoning")
        ChatGptThinking.effort(modelEntry(config.id, config.model) ?: ModelEntry(config.model), options.thinking)?.let { effort ->
            body["reasoning"] = buildJsonObject {
                put("effort", effort)
                if (effort != "none") put("summary", "auto")
            }
        }
        body["include"] = JsonArray(listOf(JsonPrimitive("reasoning.encrypted_content")))
        val replayReasoning = messages.flatMap { it.toolCalls }.filter { it.responseProviderId == config.id }.associate { it.id to it.responseReasoning }
        body["input"] = JsonArray(body.getValue("input").jsonArray.flatMap { item ->
            val obj = item.jsonObject
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "function_call") {
                replayReasoning[obj["call_id"]?.jsonPrimitive?.contentOrNull].orEmpty() +
                    JsonObject(obj + ("namespace" to JsonPrimitive(ChatGptProtocol.NAMESPACE)))
            } else listOf(obj)
        })
        if (tools.isNotEmpty()) body["tools"] = buildJsonArray {
            add(buildJsonObject {
                put("type", "namespace"); put("name", ChatGptProtocol.NAMESPACE)
                put("description", "AndroidHarness tools for coding and working with the user's project.")
                put("tools", body.getValue("tools"))
            })
        }
        return JsonObject(body)
    }

    override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String, messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions): Flow<StreamEvent> = flow {
        val requestBody = body(config, systemPrompt, messages, tools, options)
        var retry = false
        while (true) {
            val acc = LinkedHashMap<String, Triple<String, String, StringBuilder>>()
            var completed = false
            var failed = false
            var received = false
            try {
                val access = token(config.id, retry)
                // Never send an account token to an editable provider URL.
                val request = Request.Builder().url(ChatGptProtocol.RESOURCE + "/responses")
                    .header("Authorization", "Bearer $access")
                    .post(requestBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                try { ProviderFactory.sseJson(request, client).collect { element ->
                    if (completed || failed) return@collect
                    received = true
                    val event = element as? JsonObject ?: return@collect
                    val type = event["type"]?.jsonPrimitive?.contentOrNull
                    val error = event["error"] as? JsonObject ?: (event["response"] as? JsonObject)?.get("error") as? JsonObject
                    var reasoningItems: List<JsonObject> = emptyList()
                    when {
                        error != null || type in setOf("error", "response.failed", "response.incomplete") -> {
                            failed = true; acc.clear()
                            emit(StreamEvent.Failure(ChatGptProtocol.requestError(error?.get("code")?.jsonPrimitive?.contentOrNull)))
                        }
                        else -> {
                            val item = event["item"] as? JsonObject
                            val namespace = item?.get("namespace")?.jsonPrimitive?.contentOrNull
                            if (item?.get("type")?.jsonPrimitive?.contentOrNull == "function_call" && namespace != null && namespace != ChatGptProtocol.NAMESPACE) {
                                failed = true; acc.clear(); emit(StreamEvent.Failure("ChatGPT returned an unknown tool namespace."))
                            } else {
                                if (type == "response.completed") {
                                    val response = event["response"] as? JsonObject
                                    if (response?.get("status")?.jsonPrimitive?.contentOrNull != "completed") {
                                        failed = true; emit(StreamEvent.Failure("ChatGPT did not complete this response.")); throw StreamFinished()
                                    }
                                    val output = response["output"] as? JsonArray
                                    reasoningItems = output?.mapNotNull { it as? JsonObject }?.filter {
                                        it["type"]?.jsonPrimitive?.contentOrNull == "reasoning" &&
                                            !it["encrypted_content"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
                                    }.orEmpty()
                                    // Completed output is authoritative, including calls without earlier argument deltas.
                                    if (output != null) {
                                        acc.clear()
                                        output.mapNotNull { it as? JsonObject }.filter { it["type"]?.jsonPrimitive?.contentOrNull == "function_call" }.forEach { call ->
                                            val ns = call["namespace"]?.jsonPrimitive?.contentOrNull
                                            val id = call["call_id"]?.jsonPrimitive?.contentOrNull
                                            val name = call["name"]?.jsonPrimitive?.contentOrNull
                                            val args = call["arguments"]?.jsonPrimitive?.contentOrNull
                                            if ((ns != null && ns != ChatGptProtocol.NAMESPACE) || id.isNullOrBlank() || tools.none { it.name == name } ||
                                                args == null || runCatching { ProviderFactory.json.parseToJsonElement(args) as? JsonObject }.getOrNull() == null) {
                                                failed = true; acc.clear(); emit(StreamEvent.Failure("ChatGPT returned an invalid tool call.")); throw StreamFinished()
                                            }
                                            acc[id] = Triple(id, name!!, StringBuilder(args))
                                        }
                                    }
                                    completed = true
                                }
                                // Tools are released only after successful completion, never from an interrupted stream.
                                responses.parseEvent(element, acc).forEach { parsed -> emit(when (parsed) {
                                    is StreamEvent.ToolCallReady -> parsed.copy(call = parsed.call.copy(responseReasoning = reasoningItems, responseProviderId = config.id.takeIf { reasoningItems.isNotEmpty() }))
                                    is StreamEvent.ToolCallBatch -> parsed.copy(calls = parsed.calls.mapIndexed { index, call ->
                                        if (index == 0) call.copy(responseReasoning = reasoningItems, responseProviderId = config.id.takeIf { reasoningItems.isNotEmpty() }) else call
                                    })
                                    else -> parsed
                                }) }
                            }
                        }
                    }
                    if (completed || failed) throw StreamFinished()
                } } catch (_: StreamFinished) { /* Cancel the SSE reader as soon as the terminal event arrives. */ }
                if (!completed && !failed) emit(StreamEvent.Failure("ChatGPT disconnected before finishing. Please retry."))
                break
            } catch (e: CancellationException) { throw e
            } catch (e: ApiException) {
                if (e.code == 401 && !retry && !received) { retry = true; continue }
                val code = runCatching { ProviderFactory.json.parseToJsonElement(e.message.orEmpty().substringAfter(": ")).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull }.getOrNull()
                emit(StreamEvent.Failure(ChatGptProtocol.requestError(code, e.code))); break
            } catch (e: Exception) {
                lastFailureType = e.javaClass.simpleName
                emit(StreamEvent.Failure("ChatGPT could not finish. Check your connection or reconnect in Settings > Connected accounts.")); break
            }
        }
    }
}
