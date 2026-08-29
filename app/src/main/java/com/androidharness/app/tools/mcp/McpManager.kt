package com.androidharness.app.tools.mcp

import android.content.Context
import android.net.Uri
import com.androidharness.app.data.KeyStoreManager
import com.androidharness.app.data.env.EnvState
import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.tools.Tool
import com.androidharness.app.tools.ToolFailure
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Global server list persisted to filesDir/mcp-servers.json, like BgProcessStore.
 * Supports stdio servers (spawned as app-tier children) and remote http/sse
 * servers, including the MCP OAuth 2.1 flow: a 401 during connect flips the
 * server to the "auth" state, [startAuthentication] runs discovery +
 * dynamic client registration and returns the browser URL, and
 * [completeAuthentication] finishes the PKCE exchange from the redirect.
 */
class McpManager(
    private val context: Context,
    private val linuxEnv: LinuxEnvironmentManager,
    private val keys: KeyStoreManager,
) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val storeFile = File(context.filesDir, "mcp-servers.json")
    private val ioLock = Mutex()
    private val httpClient = mcpHttpClient()

    private val _configTampered = MutableStateFlow(false)
    /**
     * True when mcp-servers.json failed its integrity check (edited out-of-band
     * or corrupted) and its contents were refused. Re-saving any server clears it.
     */
    val configTampered: StateFlow<Boolean> = _configTampered

    private val _servers = MutableStateFlow<List<McpServerConfig>>(loadServers())
    val servers: StateFlow<List<McpServerConfig>> = _servers

    private val _statuses = MutableStateFlow<Map<String, McpServerStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, McpServerStatus>> = _statuses

    private val connections = HashMap<String, McpConnection>()

    /** Serializes connection creation per server so a startup reconnect and
     * an early run cannot spawn two processes for the same name. */
    private val connectLocks = HashMap<String, Mutex>()
    private fun lockFor(name: String): Mutex =
        synchronized(connectLocks) { connectLocks.getOrPut(name) { Mutex() } }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** "Connected at least once" markers, kept so a restart can reconnect. */
    private val statusFile = File(context.filesDir, "mcp-status.json")
    private var storedStatuses: Map<String, McpStoredStatus> =
        runCatching { McpReconnectPolicy.parseStored(statusFile.readText()) }.getOrDefault(emptyMap())

    init {
        autoReconnect()
    }

    /** resource_metadata URLs from each server's last 401 challenge. */
    private val authChallenges = ConcurrentHashMap<String, String?>()

    private data class PendingAuth(
        val serverName: String,
        val state: String,
        val verifier: String,
        val context: McpOAuthContext,
    )
    @Volatile private var pendingAuth: PendingAuth? = null
    private val oauthMutexes = HashMap<String, Mutex>()

    private fun loadServers(): List<McpServerConfig> {
        if (!storeFile.exists()) return emptyList()
        val text = runCatching { storeFile.readText() }.getOrNull() ?: return emptyList()
        val parsed = runCatching { json.decodeFromString<List<McpServerConfig>>(text) }.getOrNull()
        // Integrity: the .hmac sidecar is written only by the app alongside each
        // save. A mismatch means the file was edited out-of-band or corrupted,
        // refuse the contents loudly instead of spawning whatever it now defines.
        val expected = computeConfigHmac(text.toByteArray())
        val stored = runCatching { configHmacFile().readText().trim() }.getOrNull()
        if (expected != null && stored != null && stored != expected) {
            runCatching {
                storeFile.delete()
                configHmacFile().delete()
            }
            _configTampered.value = true
            return emptyList()
        }
        if (expected != null && stored == null) {
            // First launch after this change existed: adopt the current file.
            runCatching { configHmacFile().writeText(expected) }
        }
        return parsed ?: emptyList()
    }

    private suspend fun persist(list: List<McpServerConfig>) {
        _servers.value = list
        ioLock.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
                    tmp.writeText(json.encodeToString(list))
                    tmp.renameTo(storeFile)
                    computeConfigHmac(storeFile.readBytes())?.let {
                        configHmacFile().writeText(it)
                    }
                }
            }
        }
        // An app-side save re-establishes integrity after any tamper refusal.
        _configTampered.value = false
    }

    private fun configHmacFile() = File(storeFile.parentFile, storeFile.name + ".hmac")

    /**
     * HMAC-SHA256 over the config bytes, keyed by a non-exportable AndroidKeyStore
     * key. Null when Keystore is unavailable, verification then degrades to
     * accept, never to bricking the server list.
     */
    private fun computeConfigHmac(data: ByteArray): String? = runCatching {
        val key = runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey("mcp_config_hmac", null) as? javax.crypto.SecretKey) ?: run {
                val gen = javax.crypto.KeyGenerator.getInstance("HmacSHA256", "AndroidKeyStore")
                gen.init(
                    android.security.keystore.KeyGenParameterSpec.Builder(
                        "mcp_config_hmac",
                        android.security.keystore.KeyProperties.PURPOSE_SIGN or
                            android.security.keystore.KeyProperties.PURPOSE_VERIFY,
                    ).setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256).build(),
                )
                gen.generateKey()
            }
        }.getOrNull() ?: return@runCatching null
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(key)
        android.util.Base64.encodeToString(mac.doFinal(data), android.util.Base64.NO_WRAP)
    }.getOrNull()

    suspend fun addServer(config: McpServerConfig) =
        persist(_servers.value.filterNot { it.name == config.name } + config)

    suspend fun removeServer(name: String) {
        disconnect(name)
        keys.removeMcpOAuthState(name)
        persist(_servers.value.filterNot { it.name == name })
        storedStatuses = storedStatuses - name
        runCatching { statusFile.writeText(McpReconnectPolicy.serializeStored(storedStatuses)) }
    }

    suspend fun setServerEnabled(name: String, enabled: Boolean) =
        persist(_servers.value.map { if (it.name == name) it.copy(enabled = enabled) else it })

    fun disconnect(name: String) {
        synchronized(connections) { connections.remove(name) }?.close()
        _statuses.update { it - name }
    }

    fun disconnectAll() {
        synchronized(connections) { connections.values.toList() }.forEach { it.close() }
        synchronized(connections) { connections.clear() }
        _statuses.value = emptyMap()
    }

    /** Server status snapshot for Settings; entries exist once a connect was attempted. */
    fun statusFor(name: String): McpServerStatus? = _statuses.value[name]

    /** Per-workspace `.harness/mcp.json` configs (ecosystem-standard format). */
    fun workspaceConfigs(workspace: WorkspaceFs): List<McpServerConfig> = runCatching {
        val node = workspace.resolve(McpNames.WORKSPACE_CONFIG)
        if (node.exists && node.isFile) McpConfigParser.parse(node.readText()) else emptyList()
    }.getOrDefault(emptyList())

    /**
     * Tools for the next run: enabled global servers plus the workspace file's
     * servers (workspace wins on name collisions). Servers that fail to
     * connect are skipped with a recorded status; they never block the run.
     */
    suspend fun activeTools(workspace: WorkspaceFs): List<Tool> {
        val global = _servers.value.filter { it.enabled }
        val ws = workspaceConfigs(workspace)
            .filter { ws -> global.none { it.name.equals(ws.name, ignoreCase = true) } }
        val configs = global + ws
        if (configs.isEmpty()) return emptyList()

        val cwd = workspace.shellRoot ?: context.filesDir
        val tools = mutableListOf<Tool>()
        for (config in configs) {
            val conn = connectionFor(config, cwd) ?: continue
            tools += conn.tools.map { info ->
                McpToolAdapter(config.name, info, conn) { disconnect(config.name) }
            }
        }
        return tools
    }

    /** Live connection if healthy; otherwise up to two fresh connect attempts. */
    private suspend fun connectionFor(config: McpServerConfig, cwd: File): McpConnection? =
        lockFor(config.name).withLock {
            synchronized(connections) { connections[config.name] }?.let { existing ->
                if (existing.isAlive) return@withLock existing
                synchronized(connections) { connections.remove(config.name) }
                existing.close()
            }
            tryConnect(config, cwd)?.let { return@withLock it }
            // One retry: server binaries sometimes crash once on a cold start.
            tryConnect(config, cwd)?.let { return@withLock it }
            null
        }

    /**
     * Records that this server connected successfully, but only for app-side
     * servers: workspace `.harness/mcp.json` entries must never earn an
     * auto-reconnect (they would spawn commands at startup, the D1 vector).
     */
    private fun recordConnected(name: String, toolCount: Int) {
        if (_servers.value.none { it.name == name }) return
        storedStatuses = storedStatuses + (name to McpStoredStatus(toolCount, System.currentTimeMillis()))
        runCatching { statusFile.writeText(McpReconnectPolicy.serializeStored(storedStatuses)) }
    }

    /**
     * Startup auto-reconnect: servers that connected successfully before come
     * back on their own after the app dies or restarts, so MCP tools are
     * usable (and Settings shows real statuses) without a run or a manual
     * Test first. Remote servers connect immediately; stdio servers wait for
     * the toolchain to be Ready, because connecting before bash exists would
     * record a bogus failure.
     */
    private fun autoReconnect() {
        val candidates = McpReconnectPolicy.candidates(_servers.value, storedStatuses)
        if (candidates.isEmpty()) return
        val (remote, stdio) = candidates.partition { it.isRemote }
        remote.forEach { config ->
            scope.launch { connectionFor(config, context.filesDir) }
        }
        if (stdio.isNotEmpty()) {
            scope.launch {
                linuxEnv.state.first { it is EnvState.Ready }
                stdio.forEach { config -> connectionFor(config, context.filesDir) }
            }
        }
    }

    private suspend fun tryConnect(config: McpServerConfig, cwd: File): McpConnection? {
        _statuses.update { it + (config.name to McpServerStatus("connecting")) }
        val conn = McpConnection(
            serverName = config.name,
            config = config,
            processFactory = { cwd2 -> spawnStdio(config, cwd2) },
            authHeader = { authHeaderFor(config.name) },
            httpClient = httpClient,
        )
        try {
            conn.connect(cwd)
            _statuses.update {
                it + (config.name to McpServerStatus("connected", toolCount = conn.tools.size))
            }
            recordConnected(config.name, conn.tools.size)
            return conn
        } catch (e: McpAuthRequiredException) {
            conn.close()
            recordAuth(config.name, e)
            return null
        } catch (e: Exception) {
            conn.close()
            _statuses.update {
                it + (config.name to McpServerStatus("failed", error = e.message ?: "unknown error"))
            }
            return null
        }
    }

    /** Records an OAuth challenge so Settings shows the Authenticate button. */
    private fun recordAuth(name: String, e: McpAuthRequiredException) {
        authChallenges[name] = e.resourceMetadataUrl
        _statuses.update {
            it + (name to McpServerStatus(
                "auth",
                error = "Authorization required. Tap Authenticate to connect your account.",
                needsAuth = true,
            ))
        }
    }

    /**
     * Spawns a stdio server as an app-tier child via the same builder the
     * shell tool uses (linker-launched bash + Termux-prefix env), so plain
     * command names like `npx` or `python3` resolve. Stderr goes to a cache
     * log. Remote servers never touch this.
     */
    private fun spawnStdio(config: McpServerConfig, cwd: File): Process {
        if (linuxEnv.bashExecutable() == null) {
            throw ToolFailure(
                "The MCP server '${config.name}' needs node/python from the Linux environment. " +
                    "Install it in Settings → Terminal & environment first.",
            )
        }
        val commandLine = buildString {
            append(config.command.shellQuoted())
            config.args.forEach { append(' ').append(it.shellQuoted()) }
        }
        val pb = linuxEnv.shellProcessBuilder(commandLine)
        config.env.forEach { (k, v) -> pb.environment()[k] = v }
        pb.directory(cwd)
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile(config.name)))
        return pb.start()
    }

    /**
     * Fresh connection for the Test-connection button (never cached). Records
     * the same statuses a real connect would, so a Test that hits a 401
     * surfaces the Authenticate button without needing a run first.
     */
    suspend fun testConnection(config: McpServerConfig): Result<Int> = withContext(Dispatchers.IO) {
        _statuses.update { it + (config.name to McpServerStatus("connecting")) }
        val conn = McpConnection(
            serverName = config.name,
            config = config,
            processFactory = { cwd -> spawnStdio(config, cwd) },
            authHeader = { authHeaderFor(config.name) },
            httpClient = httpClient,
        )
        try {
            conn.connect(context.filesDir)
            _statuses.update {
                it + (config.name to McpServerStatus("connected", toolCount = conn.tools.size))
            }
            // A successful Test counts as "connected before", so this server
            // starts reconnecting on future launches too.
            recordConnected(config.name, conn.tools.size)
            Result.success(conn.tools.size)
        } catch (e: McpAuthRequiredException) {
            recordAuth(config.name, e)
            Result.failure(e)
        } catch (e: Exception) {
            _statuses.update {
                it + (config.name to McpServerStatus("failed", error = e.message ?: "unknown error"))
            }
            Result.failure(e)
        } finally {
            conn.close()
        }
    }

    // --- OAuth -------------------------------------------------------------------

    /**
     * Runs discovery + dynamic client registration and returns the browser
     * URL for the user to approve access. The PKCE verifier and state are
     * held in memory until [completeAuthentication] consumes the redirect.
     */
    suspend fun startAuthentication(name: String): Result<String> = withContext(Dispatchers.IO) {
        val config = _servers.value.firstOrNull { it.name == name }
            ?: return@withContext Result.failure(IllegalArgumentException("Unknown MCP server '$name'"))
        val url = config.url
            ?: return@withContext Result.failure(IllegalArgumentException(
                "Only remote (http/sse) MCP servers can be authenticated; stdio servers " +
                    "get credentials from their env config.",
            ))
        runCatching {
            val challenge = authChallenges[name]
            val ctx = McpOAuth.discover(httpClient, challenge, url)
                ?: throw McpOAuthException(
                    "The server did not advertise an OAuth provider. If it uses a static " +
                        "token instead, add it as a header (e.g. \"Authorization: Bearer …\") " +
                        "in the server's edit dialog.",
                )
            val registered = McpOAuth.registerClient(httpClient, ctx)
            val state = McpOAuth.createState()
            val verifier = McpOAuth.createVerifier()
            pendingAuth = PendingAuth(name, state, verifier, registered)
            McpOAuth.authorizationUrl(registered, state, McpOAuth.codeChallenge(verifier))
        }
    }

    /**
     * Consumes the browser redirect (androidharness://mcp/oauth?code=…&state=…):
     * verifies state, exchanges the code with PKCE, stores the tokens, and
     * reconnects so the status flips to connected. Returns the server name.
     */
    suspend fun completeAuthentication(stateParam: String?, code: String?): Result<String> =
        withContext(Dispatchers.IO) {
            val pending = pendingAuth
                ?: return@withContext Result.failure(IllegalStateException(
                    "No MCP authentication is in progress.",
                ))
            if (stateParam != pending.state) {
                return@withContext Result.failure(IllegalStateException(
                    "The authentication response did not match this app (state mismatch). " +
                        "Start over with Authenticate.",
                ))
            }
            if (code.isNullOrBlank()) {
                return@withContext Result.failure(IllegalStateException(
                    "The provider returned no authorization code (access was likely denied).",
                ))
            }
            runCatching {
                val tokens = McpOAuth.exchangeCode(httpClient, pending.context, code, pending.verifier)
                saveOAuthState(
                    pending.serverName,
                    McpOAuthState(pending.context, tokens.accessToken, tokens.refreshToken, tokens.expiresAtMs),
                )
                pendingAuth = null
                // Reconnect eagerly so the UI reflects success without a run.
                _servers.value.firstOrNull { it.name == pending.serverName }?.let { config ->
                    disconnect(config.name)
                    connectionFor(config, context.filesDir)
                }
                pending.serverName
            }.onFailure { pendingAuth = null }
        }

    /**
     * Bearer token supplier for remote transports: the stored access token
     * while valid, otherwise one refresh attempt (guarded per server).
     */
    private suspend fun authHeaderFor(name: String): String? {
        val state = loadOAuthState(name) ?: return null
        if (state.accessTokenValid()) return "Bearer ${state.accessToken}"
        val refreshToken = state.refreshToken ?: return null
        val mutex = synchronized(oauthMutexes) { oauthMutexes.getOrPut(name) { Mutex() } }
        return mutex.withLock {
            val current = loadOAuthState(name) ?: return@withLock null
            if (current.accessTokenValid()) return@withLock "Bearer ${current.accessToken}"
            val refreshed = runCatching {
                McpOAuth.refreshTokens(httpClient, current.context, current.refreshToken ?: return@withLock null)
            }.getOrNull() ?: return@withLock null
            val updated = current.copy(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken ?: current.refreshToken,
                expiresAtMs = refreshed.expiresAtMs,
            )
            saveOAuthState(name, updated)
            "Bearer ${updated.accessToken}"
        }
    }

    private fun loadOAuthState(name: String): McpOAuthState? = runCatching {
        keys.mcpOAuthState(name)?.let { json.decodeFromString<McpOAuthState>(it) }
    }.getOrNull()

    private fun saveOAuthState(name: String, state: McpOAuthState) {
        runCatching { keys.putMcpOAuthState(name, json.encodeToString(McpOAuthState.serializer(), state)) }
    }

    private fun logFile(name: String): File =
        File(context.cacheDir, "mcp/${McpNames.sanitizeComponent(name)}.log")
            .apply { parentFile?.mkdirs() }

    private fun String.shellQuoted(): String = "'" + replace("'", "'\\''") + "'"
}
