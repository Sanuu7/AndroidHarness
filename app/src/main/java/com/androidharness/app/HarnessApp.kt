package com.androidharness.app

import android.app.Application
import android.content.Context
import android.webkit.WebView
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.room.Room
import com.androidharness.app.agent.AgentEngine
import com.androidharness.app.agent.TodoStore
import com.androidharness.app.data.ChatBackupManager
import com.androidharness.app.data.CheckpointStore
import com.androidharness.app.data.ImageStore
import com.androidharness.app.data.KeyStoreManager
import com.androidharness.app.data.ProviderRepository
import com.androidharness.app.data.SessionRepository
import com.androidharness.app.data.SettingsRepository
import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.llm.ProviderFactory
import com.androidharness.app.llm.effectiveApiKey
import com.androidharness.app.tools.ToolRegistry
import com.androidharness.app.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HarnessApp : Application() {
    private val memoryScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        com.androidharness.app.local.GpuDeliveryFactory.attach(this)
    }
    lateinit var container: AppContainer
        private set

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate() {
        super.onCreate()
        // Paused LazyColumn prefetch crashes in LayoutNode.onChildRemoved when chat items change.
        ComposeFoundationFlags.isPausableCompositionInPrefetchEnabled = false
        WebView.enableSlowWholeDocumentDraw()
        container = AppContainer(this)
        memoryScope.launch { container.settings.ensureRumbleCombo() }
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::container.isInitialized && level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            memoryScope.launch { container.localModels.releaseCachedModel() }
        }
    }
    override fun onLowMemory() {
        super.onLowMemory()
        if (::container.isInitialized) memoryScope.launch { container.localModels.releaseCachedModel() }
    }
}

class AppContainer(val appContext: Context) {
    /** Deep-link channel: run-result notifications deliver a session id here. */
    val pendingSessionId = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    val pendingSubagentChat = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, String>?>(null)
    /** Successful repository imports open their own files, independent of the old chat's workspace. */
    val pendingImportedWorkspace = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Share-target channel: content awaiting a regular Chat destination and draft import. */
    val pendingShare = kotlinx.coroutines.flow.MutableStateFlow<com.androidharness.app.data.PendingShare?>(null)

    /** Selected-code prompt waiting for its specific chat composer. */
    val pendingCodeDraft = kotlinx.coroutines.flow.MutableStateFlow<com.androidharness.app.ui.files.CodeChatDraft?>(null)

    /** Build/test failure prompt waiting for a fresh chat to run it. */
    val pendingAgentPrompt = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** In-app nav channel: a screen asking Settings to scroll to a section. */
    val pendingSettingsScroll = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val keys = KeyStoreManager(appContext)
    init { com.androidharness.app.llm.ProviderNetwork.settings = keys::providerProxy }
    val settings = SettingsRepository(appContext)
    private val configurationRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val pluginManager = com.androidharness.app.plugins.PluginManager(appContext, configurationRevision,
        com.androidharness.app.plugins.PluginDownloads(githubToken = { github.accessToken() }))
    val configuration = com.androidharness.app.config.HarnessConfigRepository(appContext, keys, pluginManager, configurationRevision)
    val pluginHooks = com.androidharness.app.config.PluginHooks(appContext)
    internal val runtimePolicy = AppRuntimePolicy(appContext, settings)
    /** Reads assistant replies with Android TTS or Groq PlayAI. */
    val tts = com.androidharness.app.data.audio.TtsController(appContext, settings, keys)
    internal val editorScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val editorDrafts = com.androidharness.app.ui.files.EditorDraftStore(
        java.io.File(appContext.noBackupFilesDir, "editor-drafts"), editorScope)
    internal val editorTabs = com.androidharness.app.ui.files.EditorTabsStore(
        java.io.File(appContext.noBackupFilesDir, "editor-tabs"), editorScope)
    val localModels = com.androidharness.app.local.LocalModelManager(appContext)
    val chatGpt = com.androidharness.app.chatgpt.ChatGptAccounts(keys::chatGptCredentials, keys::putChatGptCredentials,
        onConnected = { config ->
            settings.setActiveSelection(config.id, config.model)
        }, onDisconnected = { id ->
            runManager.runningSessionIds.value.filter {
                val saved = runManager.controls.flow(it).value
                saved.provider?.id == id || id in saved.usedProviderIds ||
                    runManager.subagents.runs.value.any { run -> run.sessionId == it && run.status.active && run.providerId == id }
            }
                .forEach { runManager.stopAndJoin(it) }
        })
    val providers: ProviderRepository = ProviderRepository(appContext, keys, localModels, chatGpt, settings)

