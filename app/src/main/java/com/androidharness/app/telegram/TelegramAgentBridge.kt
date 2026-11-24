package com.androidharness.app.telegram

import com.androidharness.app.AppContainer
import com.androidharness.app.agent.*
import com.androidharness.app.core.Role
import com.androidharness.app.data.ChatModelTarget
import com.androidharness.app.llm.*
import kotlinx.coroutines.flow.first

internal data class TelegramChoiceItem(val id: String, val label: String)
internal data class TelegramTarget(val projectId: String, val projectName: String, val sessionId: String,
    val chatTitle: String, val providerId: String, val providerName: String, val model: String)
internal data class TelegramDecision(val kind: String, val callId: String, val title: String, val detail: String,
    val options: List<String> = emptyList(), val fingerprint: String = "")
internal data class TelegramRunSnapshot(val running: Boolean = false, val matches: Boolean = false,
    val started: Boolean = false, val completed: Boolean = false, val interrupted: Boolean = false,
    val cancelled: Boolean = false, val turnId: String? = null, val progress: String = "Preparing…",
    val streamingText: String = "", val output: String = "", val error: String? = null,
    val decision: TelegramDecision? = null)

internal interface TelegramAgentBridge {
    suspend fun projects(): List<TelegramChoiceItem>
    suspend fun chats(projectId: String): List<TelegramChoiceItem>
    suspend fun providers(): List<TelegramChoiceItem>
    suspend fun models(providerId: String): List<TelegramChoiceItem>
    suspend fun target(projectId: String, sessionId: String?, mode: String = "ACT"): TelegramTarget
    suspend fun newChat(projectId: String, title: String = "Telegram chat"): String
    suspend fun selectModel(sessionId: String, providerId: String, model: String, mode: String = "ACT")
    suspend fun snapshot(request: TelegramRequest, binding: String): TelegramRunSnapshot
    suspend fun start(request: TelegramRequest, binding: String)
    suspend fun stop(sessionId: String)
    suspend fun decide(sessionId: String, turnId: String, callId: String, kind: String, value: String, fingerprint: String? = null): Boolean
    suspend fun isBusy(sessionId: String): Boolean
}

