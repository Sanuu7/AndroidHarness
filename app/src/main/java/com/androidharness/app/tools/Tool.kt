package com.androidharness.app.tools

import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class ToolContext(
    val workspace: WorkspaceFs,
    /**
     * Full access mode: tool-internal sandbox layers (shell denylist re-check,
     * cwd containment) are off. The engine has already gated this; tools only
     * honor it, they never enable it themselves.
     */
    val sandboxOff: Boolean = false,
)

data class ToolResult(
    val ok: Boolean,
    val output: String,
    /**
     * Optional image rendered inline in the chat transcript and resolved into
     * [ChatMessage.imageData] for vision-capable models.
     */
    val image: com.androidharness.app.core.ImageRef? = null,
)

class ToolFailure(message: String) : Exception(message)

interface Tool {
    val name: String
    val description: String
    val parametersSchema: JsonObject
    val isReadOnly: Boolean

    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

class ToolRegistry(private val tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }

    fun get(name: String): Tool? = byName[name]

    /** Run-scoped extras (MCP tools) merge on top of the static set. */
    fun withExtra(extra: List<Tool>): ToolRegistry =
        if (extra.isEmpty()) this else ToolRegistry(tools + extra)

    fun schemas(readOnlyOnly: Boolean = false) = byName.values
        .filter { !readOnlyOnly || it.isReadOnly }
        .sortedBy { it.name }
        .map {
            com.androidharness.app.llm.ToolSchema(it.name, it.description, it.parametersSchema)
        }

    companion object {
        fun default(
            httpClient: okhttp3.OkHttpClient,
            todoStore: com.androidharness.app.agent.TodoStore,
            bgStore: com.androidharness.app.data.BgProcessStore,
            linuxEnv: com.androidharness.app.data.env.LinuxEnvironmentManager,
            shizuku: com.androidharness.app.data.env.ShizukuManager,
            shellRouter: com.androidharness.app.data.env.ShellTierRouter,
            skills: com.androidharness.app.skills.SkillStore,
            imageStore: com.androidharness.app.data.ImageStore? = null,
            browserController: com.androidharness.app.browser.BrowserController? = null,
            searchApi: () -> com.androidharness.app.tools.SearchApiConfig? = { null },
        ): ToolRegistry {
            val baseTools = mutableListOf<Tool>(
                ListDirTool(),
                ReadFileTool(),
                FileInfoTool(),
                WriteFileTool(),
                EditFileTool(),
                MultiEditTool(),
                ApplyPatchTool(),
                SearchFilesTool(),
                GrepTool(),
                ShellTool(linuxEnv, shellRouter),
                ShellBackgroundTool(bgStore, linuxEnv),
                EnvStatusTool(shizuku, linuxEnv, shellRouter),
                DoctorTool(linuxEnv, shizuku, shellRouter, httpClient),
                ReadLogcatTool(shizuku),
                PkgInstallTool(linuxEnv),
                PkgSearchTool(linuxEnv),
                PkgListTool(linuxEnv),
                BgListTool(bgStore),
                BgKillTool(bgStore),
                GitStatusTool(shellRouter, linuxEnv),
                GitDiffTool(shellRouter, linuxEnv),
                GitLogTool(shellRouter, linuxEnv),
                GitShowTool(shellRouter, linuxEnv),
                GitCommitTool(shellRouter, linuxEnv),
                GitBranchTool(shellRouter, linuxEnv),
                GitBranchManageTool(shellRouter, linuxEnv),
                GitCheckoutTool(shellRouter, linuxEnv),
                GitPushTool(shellRouter, linuxEnv),
                GitPullTool(shellRouter, linuxEnv),
                CreateDirTool(),
                DeleteFileTool(),
                MoveFileTool(),
                WebFetchTool(httpClient),
                WebSearchTool(httpClient, searchApi),
                HttpRequestTool(httpClient) { linuxEnv.githubToken() },
                AskUserTool(),
                TaskTool(),
                MemoryWriteTool(),
                MemoryReadTool(),
                MemorySearchTool(),
                TodoWriteTool(todoStore),
                SkillViewTool(skills),
                SkillsListTool(skills),
                SkillManageTool(skills),
            )
            if (imageStore != null) {
                baseTools.add(ReadImageTool(imageStore))
            }
            if (browserController != null) {
                baseTools.addAll(
                    listOf(
                        BrowserNavigateTool(browserController),
                        BrowserClickTool(browserController),
                        BrowserTypeTool(browserController),
                        BrowserScrollTool(browserController),
                        BrowserEvalTool(browserController),
                        BrowserGetDomTool(browserController),
                        BrowserGetLogsTool(browserController),
                        BrowserScreenshotTool(browserController),
                        BrowserWaitForTool(browserController),
                        BrowserBackTool(browserController),
                        BrowserForwardTool(browserController),
                        BrowserRefreshTool(browserController),
                        BrowserGetUrlTool(browserController),
                    )
                )
            }
            return ToolRegistry(baseTools)
        }
    }
}

object Schema {
    fun string(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    fun integer(description: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    fun boolean(description: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    fun array(items: JsonObject, description: String): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", items)
    }

    fun obj(
        properties: Map<String, JsonObject>,
        required: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            properties.forEach { (k, v) -> put(k, v) }
        }
        if (required.isNotEmpty()) {
            putJsonArray("required") { required.forEach { add(it) } }
        }
    }
}