    init {
        com.androidharness.app.llm.RotatingKeyProvider.pools = providers::controls
        ProviderFactory.chatGptProvider = com.androidharness.app.chatgpt.ChatGptProvider(chatGpt::accessToken,
            modelEntry = { id, model -> chatGpt.state.value.accounts.firstOrNull { it.providerId == id }?.models?.firstOrNull { it.id == model } })
        com.androidharness.app.llm.ModelCatalog.chatGptModels = { chatGpt.refreshModels(it) }
        ProviderFactory.localProvider = com.androidharness.app.local.LocalModelProvider(localModels) {
            settings.settings.first().localModelAgentContext
        }
    }
    val screenshotPolicy = com.androidharness.app.data.ScreenshotPolicy()

    private val db = Room.databaseBuilder(appContext, AppDatabase::class.java, "harness.db")
        .addMigrations(
            AppDatabase.MIGRATION_24_25, AppDatabase.MIGRATION_23_24, AppDatabase.MIGRATION_22_23, AppDatabase.MIGRATION_21_22, AppDatabase.MIGRATION_20_21, AppDatabase.MIGRATION_19_20, AppDatabase.MIGRATION_18_19, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17, AppDatabase.MIGRATION_17_18,
        )
        .addCallback(AppDatabase.OVERSIZED_ROW_SANITIZER)
        // Only kicks in when no migration path exists (pre-v4 databases);
        // the v4→v5 path above preserves sessions and usage totals.
        .fallbackToDestructiveMigration(true)
        .build()
    val images = ImageStore(appContext)
    val sessions = SessionRepository(db, images)
    val memory = com.androidharness.app.data.MemoryRepository(db, settings)
    val chatModels = com.androidharness.app.data.ChatModelRepository(settings, providers, sessions)
    suspend fun startChatAgent(profile: com.androidharness.app.data.ChatAgentProfile): String {
        profile.validated()
        val id = sessions.createSession(profile.name, workspace.currentProjectOnce().id,
            com.androidharness.app.data.ConversationConfig(kind = com.androidharness.app.data.ConversationKind.CHAT, agent = profile))
        chatModels.initialize(id)
        settings.setLastActiveSessionId(id)
        return id
    }

    private suspend fun canRecallChat(from: String?, target: String): Boolean {
        val source = from?.let { sessions.session(it) } ?: return false
        val destination = sessions.session(target) ?: return false
        val config = com.androidharness.app.data.ConversationConfig.decode(source.conversationJson)
        return config.personalizationEnabled && com.androidharness.app.data.ChatAgentPolicy.canReadHistory(config,
            com.androidharness.app.data.ConversationConfig.decode(destination.conversationJson))
    }
    val chatBackup by lazy { ChatBackupManager(db, appContext, workspace) }
    val snippets = com.androidharness.app.data.SnippetRepository(db.dao())

    val sshConnections = com.androidharness.app.workspace.SshConnections(keys)
    val workspace = WorkspaceManager(appContext, db.dao(), sshConnections)
    val fileHistory = com.androidharness.app.data.WorkspaceFileHistory(java.io.File(appContext.filesDir, "workspace-file-history"))
    val checkpoints = CheckpointStore(db.dao(), fileHistory)

    val todoStore = TodoStore()
    val repoMap = com.androidharness.app.repomap.RepoMapCache()
    private val fetchClient = OkHttpClient()
    val linuxEnv =
        com.androidharness.app.data.env.LinuxEnvironmentManager(appContext) { keys.githubToken() }
    val wirelessAdb = com.androidharness.app.data.env.adb.WirelessAdbManager(appContext)
    val shizuku = com.androidharness.app.data.env.ShizukuManager(appContext, wireless = wirelessAdb)
    val dhizuku = com.androidharness.app.data.env.DhizukuManager(appContext)
    val deviceAccess = com.androidharness.app.data.env.DeviceAccessManager(appContext, shizuku, linuxEnv,
        projectForSession = { sessions.session(it)?.projectId }, dhizuku = dhizuku)
    val shellRouter = com.androidharness.app.data.env.ShellTierRouter(appContext, shizuku, linuxEnv, deviceAccess)
    val codeGraph = com.androidharness.app.data.env.CodeGraphManager(linuxEnv, shellRouter)