/** Uses exactly the same Room history, model scope, tool registry and RunManager as the app UI. */
internal class AndroidTelegramAgentBridge(private val c: AppContainer, private val runs: RunManager = c.runManager) : TelegramAgentBridge {
    override suspend fun projects() = c.workspace.projects.first().map { TelegramChoiceItem(it.id, it.name) }
    override suspend fun chats(projectId: String) = c.sessions.sessions.first().filter { it.projectId == projectId }
        .map { TelegramChoiceItem(it.id, it.title.ifBlank { "Untitled chat" }) }
    override suspend fun providers() = c.providers.providers.first().map { TelegramChoiceItem(it.id, it.name) }
    override suspend fun models(providerId: String): List<TelegramChoiceItem> {
        val provider = c.providers.providers.first().firstOrNull { it.id == providerId }
            ?: error("This provider is unavailable. Choose another provider.")
        val key = key(provider)
        return when (val catalog = ModelCatalog.listModels(provider, key)) {
            is ModelCatalog.Result.Models -> {
                c.providers.saveCatalog(providerId, catalog.models)
                catalog.models.map { TelegramChoiceItem(it.id, it.id) }
            }
            is ModelCatalog.Result.Failed -> error("Could not refresh ${provider.name} models. Check the connection and retry.")
        }
    }
    override suspend fun newChat(projectId: String, title: String): String {
        check(projects().any { it.id == projectId }) { "This project was removed. Choose another project." }
        val id = c.sessions.createSession(title.take(80).ifBlank { "Telegram chat" }, projectId)
        c.chatModels.initialize(id)
        return id
    }
    override suspend fun target(projectId: String, sessionId: String?, mode: String): TelegramTarget {
        val project = c.workspace.projects.first().firstOrNull { it.id == projectId }
            ?: error("This project was removed. Choose another project.")
        val sid = sessionId ?: newChat(projectId)
        val chat = c.sessions.session(sid) ?: error("This chat was removed. Choose another chat.")
        check(chat.projectId == project.id) { "This chat belongs to another project. Choose it again." }
        val state = c.chatModels.observe(sid).first()
        check(state.error == null) { state.error ?: "Choose a model for this chat." }
        val choice = if (state.settings.planningModelsEnabled) {
            (if (mode == "PLAN") state.selection.planning else state.selection.execution) ?: state.selection.active
        } else state.selection.active
        choice ?: error("Choose a model for this chat.")
        val provider = state.providers.firstOrNull { it.id == choice.providerId }
            ?: error("This chat's provider is unavailable. Choose another provider.")
        return TelegramTarget(project.id, project.name, sid, chat.title.ifBlank { "Telegram chat" }, provider.id, provider.name, choice.model)
    }
    override suspend fun selectModel(sessionId: String, providerId: String, model: String, mode: String) {
        check(!runs.isRunning(sessionId) && !runs.hasPendingFollowUp(sessionId)) { "Stop this chat's task before changing its model." }
        check(models(providerId).any { it.id == model }) { "This model is no longer available. Refresh the list." }
        check(!runs.isRunning(sessionId) && !runs.hasPendingFollowUp(sessionId)) { "Stop this chat's task before changing its model." }
        val target = if (c.settings.settings.first().planningModelsEnabled) {
            if (mode == "PLAN") ChatModelTarget.PLANNING else ChatModelTarget.EXECUTION
        } else ChatModelTarget.ACTIVE
        c.chatModels.select(sessionId, target, providerId, model)
    }
    private suspend fun key(provider: ProviderConfig): String = if (provider.id == HarnessProvider.ID) c.providers.harnessApiKey()
        else provider.effectiveApiKey(c.providers.apiKey(provider.id)).takeIf { it.isNotBlank() }
            ?: error("Connect ${provider.name} in AndroidHarness before using it from Telegram.")

