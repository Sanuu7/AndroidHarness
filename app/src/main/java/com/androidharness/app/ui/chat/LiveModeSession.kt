package com.androidharness.app.ui.chat

import androidx.compose.runtime.Immutable
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.agent.LiveExchange
import com.androidharness.app.agent.LiveMode
import com.androidharness.app.agent.LiveTranscript
import com.androidharness.app.agent.SideContext
import com.androidharness.app.agent.SideEvent
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.core.response.RichResponse
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.LiveModeAccess
import com.androidharness.app.data.audio.SpeechText
import com.androidharness.app.data.audio.TtsPhase
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.effectiveApiKey
import com.androidharness.app.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class LivePhase { STARTING, LISTENING, THINKING, SPEAKING, PAUSED }

@Immutable
data class LiveUi(
    val phase: LivePhase = LivePhase.STARTING,
    val exchanges: List<LiveExchange> = emptyList(),
    /** What the user just said, while the reply is on its way. */
    val asking: String? = null,
    val streaming: String = "",
    /** Name of the tool the live model is using right now. */
    val tool: String? = null,
    val model: String = "",
    val access: LiveModeAccess = LiveModeAccess.READ,
    val error: String? = null,
    val notice: String? = null,
)

/**
 * One Live Mode conversation on a chat. Listening, the live model, speech
 * output and saving the transcript all run here; the overlay only renders
 * [state] and the listener's levels.
 */
