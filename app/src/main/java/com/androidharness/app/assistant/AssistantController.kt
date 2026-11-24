package com.androidharness.app.assistant

import android.content.Context
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.graphics.Bitmap
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.agent.AgentMode
import com.androidharness.app.agent.ApprovalRequest
import com.androidharness.app.agent.QuestionRequest
import com.androidharness.app.agent.RunManager
import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.agent.ThinkingSpecs
import com.androidharness.app.agent.ThinkingTiming
import com.androidharness.app.core.ChatExperience
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageRef
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.core.response.ChatRichResponse
import com.androidharness.app.data.ChatModelChoice
import com.androidharness.app.tools.ReadScreenTool
import com.androidharness.app.tools.SharedScreen
import com.androidharness.app.tools.SharedScreens
import com.androidharness.app.data.ChatModelSelection
import com.androidharness.app.data.ChatModelState
import com.androidharness.app.data.ConversationConfig
import com.androidharness.app.data.ConversationKind
import com.androidharness.app.data.SearchDepth
import com.androidharness.app.llm.HarnessProvider
import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ModelsDev
import com.androidharness.app.llm.RequestOptions
import com.androidharness.app.llm.effectiveApiKey
import com.androidharness.app.local.LocalModelCatalog
import com.androidharness.app.ui.chat.FileAttachments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal data class AssistantUiState(
    val screen: ScreenSnapshot = ScreenSnapshot(),
    /** True once Android has answered (or declined) both the text and screenshot requests. */
    val screenSettled: Boolean = false,
    val sessionId: String? = null,
    /** Saved rows of this panel's chat, the same list the Chat screen reads. */
    val messages: List<ChatMessage> = emptyList(),
    /** The question just sent, shown until its saved row arrives. */
    val sending: String? = null,
    val busy: Boolean = false,
    val currentTurnId: String? = null,
    val streamingText: String? = null,
    val streamingThinking: String? = null,
    val streamingMessageId: String? = null,
    /** The live answer has been saved; it stays on screen under the same id until its row arrives. */
    val streamingCommitted: Boolean = false,
    val runningCalls: List<ToolCallData> = emptyList(),
    val thinkingTiming: ThinkingTiming = ThinkingTiming(),
    val reasoningTokens: Int? = null,
    val turnStartedAt: Long? = null,
    val approval: ApprovalRequest? = null,
    val question: QuestionRequest? = null,
    val questionAutoAt: Long? = null,
    val retryStatus: String? = null,
    val error: String? = null,
    /** Short name of the model that answers here. */
    val modelLabel: String? = null,
    /** The assistant's thinking setting as this model understands it. */
    val thinking: ThinkingSpecs.ResolvedThinking? = null,
) {
    val hasConversation: Boolean get() = messages.isNotEmpty() || sending != null || busy || error != null
}