    override suspend fun start(request: TelegramRequest, binding: String) {
        val sid = requireNotNull(request.sessionId)
        val chat = c.sessions.session(sid) ?: error("This chat was removed. Your request was kept.")
        check(chat.projectId == request.projectId) { "This chat's project changed. Your request was kept for review." }
        val fs = c.workspace.forSession(sid)
        c.mcp.pendingTrustItems(fs).takeIf { it.isNotEmpty() }?.let { pending ->
            error(c.appContext.getString(com.androidharness.app.R.string.trust_needs_approval, pending.joinToString(", ") { it.name }))
        }
        val provider = c.providers.providers.first().firstOrNull { it.id == request.providerId }
            ?: error("The saved provider is unavailable. Choose another model and retry.")
        val config = provider.copy(model = requireNotNull(request.model))
        val apiKey = key(config)
        val saved = runs.controls.flow(sid).value
        val promptId = request.promptId(binding)
        if (request.resume) {
            check(saved.initialPromptId == promptId && saved.resumable) { "This chat has moved on. Review the saved request in AndroidHarness." }
            runs.controls.update(sid) { it.copy(provider = config, allowModelFallback = false) }
            runs.resumeTask(sid, apiKey, notifyOnFinish = false)
            return
        }
        check(c.sessions.messages(sid).none { it.id == promptId }) { "This request already started. Resume its saved task instead of repeating it." }
        check(!saved.resumable) { "This chat has an interrupted task. Resume or stop it first." }
        val prefs = c.settings.settings.first()
        val modelState = c.chatModels.observe(sid).first()
        runs.startRun(sid, request.text, emptyList(), config, apiKey, prefs.permissionMode,
            AgentMode.valueOf(request.mode), prefs.maxOutputTokens, prefs.maxContextTokens, modelState.selection.thinking,
            prefs.maxIterations, workspaceOverride = fs, notifyOnFinish = false, onlyIfIdle = true,
            queuedPromptId = promptId, allowModelFallback = false)
    }
    override suspend fun snapshot(request: TelegramRequest, binding: String): TelegramRunSnapshot {
        val sid = request.sessionId ?: return TelegramRunSnapshot()
        val saved = runs.controls.flow(sid).value
        val live = runs.live(sid).value
        val matches = saved.initialPromptId == request.promptId(binding)
        val archived = if (!matches) runs.remoteCompletions.read(request.promptId(binding))?.takeIf { it.sessionId == sid } else null
        val running = runs.isRunning(sid) || runs.hasPendingFollowUp(sid)
        val rows = if (!running || archived != null || request.active && !matches) c.sessions.messages(sid) else emptyList()
        val started = matches || rows.any { it.id == request.promptId(binding) }
        val decision = if (matches && running) when {
            live.pendingApproval != null -> live.pendingApproval.let {
                TelegramDecision("approval", it.call.id, it.toolDescription,
                    "${it.call.name}\n${it.call.argumentsJson}" + (it.diffPreview?.let { diff -> "\n\n$diff" } ?: ""), fingerprint = RemotePromptIdentity.approval(it)) }
            live.pendingQuestion != null -> live.pendingQuestion.let {
                TelegramDecision("question", it.callId, it.question, if (it.multiSelect) "Reply with all the options you want, separated by commas." else "Choose an option or type your answer.", it.options, RemotePromptIdentity.question(it)) }
            live.pendingEnvironment != null -> live.pendingEnvironment.let {
                TelegramDecision("environment", it.call.id, if (it.repair) "Repair Linux tools?" else "Install Linux tools?", it.command, fingerprint = RemotePromptIdentity.environment(it)) }
            else -> null
        } else null
        val output = rows.filter { it.turnId == (request.turnId ?: archived?.turnId ?: saved.turnId) && it.role == Role.ASSISTANT && it.toolCallId == null && it.text.isNotBlank() }
            .joinToString("\n\n") { com.androidharness.app.core.response.RichResponse.plainText(it.text) }
        if (archived != null && (archived.completed || archived.cancelled)) return TelegramRunSnapshot(
            matches = true, started = true, completed = archived.completed, cancelled = archived.cancelled, turnId = archived.turnId, output = output)
        return TelegramRunSnapshot(running && matches, matches, started, matches && saved.completed,
            matches && !running && saved.resumable, matches && live.cancelled,
            saved.turnId.takeIf { matches }, if (matches) runs.actionText(live) ?: "Working…" else "Waiting for this chat's current task",
            if (matches) com.androidharness.app.core.response.RichResponse.plainText(live.streamingText.orEmpty(), streaming = true) else "", output,
            if (matches) saved.reason ?: live.error else if (started) "This chat has moved to another task. Review the saved request in AndroidHarness." else null, decision)
    }
    override suspend fun isBusy(sessionId: String) = runs.isRunning(sessionId) || runs.hasPendingFollowUp(sessionId) || runs.controls.flow(sessionId).value.resumable
    override suspend fun stop(sessionId: String) {
        runs.stopAndJoin(sessionId)
        runs.controls.update(sessionId) { it.copy(status = "idle", queue = emptyList(), completed = false, reason = "Stopped from Telegram") }
    }
    override suspend fun decide(sessionId: String, turnId: String, callId: String, kind: String, value: String, fingerprint: String?): Boolean = when (kind) {
        "approval" -> runs.respondToRemoteApproval(sessionId, turnId, callId, value == "approve", fingerprint)
        "question" -> runs.respondToRemoteQuestion(sessionId, turnId, callId, value, fingerprint)
        "environment" -> runs.respondToRemoteEnvironment(sessionId, turnId, callId, value == "install", fingerprint)
        else -> false
    }
}
