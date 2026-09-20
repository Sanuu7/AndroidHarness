package com.androidharness.app.tools

import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.PkgMeta
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class PkgInstallTool(
    private val isReady: () -> Boolean,
    private val installedPackages: () -> List<String>,
    private val installPackages: suspend (List<String>) -> Unit,
) : Tool {

    constructor(linuxEnv: LinuxEnvironmentManager) : this(
        isReady = { linuxEnv.isReady },
        installedPackages = { linuxEnv.installedPackages() },
        installPackages = { linuxEnv.install(it) },
    )

    override val name = "pkg_install"
    override val description =
        "Install one or more packages from the Linux toolchain repository (e.g. 'ripgrep', 'jq', 'clang', 'rust', 'tmux', 'curl', 'tree', 'openjdk-17'). " +
        "Dependencies are resolved and installed automatically. " +
        "NOTE: Requires explicit user approval before downloading packages."

    override val parametersSchema = Schema.obj(
        mapOf(
            "packages" to Schema.array(
                Schema.string("Package name to install"),
                "List of package names to install, e.g. [\"ripgrep\", \"jq\"]."
            ),
            "reason" to Schema.string("Short explanation of why these packages are needed for the current task."),
        ),
        required = listOf("packages"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val remote = ctx.workspace as? com.androidharness.app.workspace.SshFs
        if (remote == null && !isReady()) {
            return ToolResult(false, "Linux environment is not ready or not installed. The environment must be installed first.")
        }
        val pkgs = when (val p = args["packages"]) {
            is JsonArray -> p.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }.filter { it.isNotEmpty() }
            else -> listOfNotNull((args["package"] as? JsonPrimitive)?.content?.trim()).filter { it.isNotEmpty() }
        }
        if (pkgs.isEmpty()) {
            return ToolResult(false, "Missing package name(s) to install.")
        }

        if (remote != null) {
            require(pkgs.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9+_.-]*")) }) { "Invalid package name" }
            val names = pkgs.joinToString(" ") { com.androidharness.app.workspace.sshQuote(it) }
            val result = remote.run("if command -v pkg >/dev/null; then pkg install -y $names; elif command -v apt-get >/dev/null; then apt-get install -y -- $names; else echo 'Install these packages with the host package manager'; exit 1; fi", timeoutMs = 600_000)
            return ToolResult(result.exitCode == 0, result.rawOutput + result.rawStderr + "\n" + result.note.orEmpty())
        }
        return try {
            installPackages(pkgs)
            val newlyInstalled = installedPackages().filter { it in pkgs }
            val listText = if (newlyInstalled.isNotEmpty()) newlyInstalled.joinToString(", ") else pkgs.joinToString(", ")
            ToolResult(
                ok = true,
                output = "Successfully installed: $listText. Binaries are now ready in the shell PATH."
            )
        } catch (e: Exception) {
            ToolResult(false, "Package installation failed: ${e.message}")
        }
    }
}

class PkgSearchTool(
    private val searcher: suspend (String) -> List<PkgMeta>,
    private val installedPackages: () -> List<String>,
) : Tool {

    constructor(linuxEnv: LinuxEnvironmentManager) : this(
        searcher = { linuxEnv.searchPackages(it) },
        installedPackages = { linuxEnv.installedPackages() },
    )

    override val name = "pkg_search"
    override val description =
        "Search the Linux toolchain repository for available packages by keyword or tool name."

    override val parametersSchema = Schema.obj(
        mapOf(
            "query" to Schema.string("Keyword or package name to search for."),
        ),
        required = listOf("query"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult(false, "Query parameter is required.")

        (ctx.workspace as? com.androidharness.app.workspace.SshFs)?.let { remote ->
            val q = com.androidharness.app.workspace.sshQuote(query)
            val r = remote.run("if command -v apt-cache >/dev/null; then apt-cache search -- $q; else echo 'Use the host package manager to search'; exit 1; fi", timeoutMs = 30_000)
            return ToolResult(r.exitCode == 0, r.rawOutput + r.rawStderr)
        }
        return try {
            val matches = searcher(query).take(25)
            if (matches.isEmpty()) {
                ToolResult(true, "No packages found matching '$query'.")
            } else {
                val installed = installedPackages().toSet()
                val text = buildString {
                    append("Found ${matches.size} package(s):\n")
                    matches.forEach { meta ->
                        val status = if (meta.name in installed) "[installed]" else ""
                        val sizeKb = (meta.size + 1023) / 1024
                        append("• ${meta.name} (${meta.version}) - ${sizeKb} KB $status\n")
                        if (meta.description.isNotBlank()) {
                            append("  ${meta.description}\n")
                        }
                    }
                }
                ToolResult(true, text.trimEnd())
            }
        } catch (e: Exception) {
            ToolResult(false, "Package search failed: ${e.message}")
        }
    }
}

class PkgListTool(
    private val installedPackages: () -> List<String>,
) : Tool {

    constructor(linuxEnv: LinuxEnvironmentManager) : this(
        installedPackages = { linuxEnv.installedPackages() },
    )

    override val name = "pkg_list"
    override val description = "List all packages currently installed in the Linux environment."
    override val parametersSchema = Schema.obj(emptyMap())
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        (ctx.workspace as? com.androidharness.app.workspace.SshFs)?.let { remote ->
            val r = remote.run("if command -v dpkg-query >/dev/null; then dpkg-query -W; else echo 'Use the host package manager to list packages'; exit 1; fi", timeoutMs = 30_000)
            return ToolResult(r.exitCode == 0, r.rawOutput + r.rawStderr)
        }
        val installed = installedPackages()
        return if (installed.isEmpty()) {
            ToolResult(true, "No packages currently installed.")
        } else {
            ToolResult(true, "Installed packages (${installed.size}):\n" + installed.sorted().joinToString(", "))
        }
    }
}