    init {
        // Package-set changes must invalidate the deployed-copy cache, or the
        // privileged tier keeps serving the old toolchain until a restart.
        linuxEnv.deployStateListener = { shizuku.invalidateDeployState() }
    }

    val updates = com.androidharness.app.data.update.UpdateManager(appContext, deviceAccess)
    internal val sync by lazy { com.androidharness.app.data.transfer.SyncManager(keys, sshConnections) { fs, action ->
        runManager.withIdleWorkspace(fs, "Stop running tasks in this workspace before syncing files.", action)
    } }
    internal val ftpShare by lazy { com.androidharness.app.data.transfer.FtpShareManager(appContext) { fs, action ->
        runManager.withIdleWorkspace(fs, "Stop running tasks in this workspace before changing shared files.", action)
    } }
    val backgroundProcesses = com.androidharness.app.data.BgProcessStore(
        appContext, linuxEnv, shizuku, workspaceRoot = workspace.appPrivateRoot, deviceAccess = deviceAccess,
    )

    init { deviceAccess.stopBackgroundTasks = backgroundProcesses::stopPrivilegedTasks }

    @Volatile
    private var projectSkillsDir: java.io.File? = null
    private val disabledSkills = AtomicReference<Set<String>>(emptySet())
    private val webSearchProvider = AtomicReference("keyless")
    private val searchKeyMigrated = AtomicBoolean(false)

    /**
     * web_search backend config, resolved per call so Settings changes apply
     * to the very next search without a restart. Null = keyless engines.
     */
    val searchApiConfig: com.androidharness.app.tools.SearchApiConfig?
        get() {
            val provider = webSearchProvider.get()
            if (provider == "keyless") return null
            return keys.searchApiKey(provider)?.let {
                com.androidharness.app.tools.SearchApiConfig(provider, it)
            }
        }

    private suspend fun resolveSearchApiConfig(): com.androidharness.app.tools.SearchApiConfig? {
        val provider = settings.settings.first().webSearchProvider
        keys.migrateLegacySearchKey(provider)
        webSearchProvider.set(provider)
        if (provider == "keyless") return null
        return com.androidharness.app.tools.SearchApiConfig(provider, keys.searchApiKey(provider).orEmpty())
    }

    private val cavemanSettings = AtomicReference(com.androidharness.app.data.AppSettings())
    val skills = com.androidharness.app.skills.SkillStore(
        bundled = com.androidharness.app.skills.SkillAssets.load(appContext.assets),
        userDir = java.io.File(appContext.filesDir, "skills").apply { mkdirs() },
        projectDir = { projectSkillsDir },
        disabled = { disabledSkills.get() },
        optionalBundled = {
            if (cavemanSettings.get().cavemanInstalled) com.androidharness.app.caveman.CavemanPolicy.skills else emptyMap()
        },
    )
    val browser = com.androidharness.app.browser.BrowserController(appContext, images)
    val registry = ToolRegistry.default(
        fetchClient, todoStore, backgroundProcesses, linuxEnv, shizuku, shellRouter, skills, codeGraph,
        imageStore = images,
        browserController = browser,
        searchApi = { searchApiConfig },
        searchApiRefresh = ::resolveSearchApiConfig,
        artifactLoader = sessions::artifacts,
        toolOutputLoader = sessions::toolOutput,
        fileHistory = fileHistory,
        memory = memory,
        dhizuku = dhizuku,
        deviceAccess = deviceAccess,
        // Declared below; resolved on each call, after construction.
        pullRequests = { githubRepositories },
        sessionMessages = { sessions.messages(it) },
    )
    val mcp = com.androidharness.app.tools.mcp.McpManager(appContext, linuxEnv, keys, codeGraph, configuration)
    val github = com.androidharness.app.github.GitHubAuth(BuildConfig.GITHUB_CLIENT_ID,
        keys::githubCredential, keys::putGitHubCredential, { refreshGitHubAuth() })
    fun routedProvider(): com.androidharness.app.llm.LlmProvider =
        com.androidharness.app.llm.FallbackProvider(
                factory = ProviderFactory::create,
                resolve = { target ->
                    providers.providers.first().firstOrNull { it.id == target.providerId && it.enabled }
                        ?.takeIf { provider -> com.androidharness.app.llm.ProviderModelSearch.models(provider, providers.catalog(target.providerId)).any { it.id == target.model } }
                        ?.copy(model = target.model)
                },
                keyFor = { config ->
                    if (config.id == com.androidharness.app.llm.HarnessProvider.ID) providers.harnessApiKey()
                    else {
                        val live = providers.providers.first().firstOrNull { it.id == config.id }
                        if (live == null || live.baseUrl != config.baseUrl || live.type != config.type) ""
                        else config.effectiveApiKey(providers.apiKey(config.id))
                    }
                },
                destination = { config ->
                    if (config.id == com.androidharness.app.llm.HarnessProvider.ID)
                        com.androidharness.app.llm.HarnessProvider.pool[config.model]?.baseUrl ?: config.baseUrl
                    else config.baseUrl
                },
        )