/**
 * Runs the panel's chat through the same RunManager, history, tools and model choice as
 * the Chat screen, as a normal Chat conversation, so "Continue in app" lands on the same
 * thread. Only the thinking level is the assistant's own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AssistantController(private val context: Context, private val c: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = mutable.asStateFlow()
    private val session = MutableStateFlow<String?>(null)
    private var starting = false
    private var pendingText = false
    private var pendingShot = false
    /** The last answer that streamed, kept on screen until its saved row shows up. */
    private var held: Held? = null
    /** Read the next answer aloud: the question was spoken. */
    private var speakNext = false
    private var runSeen = false
    /** When the current question was sent; its saved row replaces the pending bubble. */
    private var sentAt = 0L
    /** A run error the user already closed, so a late update does not bring it back. */
    private var dismissed: String? = null
    /** The last question typed or spoken here, to send again when it failed before reaching the chat. */
    private var lastAsked: String? = null
    /** Counts assistant calls, so a screen still loading is never taken from a later call. */
    private var generation = 0

    private class Held(val messageId: String, val text: String?, val thinking: String?)

    init {
        scope.launch {
            session.flatMapLatest { sid ->
                if (sid == null) flowOf(null) else combine(c.sessions.messagesFlow(sid), c.runManager.live(sid)) { rows, live -> rows to live }
            }.collect { pair ->
                val (rows, live) = pair ?: return@collect
                mirror(rows, live)
            }
        }
        scope.launch {
            combine(session.flatMapLatest { c.chatModels.observe(it) }, c.providers.catalogs, ModelsDev.revision) { models, catalogs, _ ->
                describeModel(models, catalogs)
            }.collect { (label, thinking) -> mutable.update { it.copy(modelLabel = label, thinking = thinking) } }
        }
    }

    /**
     * Same handoff as the Chat screen: when the stream stops before its saved row arrives,
     * the last text stays under the same id, so the answer never blinks out for a frame.
     */
    private fun mirror(rows: List<ChatMessage>, live: RunManager.LiveRunState) {
        mutable.update { st ->
            var text = live.streamingText
            var thinking = live.streamingThinking
            var streamId = live.liveMessageId
            var committed = false
            if (text == null && thinking == null) {
                val hold = held ?: live.lastCommittedId?.takeIf { id ->
                    st.streamingMessageId == id && (!st.streamingText.isNullOrBlank() || !st.streamingThinking.isNullOrBlank())
                }?.let { id -> Held(id, st.streamingText, st.streamingThinking).also { h -> held = h; releaseLater(h) } }
                if (hold != null && rows.none { it.id == hold.messageId }) {
                    text = hold.text
                    thinking = hold.thinking
                    streamId = hold.messageId
                    committed = true
                } else if (hold != null) held = null
            } else held = null
            val running = live.running
            if (running) runSeen = true
            st.copy(
                messages = rows,
                sending = st.sending?.takeIf { rows.none { row -> row.role == Role.USER && row.createdAt >= sentAt } },
                busy = running || starting,
                currentTurnId = live.turnId,
                streamingText = text,
                streamingThinking = thinking,
                streamingMessageId = streamId,
                streamingCommitted = committed,
                runningCalls = live.runningCalls,
                thinkingTiming = if (committed) st.thinkingTiming.stop(ThinkingTiming.now()) else live.thinkingTiming,
                reasoningTokens = if (committed) st.reasoningTokens else live.reasoningTokens,
                turnStartedAt = live.turnStartedAt,
                approval = live.pendingApproval,
                question = live.pendingQuestion,
                questionAutoAt = live.questionAutoAt,
                retryStatus = live.retryStatus,
                error = live.error?.takeIf { !running && it != dismissed } ?: st.error,
            )
        }
        if (speakNext && runSeen && !live.running) speakAnswer(rows)
    }

    /** A held answer must not outlive a slow or failed save. */
    private fun releaseLater(hold: Held) {
        scope.launch {
            delay(HOLD_TIMEOUT_MS)
            if (held === hold && mutable.value.messages.none { it.id == hold.messageId }) {
                held = null
                mutable.update { it.copy(streamingText = null, streamingThinking = null, streamingMessageId = null, streamingCommitted = false) }
            }
        }
    }

    private fun speakAnswer(rows: List<ChatMessage>) {
        val turn = rows.lastOrNull { it.role == Role.USER }?.turnId ?: return
        val answer = rows.lastOrNull { it.turnId == turn && it.role == Role.ASSISTANT && it.toolCallId == null && it.toolCalls.isEmpty() && it.text.isNotBlank() }
        val id = answer?.id ?: return
        speakNext = false
        scope.launch {
            if (c.settings.settings.first().assistantSpeakReplies) c.tts.enqueueAuto(id, ChatRichResponse.normalize(answer.text))
        }
    }

    /** The model picked for the assistant in settings, while its provider is still there; null follows Chat. */
    private fun ownModel(models: ChatModelState): ChatModelChoice? {
        val id = models.settings.assistantProviderId ?: return null
        val provider = models.providers.firstOrNull { it.id == id } ?: return null
        return ChatModelChoice(id, models.settings.assistantModel?.takeIf { it.isNotBlank() } ?: provider.model)
    }

    private fun describeModel(models: ChatModelState, catalogs: Map<String, List<ModelEntry>>): Pair<String?, ThinkingSpecs.ResolvedThinking?> {
        val choice = ownModel(models) ?: models.selection.active
        val provider = models.providers.firstOrNull { it.id == choice?.providerId } ?: return null to null
        val model = choice?.model?.takeIf { it.isNotBlank() } ?: provider.model
        val entry = catalogs[provider.id].orEmpty().firstOrNull { it.id == model } ?: ModelEntry(model)
        val thinking = runCatching {
            RequestOptions(thinking = models.settings.assistantThinking, maxOutputTokens = models.settings.maxOutputTokens,
                customReasoning = models.settings.reasoningConfigs, customThinkingLevels = models.selection.customThinkingLevels)
                .resolveThinking(provider.copy(model = model), entry)
        }.getOrNull()
        return (ChatExperience.modelLabel(model) ?: model) to thinking
    }

    /** A new assistant call: forget the old screen, keep nothing from a previous panel. */
    fun begin(wantsText: Boolean, wantsShot: Boolean) {
        generation++
        pendingText = wantsText
        pendingShot = wantsShot
        quiet()
        mutable.update { AssistantUiState(screenSettled = !wantsText && !wantsShot, modelLabel = it.modelLabel, thinking = it.thinking) }
        session.value = null
    }

    /**
     * Reads the screen's text away from the main thread: walking the structure can fetch more of
     * it from the other app, and that would stall the opening animation.
     */
    @Suppress("DEPRECATION")
    fun onAssist(structure: AssistStructure?, fallbackPackage: String?, content: AssistContent?) {
        val gen = generation
        scope.launch {
            val read = withContext(Dispatchers.Default) {
                val text = structure?.let { runCatching { ScreenText.fromStructure(it) }.getOrNull() }?.takeIf { it.isNotBlank() }
                val pkg = structure?.let { runCatching { it.activityComponent?.packageName }.getOrNull() } ?: fallbackPackage
                ScreenRead(pkg, ScreenText.appName(context, pkg), text, ScreenText.webUri(content))
            }
            if (gen != generation) return@launch
            pendingText = false
            mutable.update { it.copy(screen = it.screen.copy(packageName = read.packageName ?: it.screen.packageName,
                appName = read.appName ?: it.screen.appName, text = read.text, webUri = read.webUri),
                screenSettled = !pendingShot) }
        }
    }

    private class ScreenRead(val packageName: String?, val appName: String?, val text: String?, val webUri: String?)

    /** Kept as Android gave it; it is only copied to a normal bitmap when the model asks to see it. */
    fun onScreenshot(bitmap: Bitmap?) {
        pendingShot = false
        mutable.update { it.copy(screen = it.screen.copy(screenshot = bitmap), screenSettled = !pendingText) }
    }

    fun dismissError() {
        dismissed = mutable.value.error
        mutable.update { it.copy(error = null) }
    }

    fun showError(message: String) = mutable.update { it.copy(error = message) }

    fun newQuestion() {
        quiet()
        session.value = null
        held = null
        mutable.update { AssistantUiState(screen = it.screen, screenSettled = it.screenSettled,
            modelLabel = it.modelLabel, thinking = it.thinking) }
    }

    fun stop() {
        val sid = session.value ?: return
        speakNext = false
        scope.launch { runCatching { c.runManager.stopAndJoin(sid) } }
    }

    fun approve(remember: Boolean) { session.value?.let { c.runManager.approve(it, remember) } }

    fun deny() { session.value?.let(c.runManager::deny) }

    fun answer(text: String) { session.value?.let { c.runManager.answerQuestion(it, text) } }

    fun setThinking(level: ThinkingLevel) { scope.launch { c.settings.setAssistantThinking(level) } }

    /** Reads one answer aloud, or stops it when it is already speaking. */
    fun speak(messageId: String, text: String) = c.tts.toggle(messageId, text)

    /** Stops reading only when what is being read belongs to this panel. */
    fun quiet() {
        val speaking = c.tts.state.value.messageId ?: return
        if (mutable.value.messages.any { it.id == speaking }) c.tts.stop()
    }

    /** Answers the latest question again with deep research, the way Chat's "Dig deeper" does. */
    fun digDeeper(): Job? {
        val question = mutable.value.messages.lastOrNull { it.role == Role.USER && it.id != null } ?: return null
        return run(question.text, spoken = false, replacement = question, depth = SearchDepth.DEEP)
    }

    /** [withScreen]: a suggestion like Summarize, which is always about the screen, so it goes with the question. */
    fun send(question: String, spoken: Boolean = false, withScreen: Boolean = false): Job? =
        run(question.trim(), spoken, null, null, withScreen)

    /**
     * Asks the last question again after an error: a saved question is answered again in
     * place, like Chat's retry, and one that never reached the chat is simply sent again.
     */
    fun retry(): Job? {
        val saved = mutable.value.messages.lastOrNull { it.role == Role.USER && it.id != null }
        if (saved != null && saved.createdAt >= sentAt) return run(saved.text, false, saved, null)
        return lastAsked?.let { send(it) }
    }

    private fun run(text: String, spoken: Boolean, replacement: ChatMessage?, depth: SearchDepth?, withScreen: Boolean = false): Job? {
        if (text.isEmpty() || starting || mutable.value.busy) return null
        starting = true
        quiet()
        speakNext = spoken
        runSeen = false
        dismissed = null
        if (replacement == null) lastAsked = text
        sentAt = System.currentTimeMillis() - 1_000
        mutable.update { it.copy(busy = true, error = null, sending = if (replacement == null) text else null) }
        return scope.launch {
            try {
                val sid = start(text, replacement, depth, withScreen)
                mutable.update { it.copy(sessionId = sid) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                speakNext = false
                mutable.update { it.copy(error = e.message ?: context.getString(R.string.assistant_failed), sending = null) }
            } finally {
                starting = false
                mutable.update { it.copy(busy = session.value?.let(c.runManager::isRunning) == true,
                    sending = it.sending?.takeIf { _ -> it.messages.none { row -> row.role == Role.USER && row.createdAt >= sentAt } }) }
            }
        }
    }

    /** The Chat screen's send path, step for step, with the screen riding along on the first question. */
    private suspend fun start(text: String, replacement: ChatMessage?, depth: SearchDepth?, withScreen: Boolean): String {
        val models = c.chatModels.observe(session.value).first()
        models.error?.let { error(it) }
        val own = ownModel(models)
        // A model picked for the assistant stays put, like a chat with its own model.
        val sameModel = own == null && models.settings.sameModelAcrossChats && !models.agentChat
        val choice = own ?: models.selection.active ?: error(context.getString(R.string.assistant_no_model))
        val provider = models.providers.firstOrNull { it.id == choice.providerId } ?: error(context.getString(R.string.assistant_no_model))
        val model = choice.model.takeIf { it.isNotBlank() } ?: provider.model
        if (!sameModel && provider.id == HarnessProvider.ID && HarnessProvider.sanitize(model, HarnessProvider.customModelIds) != model)
            error(context.getString(R.string.ui_this_chat_s_model_is_no_longer))
        val apiKey = if (provider.id == HarnessProvider.ID) c.providers.harnessApiKey(models.providers)
            else provider.effectiveApiKey(c.providers.apiKey(provider.id))
        check(apiKey.isNotBlank()) { context.getString(R.string.assistant_no_key, provider.name) }

        val first = session.value == null
        val sid = session.value ?: withContext(Dispatchers.IO) {
            c.sessions.createSession(text.take(48), c.workspace.currentProjectOnce().id, ConversationConfig(ConversationKind.CHAT))
                .also { c.chatModels.initialize(it) }
                .also { sid ->
                    // With a model per chat, the chat opened from "Continue in app" keeps the assistant's model.
                    if (own != null && !models.settings.sameModelAcrossChats) {
                        val saved = runCatching { ChatModelSelection.decode(c.sessions.session(sid)?.modelSelectionJson) }.getOrNull()
                            ?: ChatModelSelection.from(models.settings, models.providers)
                        c.sessions.saveModelSelection(sid, saved.copy(active = own))
                    }
                }
        }
        session.value = sid
        mutable.update { it.copy(sessionId = sid) }

        // Harness free models sit behind different wires per model; learn the right one once.
        if (provider.id == HarnessProvider.ID && c.providers.wire(model) == null && !HarnessProvider.isPooled(model) &&
            c.providers.customModels(provider.id).any { it.id == model }) {
            HarnessProvider.probeWire(model, c.providers.harnessApiKey())?.let { learned ->
                c.providers.pinWire(model, learned.name)
                HarnessProvider.pins = HarnessProvider.pins + (model to learned.name)
            }
        }
        val config = provider.copy(model = model)
        val resolved = c.providers.resolveHarnessConfig(config)
        check(sameModel || resolved.model == config.model) { context.getString(R.string.ui_this_chat_s_model_is_no_longer) }
        if (resolved.model != config.model) c.settings.setActiveModel(resolved.model)

        val current = mutable.value
        // The screen is not sent with the question. The model gets a one-line note and reads the
        // screen with read_screen when the question is about it. Android may still be delivering
        // the screen when a quick question goes out, so it is picked up when the model asks.
        val share = first && replacement == null && (current.screen.hasContent || !current.screenSettled)
        var images = replacement?.images ?: emptyList()
        val payload = if (share) {
            val gen = generation
            val ready = scope.async {
                withTimeoutOrNull(SCREEN_WAIT_MS) { state.first { it.screenSettled } }
                mutable.value.screen.takeIf { gen == generation && it.hasContent }
            }
            val shared = SharedScreen {
                // Closing the panel before Android answered cancels the wait; that only means no screen.
                val screen = try { ready.await() } catch (e: CancellationException) { if (!ready.isCancelled) throw e; null }
                screen?.let { shown ->
                    SharedScreen.Content(ScreenText.describe(shown, context.getString(R.string.assistant_screen_context_note)),
                        shown.screenshot?.let { { withContext(Dispatchers.IO) { saveScreenshot(shown) } } },
                        shown.screenshot?.let { bitmap -> { readScreenshot(bitmap) } })
                }
            }
            SharedScreens.put(sid, shared)
            val screen = if (withScreen) shared.content() else null
            val app = (if (withScreen) mutable.value.screen.appName else current.screen.appName) ?: "another app"
            if (screen != null) {
                // A suggestion is about the screen by definition: send it now instead of waiting for the tool.
                val sees = c.providers.catalogs.first()[provider.id]?.firstOrNull { it.id == model }?.imageInput
                    ?: com.androidharness.app.llm.visionCapable(model)
                if (sees) shared.screenshot()?.let { images = images + it }
                // A model that cannot see gets the screenshot's text too: it catches what the app's text leaves out.
                val read = if (sees) null else shared.screenshotText()
                val body = if (read == null) screen.text else screen.text + "\n\n" + ReadScreenTool.screenshotTextBlock(read)
                text + "\n\n" + FileAttachments.inlineBlock("Screen from $app.txt", "text/plain",
                    FileAttachments.humanBytes(body.toByteArray().size.toLong()), body, null)
            } else {
                val note = "The user opened the assistant over $app. When their message is about what is on their screen, " +
                    "call ${ReadScreenTool.NAME} to see it. Otherwise answer normally."
                text + "\n\n" + FileAttachments.inlineBlock("Screen from $app.txt", "text/plain",
                    FileAttachments.humanBytes(note.toByteArray().size.toLong()), note, null)
            }
        } else text

        val prefs = models.settings
        val local = if (LocalModelCatalog.isLocal(provider.id)) withContext(Dispatchers.IO) {
            c.localModels.limits(provider.id.removePrefix(LocalModelCatalog.PROVIDER_PREFIX))
        } else null
        return c.runManager.startRun(
            sessionId = sid, text = payload, imageRefs = images, config = config, apiKey = apiKey,
            permissionMode = prefs.permissionMode, mode = AgentMode.ACT,
            maxOutputTokens = local?.output ?: prefs.maxOutputTokens,
            maxContextTokens = local?.context ?: prefs.maxContextTokens,
            thinking = c.settings.settings.first().assistantThinking, maxIterations = prefs.maxIterations,
            workspaceOverride = c.workspace.forSession(sid), notifyOnFinish = false,
            replacementMessageId = replacement?.id,
            allowModelFallback = sameModel,
            searchDepthOverride = depth,
        )
    }

    /** Bounded JPEG in the app's image store, so the chat can show it later too. */
    /** The screenshot's text, read on the device, when Read text in images is on. */
    private suspend fun readScreenshot(bitmap: Bitmap): String? {
        if (!c.settings.settings.first().readImageText || bitmap.isRecycled) return null
        return try { c.imageText.read(bitmap) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }

    private fun saveScreenshot(screen: ScreenSnapshot): ImageRef? {
        val given = screen.screenshot ?: return null
        val shot = if (given.config == Bitmap.Config.HARDWARE) given.copy(Bitmap.Config.ARGB_8888, false) ?: return null else given
        val scale = (MAX_EDGE.toFloat() / maxOf(shot.width, shot.height)).coerceAtMost(1f)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(shot, (shot.width * scale).toInt().coerceAtLeast(1),
            (shot.height * scale).toInt().coerceAtLeast(1), true) else shot
        val file = File(c.images.imagesDir, "assistant-screen-${UUID.randomUUID()}.jpg")
        return try {
            file.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
            ImageRef(file.name, "image/jpeg", caption = "Screenshot of ${screen.appName ?: "the screen"}",
                width = scaled.width, height = scaled.height, bytes = file.length(), origin = "assistant_screen")
        } catch (e: Exception) {
            file.delete(); null
        } finally {
            if (scaled !== shot) scaled.recycle()
            if (shot !== given) shot.recycle()
        }
    }

    fun close() {
        quiet()
        scope.cancel()
    }

    private companion object {
        const val MAX_EDGE = 1568
        const val SCREEN_WAIT_MS = 4_000L
        const val HOLD_TIMEOUT_MS = 2_000L
    }
}
