package com.androidharness.app.tools

import com.androidharness.app.workspace.ChatWorkspaceBinding
import com.androidharness.app.workspace.ChatWorkspaceFs
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.serialization.json.*

/** Keep routing and access checks identical for advertised tools, main runs and nested tasks. */
object WorkspaceToolRouting {
    private val fileTools = setOf("list_dir", "read_file", "file_info", "write_file", "edit_file", "multi_edit",
        "apply_patch", "search_files", "grep", "create_dir", "delete_file", "move_file", "read_image",
        "memory_write", "memory_read", "memory_search", "file_history", "file_restore")
    private val independentTools = setOf("reference_read", "search_agent_knowledge", "read_chat_text", "zoom_chat_image", "offer_code_handoff", "create_artifact", "read_artifact", "read_tool_output", "ask_user", "task", "todo_write", "web_fetch", "web_search", "http_request", "read_logcat", "browser_open_external", "remember", "recall", "github_api", "github_search", "github_file", "skill_view", "skills_list", "visual_guide")
    private fun requiresCommandPath(name: String) = name == "shell" || name == "shell_background" || name.startsWith("git_")

    fun available(tool: Tool, ctx: ToolContext): Boolean {
        val selected = ctx.workspace as? ChatWorkspaceFs ?: return tool.isAvailable(ctx) &&
            (!requiresCommandPath(tool.name) || ctx.workspace.shellRoot != null || ctx.workspace is com.androidharness.app.workspace.SshFs)
        if (tool.name in independentTools) return tool.isAvailable(ctx)
        if (selected.hasReadOnly && tool.name !in fileTools) return false
        return selected.bindings.any { binding ->
            (tool.isReadOnly || binding.canEdit) && available(tool, ctx.copy(workspace = binding.fs, sandboxOff = false))
        }
    }

    fun schema(tool: Tool, workspace: WorkspaceFs?): JsonObject {
        val selected = workspace as? ChatWorkspaceFs ?: return tool.parametersSchema
        if (tool.name in independentTools) return tool.parametersSchema
        return JsonObject(tool.parametersSchema + ("properties" to JsonObject(
            (tool.parametersSchema["properties"] as? JsonObject).orEmpty() + ("workspace_id" to buildJsonObject {
                put("type", "string")
                put("description", "Selected workspace id. Omit for the primary workspace. Paths are relative to this workspace.")
                putJsonArray("enum") { selected.bindings.forEach { add(it.id) } }
            }),
        )))
    }

    data class Route(val workspace: WorkspaceFs, val binding: ChatWorkspaceBinding? = null, val selected: ChatWorkspaceFs? = null) {
        fun path(path: String) = if (binding != null && selected != null) selected.key(binding.id, path) else path
        val label: String? get() = binding?.name
    }

    fun route(tool: Tool, args: JsonObject, workspace: WorkspaceFs, context: ToolContext = ToolContext(workspace)): Route {
        val selected = workspace as? ChatWorkspaceFs ?: return Route(workspace).also {
            if (!available(tool, context)) throw ToolFailure("${tool.name} is unavailable in this workspace or its connection is no longer ready.")
        }
        if (tool.name in independentTools) {
            if (!tool.isAvailable(context)) throw ToolFailure("${tool.name} is unavailable in this chat or its connection is no longer ready.")
            return Route(workspace)
        }
        val id = args["workspace_id"]?.let {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
                ?: throw ToolFailure("workspace_id must be a selected workspace id.")
        }
        val binding = selected.binding(id)
        if (!tool.isReadOnly && !binding.canEdit) throw ToolFailure("${binding.name} is read only in this chat. Open Workspaces to allow edits.")
        if (selected.hasReadOnly && tool.name !in fileTools) throw ToolFailure(
            "Commands and integrations are unavailable while a selected workspace is read only. Use file tools, or change access in Workspaces.",
        )
        // File tools get grant-preserving nodes; executable tools need their actual SSH/local identity.
        val target = if (tool.name in fileTools) selected.fileAccess(binding) else binding.fs
        if (!available(tool, context.copy(workspace = binding.fs, sandboxOff = false))) throw ToolFailure("${tool.name} is unavailable in ${binding.name}.")
        return Route(target, binding, selected)
    }

    fun instructions(workspace: ChatWorkspaceFs): String = buildString {
        append("\nSelected workspaces for this chat (never infer access from other chats):\n")
        workspace.bindings.forEach { binding ->
            append("- workspace_id=\"").append(binding.id).append("\": ")
                .append(binding.name.replace('\n', ' ')).append(if (binding.id == workspace.primaryId) " (primary)" else "")
                .append(if (binding.canEdit) " — can edit" else " — read only").append('\n')
            append("  ").append(when {
                binding.fs is com.androidharness.app.workspace.SshFs -> "SSH host: ${binding.fs.displayPath}; commands run remotely."
                binding.fs.shellRoot != null -> "Device folder: ${binding.fs.displayPath}; commands run on the device."
                else -> "Folder access: file tools only; commands are unavailable for this workspace."
            }).append('\n')
        }
        append("Use workspace_id on workspace tools to target a project; omit it only for the primary. Tool paths stay relative to that project's root. Never combine roots in one patch or move; read then write when copying between projects. Identify the workspace when reporting work. Read the target project's AGENTS.md/HARNESS.md before changing it. Saved UI paths beginning @workspaces/ include a stable workspace id; use workspace_id and a relative path in tools.\n")
        if (workspace.hasReadOnly) append("Commands, Git and integrations are unavailable while any selected workspace is read only. Use the file tools. Ask the user to change access from the workspace chip if needed; never bypass it with another tool.\n")
        else append("Commands and Git run in the workspace selected by workspace_id, using that workspace's existing execution tier. Full access does not lift selected-workspace path checks.\n")
    }
}