    val engine: AgentEngine = AgentEngine(
        providerFactory = { routedProvider() },
        registry = registry,
        checkpointer = checkpoints,
        imageStore = images,
        linuxEnv = linuxEnv,
        shizuku = shizuku,
        skills = skills,
        resolveConfiguration = { configuration.load(it) },
        pluginHooks = pluginHooks,
        todoStore = todoStore,
        repoMap = repoMap,
        cavemanSettings = { settings.settings.first().also { cavemanSettings.set(it); disabledSkills.set(it.disabledSkills) } },
        configuredSubagent = {
            com.androidharness.app.agent.SubagentConfiguration.configured(
                settings.settings.first(), providers.providers.first(), providers::apiKey,
            )?.let { connection ->
                com.androidharness.app.agent.SubagentConnection(
                    providers.resolveHarnessConfig(connection.config), connection.apiKey,
                )
            }
        },
        prepareGitHub = { github.refreshIfNeeded() },
        deviceAccess = deviceAccess,
        universalMemory = memory,
        chatOnlyTools = listOf(com.androidharness.app.tools.ReadChatTextTool(sessions::messages),
            com.androidharness.app.tools.ViewChatImageTool(images),
            com.androidharness.app.tools.BrowserReadTool(browser),
            com.androidharness.app.tools.SearchChatsTool { query, fuzzy, limit ->
                sessions.searchMessages(query, fuzzy, limit)
            }.apply { canAccess = ::canRecallChat },
            com.androidharness.app.tools.RecentChatsTool(sessions.sessions).apply { canAccess = ::canRecallChat },
            com.androidharness.app.tools.ReadChatSnippetTool(sessions::messages, sessions::session).apply { canAccess = ::canRecallChat },
            com.androidharness.app.tools.OfferCodeHandoffTool(),
            com.androidharness.app.tools.GitHubApiTool(fetchClient) { github.accessToken() },
            com.androidharness.app.tools.GitHubSearchTool(fetchClient) { github.accessToken() },
            com.androidharness.app.tools.GitHubFileTool(fetchClient) { github.accessToken() }),
    )
    val runManager = com.androidharness.app.agent.RunManager(
        context = appContext,
        engine = engine,
        sessions = sessions,
        checkpoints = checkpoints,
        workspace = workspace,
        linuxEnv = linuxEnv,
        settings = settings,
        todoStore = todoStore,
        providers = providers,
        mcp = mcp,
        repoMap = repoMap,
        chatModels = chatModels,
        canRun = runtimePolicy::canRun,
    )
    val githubRepositories = com.androidharness.app.github.GitHubWorkspaces(this)
    val automation = com.androidharness.app.automation.AutomationManager(this)
    val projectDeletion = com.androidharness.app.workspace.ProjectDeletion(this)
    internal var telegram = com.androidharness.app.telegram.TelegramManager(this)
    val terminal = com.androidharness.app.data.TerminalManager(appContext, linuxEnv, shizuku, runManager, shellRouter)
    val storage = com.androidharness.app.data.storage.StorageManager(this)
    val projectChecks = com.androidharness.app.data.ProjectCheckStore(appContext, terminal)

    /**
     * Applies a GitHub token change end-to-end: re-materialize the prefix
     * copies (token file + gitconfig rewrite), then redeploy the shell-tier
     * toolchain so the new auth is live without an app restart.
     */
    val nineRouter by lazy { com.androidharness.app.router.NineRouterManager(appContext, linuxEnv,
        runManager::acquireBackgroundKeepalive, runManager::releaseBackgroundKeepalive, providers, settings) }

    suspend fun refreshGitHubAuth() = linuxEnv.refreshGitHub(shizuku, strict = true)

