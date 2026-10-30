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
    val sessionId: String? = null,
    /** Scoped to this call by the engine; never shared across chats or concurrent installs. */
    val onPackageProgress: suspend (com.androidharness.app.data.env.PackageInstallStatus) -> Unit = {},
    val imageInputAllowed: Boolean = true,
)

data class ToolResult(
    val ok: Boolean,
    val output: String,
    /**
     * Optional image rendered inline in the chat transcript and resolved into
     * [ChatMessage.imageData] for vision-capable models.
     */
    val image: com.androidharness.app.core.ImageRef? = null,
    val artifact: com.androidharness.app.artifacts.ChatArtifact? = null,
    val execution: List<com.androidharness.app.data.env.ExecutionReceipt> = emptyList(),
    val research: com.androidharness.app.core.ResearchReceipt? = null,
)

class ToolFailure(message: String) : Exception(message)

interface Tool {
    val name: String
    val description: String
    val parametersSchema: JsonObject
    val isReadOnly: Boolean

    fun isAvailable(ctx: ToolContext): Boolean = true

    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

class ToolRegistry(private val tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }

    fun get(name: String): Tool? = byName[name]

    fun restrictedTo(names: Set<String>?): ToolRegistry =
        if (names == null) this else ToolRegistry(tools.filter { it.name in names })

    /** Run-scoped extras (MCP tools) merge on top of the static set. */
    fun withExtra(extra: List<Tool>): ToolRegistry =
        if (extra.isEmpty()) this else ToolRegistry(tools + extra)

    fun without(names: Set<String>): ToolRegistry =
        if (names.isEmpty()) this else ToolRegistry(tools.filterNot { it.name in names })

    fun schemas(readOnlyOnly: Boolean = false, context: ToolContext? = null) = byName.values
        .filter { (!readOnlyOnly || it.isReadOnly) && (context == null || WorkspaceToolRouting.available(it, context)) }
        .sortedBy { it.name }
        .map {
            com.androidharness.app.llm.ToolSchema(it.name, it.description, WorkspaceToolRouting.schema(it, context?.workspace))
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
            codeGraph: com.androidharness.app.data.env.CodeGraphManager,
            imageStore: com.androidharness.app.data.ImageStore? = null,
            browserController: com.androidharness.app.browser.BrowserController? = null,
            searchApi: () -> com.androidharness.app.tools.SearchApiConfig? = { null },
            artifactLoader: ArtifactLoader? = null,
            toolOutputLoader: ToolOutputLoader? = null,
            fileHistory: com.androidharness.app.data.WorkspaceFileHistory? = null,
            memory: com.androidharness.app.data.MemoryRepository? = null,
            searchApiRefresh: suspend () -> SearchApiConfig? = { searchApi() },
            dhizuku: com.androidharness.app.data.env.DhizukuManager? = null,
            deviceAccess: com.androidharness.app.data.env.DeviceAccessManager? = null,
            pullRequests: (() -> com.androidharness.app.github.PullRequestPublisher?)? = null,
            sessionMessages: suspend (String) -> List<com.androidharness.app.core.ChatMessage> = { emptyList() },
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
                ShellBackgroundTool(bgStore, linuxEnv, shellRouter),
                EnvStatusTool(shizuku, linuxEnv, shellRouter),
                DoctorTool(linuxEnv, shizuku, shellRouter, httpClient),
                ReadLogcatTool(shizuku, router = shellRouter),
                PkgInstallTool(linuxEnv),
                AndroidSdkInstallTool(linuxEnv),
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
                WebFetchTool(httpClient, searchApi = searchApi, refreshApi = searchApiRefresh,
                    renderedReader = browserController?.let { browser -> { budget -> BrowserReadTool(browser, budget) } }),
                WebSearchTool(httpClient, searchApi, refreshApi = searchApiRefresh),
                HttpRequestTool(httpClient) { linuxEnv.githubToken() },
                AskUserTool(),
                VisualGuideTool(),
                TaskTool(),
                MemoryWriteTool(),
                MemoryReadTool(),
                MemorySearchTool(),
                TodoWriteTool(todoStore),
                SkillViewTool(skills),
                SkillsListTool(skills),
                SkillManageTool(skills),
                CodeGraphExploreTool(codeGraph),
                CodeGraphNodeTool(codeGraph),
                CodeGraphImpactTool(codeGraph),
                CodeGraphAffectedTool(codeGraph),
                CodeGraphSyncTool(codeGraph),
            )
            if (dhizuku != null && deviceAccess != null) baseTools += DeviceOwnerShellTool(dhizuku, deviceAccess)
            if (pullRequests != null) baseTools += GitHubCreatePullRequestTool(pullRequests, sessionMessages)
            if (artifactLoader != null) {
                baseTools.add(CreateArtifactTool(artifactLoader))
                baseTools.add(ReadArtifactTool(artifactLoader))
            }
            if (toolOutputLoader != null) baseTools.add(ReadToolOutputTool(toolOutputLoader))
            if (fileHistory != null) {
                baseTools.add(FileHistoryTool(fileHistory))
                baseTools.add(FileRestoreTool(fileHistory))
            }
            if (memory != null) {
                baseTools.add(RememberTool(memory))
                baseTools.add(RecallTool(memory))
            }
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
                        BrowserOpenExternalTool(browserController),
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
