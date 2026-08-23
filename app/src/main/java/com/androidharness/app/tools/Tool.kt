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
)

data class ToolResult(
    val ok: Boolean,
    val output: String,
)

class ToolFailure(message: String) : Exception(message)

interface Tool {
    val name: String
    val description: String
    val parametersSchema: JsonObject
    val isReadOnly: Boolean

    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

class ToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }

    fun get(name: String): Tool? = byName[name]

    fun schemas(readOnlyOnly: Boolean = false) = byName.values
        .filter { !readOnlyOnly || it.isReadOnly }
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
        ): ToolRegistry = ToolRegistry(
            listOf(
                ListDirTool(),
                ReadFileTool(),
                WriteFileTool(),
                EditFileTool(),
                MultiEditTool(),
                ApplyPatchTool(),
                SearchFilesTool(),
                GrepTool(),
                ShellTool(linuxEnv, shellRouter),
                ShellBackgroundTool(bgStore, linuxEnv),
                EnvStatusTool(shizuku, linuxEnv, shellRouter),
                BgListTool(bgStore),
                BgKillTool(bgStore),
                GitStatusTool(shellRouter, linuxEnv),
                GitDiffTool(shellRouter, linuxEnv),
                GitCommitTool(shellRouter, linuxEnv),
                CreateDirTool(),
                DeleteFileTool(),
                MoveFileTool(),
                WebFetchTool(httpClient),
                WebSearchTool(httpClient),
                HttpRequestTool(httpClient),
                AskUserTool(),
                TaskTool(),
                MemoryWriteTool(),
                TodoWriteTool(todoStore),
            )
        )
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