    init {
        runtimePolicy.start(onBackgroundDisabled = {
            runManager.stopIfBackgroundDisabled()
            if (!runtimePolicy.canRun()) {
                // One unavailable device backend must not disable the policy observer.
                runCatching { telegram.suspendForBackground() }
                runCatching { ftpShare.stop() }
                runCatching { terminal.stopTerminal() }
                runCatching { backgroundProcesses.killAll() }
                AgentService.stop(appContext)
            }
        }, onAvailable = { telegram.restoreIfEnabled() })
        // models.dev thinking-capability catalog: serve the cached copy
        // synchronously, then refresh in the background (daily cadence).
        com.androidharness.app.llm.ModelsDev.load(appContext)
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            combine(settings.settings, providers.providers) { saved, available -> saved to available.map { it.id }.toSet() }
                .collect { (saved, availableIds) ->
                    val selected = listOf(saved.activeProviderId, saved.planningProviderId, saved.executionProviderId, saved.subagentProviderId)
                    if (selected.any { it != null && (com.androidharness.app.local.LocalModelCatalog.isLocal(it) || com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(it)) && it !in availableIds }) {
                        settings.clearMissingLocalProviderSelections(availableIds)
                    }
                }
        }
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Learned wire per Harness model (chat vs anthropic vs responses),
            // persisted by the first-request probe; mirrored into memory so
            // every request path reads it synchronously.
            providers.harnessWires.collect { com.androidharness.app.llm.HarnessProvider.pins = it }
        }
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Restore routes before fetching; failed refreshes leave the cache intact.
            providers.providers.first()
            when (val result = com.androidharness.app.llm.ModelCatalog.listModels(
                com.androidharness.app.llm.HarnessProvider.config,
                com.androidharness.app.llm.HarnessProvider.KEYLESS,
            )) {
                is com.androidharness.app.llm.ModelCatalog.Result.Models ->
                    providers.saveCatalog(com.androidharness.app.llm.HarnessProvider.ID, result.models)
                is com.androidharness.app.llm.ModelCatalog.Result.Failed -> Unit
            }
        }
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            com.androidharness.app.llm.ModelsDev.refresh(appContext)
        }
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settings.settings.collect {
                disabledSkills.set(it.disabledSkills)
                cavemanSettings.set(it)
                // The search key store moved to per-provider slots; attribute
                // the pre-split key to whichever provider was active first.
                if (searchKeyMigrated.compareAndSet(false, true)) {
                    keys.migrateLegacySearchKey(it.webSearchProvider)
                }
                webSearchProvider.set(it.webSearchProvider)
            }
        }
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            workspace.current.collect { fs ->
                projectSkillsDir = fs.shellRoot?.resolve(".harness/skills")
            }
        }
        // Regenerate the linker shims the moment the environment is ready: an
        // older format (broken argv[0] for coreutils applets) must not survive
        // an app update, and the header check makes this a no-op when current.
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            linuxEnv.state.collect {
                if (it is com.androidharness.app.data.env.EnvState.Ready) linuxEnv.ensureShims()
            }
        }
        // Eagerly deploy the shell-user toolchain copy the moment both the
        // Linux environment and Shizuku are ready, so every tier works from
        // the first command (foreground shell, background spawn, terminal).
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // include serviceState so a retry fires once the user service binds
            combine(linuxEnv.state, shizuku.state, shizuku.serviceState) { e, s, v -> Triple(e, s, v) }
                .collect { (e, s, v) ->
                    if (e is com.androidharness.app.data.env.EnvState.Ready &&
                        s == com.androidharness.app.data.env.ShizukuState.GRANTED &&
                        v.isReady
                    ) {
                        linuxEnv.ensureShellDeploy(shizuku)
                    }
                }
        }
        // Self-heal prefixes installed by older builds: relink dangling
        // termux-absolute symlinks, rewrite Termux shebangs, resume installs
        // that were interrupted and reinstall packages whose binaries
        // vanished. Silent when the prefix is already healthy; network
        // failures never demote a Ready environment (retried next launch).
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val repaired = linuxEnv.repairIfNeeded()
            // A repair changes prefix content (reinstalled pip, rewritten
            // shebangs) without necessarily changing the package-set hash, and
            // StateFlow conflates the unchanged Ready state so the deploy
            // collector above never re-fires, re-stage the shell-tier copy
            // explicitly so it picks the fixes up.
            if (repaired != null &&
                shizuku.state.value == com.androidharness.app.data.env.ShizukuState.GRANTED &&
                shizuku.serviceState.value.isReady
            ) {
                linuxEnv.ensureShellDeploy(shizuku)
            }
        }
    }
}