class LiveModeSession(
    private val c: AppContainer,
    private val scope: CoroutineScope,
    private val sessionId: String,
    /** The chat's own model, used when Live Mode has no model of its own. */
    private val chatProvider: () -> ProviderConfig?,
    private val maxContextTokens: Int,
    /** Sends a message to the main agent exactly as the composer would. */
    private val sendToAgent: (String) -> Boolean,
) {
    private val liveId = UUID.randomUUID().toString()
    private val _state = MutableStateFlow(LiveUi())
    val state: StateFlow<LiveUi> = _state.asStateFlow()

    val listener = LiveListener(c.appContext, scope, groqKey = { c.keys.groqApiKey() })

    private var settings = AppSettings()
    private var context: SideContext? = null
    private var transcript: String? = null
    /** The live model's own conversation, tool rounds included. */
    private val conversation = mutableListOf<ChatMessage>()
    private var turn: Job? = null
    private var replyCount = 0
    private var closed = false

    fun start() {
        scope.launch {
            try {
                settings = c.settings.settings.first()
                val live = settings.liveMode
                val provider = resolveProvider(live.providerId, live.model)
                val key = withContext(Dispatchers.IO) {
                    if (provider.id == com.androidharness.app.llm.HarnessProvider.ID) c.providers.harnessApiKey()
                    else provider.effectiveApiKey(c.providers.apiKey(provider.id))
                }
                check(key.isNotBlank() || com.androidharness.app.local.LocalModelCatalog.isLocal(provider.id)) {
                    c.appContext.getString(R.string.btw_error_no_key)
                }
                val (ctx, chat) = c.runManager.liveContext(sessionId, provider, key, maxContextTokens, live.access)
                if (closed) return@launch
                context = ctx
                transcript = chat
                _state.update { it.copy(model = ctx.config.model, access = live.access) }
                listen()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = LivePhase.PAUSED, error = e.message ?: c.appContext.getString(R.string.live_error_generic)) }
            }
        }
    }

    private suspend fun resolveProvider(providerId: String?, model: String?): ProviderConfig {
        if (providerId != null) {
            val saved = c.providers.providers.first().firstOrNull { it.id == providerId }
                ?: error(c.appContext.getString(R.string.live_error_model_missing))
            return saved.copy(model = model?.takeIf { it.isNotBlank() } ?: saved.model)
        }
        return chatProvider() ?: error(c.appContext.getString(R.string.ui_no_provider_configured_add_one_from_the))
    }

    private fun listen() {
        if (closed || context == null) return
        _state.update { it.copy(phase = LivePhase.LISTENING) }
        listener.start(
            useGroq = settings.voiceEngine == AppSettings.VOICE_ENGINE_GROQ,
            model = settings.groqWhisperModel,
            silence = settings.liveMode.silenceMs,
            onUtterance = ::answer,
        )
    }

    /** Microphone back on after a reply, a stop or a pause. */
    fun resume() {
        if (closed) return
        if (context == null) { _state.update { it.copy(error = null, phase = LivePhase.STARTING) }; start(); return }
        turn?.cancel()
        c.tts.stop()
        _state.update { it.copy(phase = LivePhase.LISTENING, asking = null, streaming = "", tool = null, error = null) }
        listener.resume()
    }

    fun pause() {
        turn?.cancel()
        c.tts.stop()
        listener.pause()
        _state.update { it.copy(phase = LivePhase.PAUSED, asking = null, streaming = "", tool = null) }
    }

    /** Tapping the orb: stop talking or thinking and listen, or start listening when paused. */
    fun interrupt() {
        when (_state.value.phase) {
            LivePhase.LISTENING -> pause()
            LivePhase.STARTING -> Unit
            else -> resume()
        }
    }

    private fun answer(text: String) {
        val ctx = context ?: return
        // An interrupted turn left no reply; join the words so the request keeps alternating roles.
        val last = conversation.lastOrNull()
        if (last?.role == Role.USER) conversation[conversation.lastIndex] = last.copy(text = last.text + "\n\n" + text)
        else conversation += ChatMessage(role = Role.USER, text = text)
        val base = conversation.size
        _state.update { it.copy(phase = LivePhase.THINKING, asking = text, streaming = "", tool = null, error = null, notice = null) }
        turn = scope.launch {
            var reply = ""
            var failure: String? = null
            try {
                c.runManager.askLive(ctx, LiveMode.requestMessages(transcript, conversation), ::intercept).collect { event ->
                    when (event) {
                        is SideEvent.Text -> _state.update { it.copy(streaming = it.streaming + event.text) }
                        is SideEvent.AssistantCommitted -> {
                            conversation += event.message
                            if (event.message.toolCalls.isEmpty()) reply = event.message.text.trim()
                            _state.update { it.copy(streaming = "") }
                        }
                        is SideEvent.ToolStarted -> _state.update { it.copy(tool = event.call.name) }
                        is SideEvent.ToolFinished -> {
                            conversation += event.message
                            _state.update { it.copy(tool = null) }
                        }
                        is SideEvent.Failed -> failure = event.message
                        is SideEvent.Finished -> if (reply.isEmpty()) failure = event.reason
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                // Stopped mid-reply: keep what was said so the transcript stays honest.
                reply = reply.ifEmpty { _state.value.streaming.trim() }
                rollBack(base)
                record(text, reply, partial = true)
                throw e
            } catch (e: Exception) {
                failure = e.message ?: c.appContext.getString(R.string.live_error_generic)
            }
            if (failure != null) rollBack(base)
            record(text, reply, partial = false)
            if (failure != null && reply.isEmpty()) {
                _state.update { it.copy(phase = LivePhase.PAUSED, asking = null, streaming = "", tool = null, error = failure) }
                return@launch
            }
            speakThenListen(reply)
        }
    }

    /** Drops a half-finished tool round, which a later request could not replay. */
    private fun rollBack(size: Int) {
        while (conversation.size > size) conversation.removeAt(conversation.lastIndex)
    }

    private fun record(user: String, reply: String, partial: Boolean) {
        if (reply.isNotEmpty() && conversation.lastOrNull()?.role != Role.ASSISTANT)
            conversation += ChatMessage(role = Role.ASSISTANT, text = reply)
        val exchanges = _state.value.exchanges + LiveExchange(user, reply)
        _state.update { it.copy(exchanges = exchanges, asking = null, streaming = "", tool = null,
            notice = if (partial) null else it.notice) }
        c.runManager.saveLiveTranscript(sessionId, liveId, exchanges)
    }

    private suspend fun speakThenListen(reply: String) {
        val speakable = settings.liveMode.speakReplies &&
            SpeechText.isSpeakable(SpeechText.forSpeech(RichResponse.plainText(reply)))
        if (speakable) {
            val id = "live-$liveId-${replyCount++}"
            _state.update { it.copy(phase = LivePhase.SPEAKING) }
            c.tts.enqueueAuto(id, reply)
            // Groq needs a moment to synthesize before playback starts.
            val started = withTimeoutOrNull(SPEECH_START_TIMEOUT_MS) {
                c.tts.state.first { it.messageId == id && it.phase != TtsPhase.IDLE }
            }
            if (started != null) c.tts.state.first { it.messageId != id || it.phase == TtsPhase.IDLE }
        }
        if (!closed && _state.value.phase != LivePhase.PAUSED) {
            _state.update { it.copy(phase = LivePhase.LISTENING) }
            listener.resume()
        }
    }

    private suspend fun intercept(call: ToolCallData): ToolResult? {
        if (call.name != LiveMode.START_TASK_TOOL) return null
        if (settings.liveMode.access != LiveModeAccess.START_TASKS) return ToolResult(false,
            "Starting tasks is turned off in Live Mode settings. Tell the user to send the plan from the chat.")
        val brief = LiveMode.briefFrom(call.argumentsJson)
            ?: return ToolResult(false, "brief is required: describe the task for the main agent.")
        return if (handOff(brief)) ToolResult(true, "The main agent has the task and is starting on it.")
        else ToolResult(false, "The chat could not take the task right now. Tell the user to send it from the chat.")
    }

    /** Sends the plan from the overlay button, phrased for the main agent. */
    fun sendPlan() {
        scope.launch {
            val ok = handOff(c.appContext.getString(R.string.live_handoff_default_brief))
            _state.update { it.copy(notice = c.appContext.getString(if (ok) R.string.live_handoff_sent else R.string.live_handoff_failed)) }
        }
    }

    /**
     * The main agent reads the conversation from the chat. When a task is
     * still running the saved transcript waits for it, so the message carries
     * the conversation itself instead.
     */
    private suspend fun handOff(brief: String): Boolean {
        val exchanges = _state.value.exchanges
        val running = c.runManager.isRunning(sessionId)
        val message = if (running && exchanges.isNotEmpty()) brief + "\n\n" + LiveTranscript.format(exchanges) else brief
        if (!running && exchanges.isNotEmpty()) c.runManager.saveLiveTranscript(sessionId, liveId, exchanges)?.join()
        return withContext(Dispatchers.Main.immediate) { sendToAgent(message) }
    }

    fun close() {
        if (closed) return
        closed = true
        turn?.cancel()
        listener.release()
        c.tts.stop()
    }

    companion object {
        private const val SPEECH_START_TIMEOUT_MS = 20_000L
    }
}
