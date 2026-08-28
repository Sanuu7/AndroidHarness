package com.androidharness.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.androidharness.app.agent.AgentEngine
import com.androidharness.app.agent.TodoStore
import com.androidharness.app.data.CheckpointStore
import com.androidharness.app.data.ImageStore
import com.androidharness.app.data.KeyStoreManager
import com.androidharness.app.data.ProviderRepository
import com.androidharness.app.data.SessionRepository
import com.androidharness.app.data.SettingsRepository
import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.llm.ProviderFactory
import com.androidharness.app.tools.ToolRegistry
import com.androidharness.app.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicReference

class HarnessApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(val appContext: Context) {
    /** Deep-link channel: run-result notifications deliver a session id here. */
    val pendingSessionId = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)

    val keys = KeyStoreManager(appContext)
    val settings = SettingsRepository(appContext)
    val providers = ProviderRepository(appContext, keys)

    private val db = Room.databaseBuilder(appContext, AppDatabase::class.java, "harness.db")
        .addMigrations(
            AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8,
        )
        // Only kicks in when no migration path exists (pre-v4 databases);
        // the v4→v5 path above preserves sessions and usage totals.
        .fallbackToDestructiveMigration(true)
        .build()
    val sessions = SessionRepository(db)
    val snippets = com.androidharness.app.data.SnippetRepository(db.dao())

    val workspace = WorkspaceManager(appContext, db.dao())
    val checkpoints = CheckpointStore(db.dao())
    val images = ImageStore(appContext)

    val todoStore = TodoStore()
    private val fetchClient = OkHttpClient()
    val linuxEnv =
        com.androidharness.app.data.env.LinuxEnvironmentManager(appContext) { keys.githubToken() }
    val shizuku = com.androidharness.app.data.env.ShizukuManager(appContext)
    val shellRouter = com.androidharness.app.data.env.ShellTierRouter(appContext, shizuku, linuxEnv)

    init {
        // Package-set changes must invalidate the deployed-copy cache, or the
        // privileged tier keeps serving the old toolchain until a restart.
        linuxEnv.deployStateListener = { shizuku.invalidateDeployState() }
    }

    val updates = com.androidharness.app.data.update.UpdateManager(appContext, shizuku)
    val backgroundProcesses = com.androidharness.app.data.BgProcessStore(
        appContext, linuxEnv, shizuku, workspaceRoot = workspace.appPrivateRoot,
    )

    @Volatile
    private var projectSkillsDir: java.io.File? = null
    private val disabledSkills = AtomicReference<Set<String>>(emptySet())

    val skills = com.androidharness.app.skills.SkillStore(
        bundled = com.androidharness.app.skills.SkillAssets.load(appContext.assets),
        userDir = java.io.File(appContext.filesDir, "skills").apply { mkdirs() },
        projectDir = { projectSkillsDir },
        disabled = { disabledSkills.get() },
    )
    val registry = ToolRegistry.default(fetchClient, todoStore, backgroundProcesses, linuxEnv, shizuku, shellRouter, skills)
    val engine = AgentEngine(
        providerFactory = { config -> ProviderFactory.create(config.type) },
        registry = registry,
        checkpointer = checkpoints,
        imageStore = images,
        linuxEnv = linuxEnv,
        shizuku = shizuku,
        skills = skills,
        todoStore = todoStore,
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
    )
    val terminal = com.androidharness.app.data.TerminalManager(appContext, linuxEnv, shizuku, runManager)

    /**
     * Applies a GitHub token change end-to-end: re-materialize the prefix
     * copies (token file + gitconfig rewrite), then redeploy the shell-tier
     * toolchain so the new auth is live without an app restart.
     */
    suspend fun refreshGitHubAuth() = linuxEnv.refreshGitHub(shizuku)

    init {
        // models.dev thinking-capability catalog: serve the cached copy
        // synchronously, then refresh in the background (weekly cadence).
        com.androidharness.app.llm.ModelsDev.load(appContext)
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            com.androidharness.app.llm.ModelsDev.refresh(appContext)
        }
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settings.settings.collect { disabledSkills.set(it.disabledSkills) }
        }
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            workspace.current.collect { fs ->
                projectSkillsDir = fs.shellRoot?.resolve(".harness/skills")
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
                        v == com.androidharness.app.data.env.UserServiceState.BOUND_READY
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
            // collector above never re-fires — re-stage the shell-tier copy
            // explicitly so it picks the fixes up.
            if (repaired != null &&
                shizuku.state.value == com.androidharness.app.data.env.ShizukuState.GRANTED &&
                shizuku.serviceState.value == com.androidharness.app.data.env.UserServiceState.BOUND_READY
            ) {
                linuxEnv.ensureShellDeploy(shizuku)
            }
        }
    }
}
