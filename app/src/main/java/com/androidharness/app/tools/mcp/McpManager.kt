package com.androidharness.app.tools.mcp

import android.content.Context
import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.tools.Tool
import com.androidharness.app.tools.ToolFailure
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** Global server list persisted to filesDir/mcp-servers.json, like BgProcessStore. */
class McpManager(
    private val context: Context,
    private val linuxEnv: LinuxEnvironmentManager,
) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val storeFile = File(context.filesDir, "mcp-servers.json")
    private val ioLock = Mutex()

    private val _servers = MutableStateFlow<List<McpServerConfig>>(loadServers())
    val servers: StateFlow<List<McpServerConfig>> = _servers

    private val _statuses = MutableStateFlow<Map<String, McpServerStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, McpServerStatus>> = _statuses

    private val connections = HashMap<String, McpConnection>()

    private fun loadServers(): List<McpServerConfig> = runCatching {
        if (storeFile.exists()) json.decodeFromString<List<McpServerConfig>>(storeFile.readText())
        else emptyList()
    }.getOrDefault(emptyList())

    private suspend fun persist(list: List<McpServerConfig>) {
        _servers.value = list
        ioLock.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
                    tmp.writeText(json.encodeToString(list))
                    tmp.renameTo(storeFile)
                }
            }
        }
    }

    suspend fun addServer(config: McpServerConfig) =
        persist(_servers.value.filterNot { it.name == config.name } + config)

    suspend fun removeServer(name: String) {
        disconnect(name)
        persist(_servers.value.filterNot { it.name == name })
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
    private suspend fun connectionFor(config: McpServerConfig, cwd: File): McpConnection? {
        synchronized(connections) { connections[config.name] }?.let { existing ->
            if (existing.isAlive) return existing
            synchronized(connections) { connections.remove(config.name) }
            existing.close()
        }
        tryConnect(config, cwd)?.let {
            synchronized(connections) { connections[config.name] = it }
            return it
        }
        // One retry: server binaries sometimes crash once on a cold start.
        tryConnect(config, cwd)?.let {
            synchronized(connections) { connections[config.name] = it }
            return it
        }
        return null
    }

    private suspend fun tryConnect(config: McpServerConfig, cwd: File): McpConnection? {
        _statuses.update { it + (config.name to McpServerStatus("connecting")) }
        val conn = McpConnection(config.name, config, processFactory(config))
        try {
            conn.connect(cwd)
            _statuses.update {
                it + (config.name to McpServerStatus("connected", toolCount = conn.tools.size))
            }
            return conn
        } catch (e: Exception) {
            conn.close()
            _statuses.update {
                it + (config.name to McpServerStatus("failed", error = e.message ?: "unknown error"))
            }
            return null
        }
    }

    /**
     * Spawns the server as an app-tier child via the same builder the shell
     * tool uses (linker-launched bash + Termux-prefix env), so plain command
     * names like `npx` or `python3` resolve. Stderr goes to a cache log.
     */
    private fun processFactory(config: McpServerConfig): (File) -> Process = { cwd ->
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
        pb.start()
    }

    /** Fresh connection for the Test-connection button (never cached). */
    suspend fun testConnection(config: McpServerConfig): Result<Int> = withContext(Dispatchers.IO) {
        val conn = McpConnection(config.name, config, processFactory(config))
        try {
            conn.connect(context.filesDir)
            Result.success(conn.tools.size)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn.close()
        }
    }

    private fun logFile(name: String): File =
        File(context.cacheDir, "mcp/${McpNames.sanitizeComponent(name)}.log")
            .apply { parentFile?.mkdirs() }

    private fun String.shellQuoted(): String = "'" + replace("'", "'\\''") + "'"
}
