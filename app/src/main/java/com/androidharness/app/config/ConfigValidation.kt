package com.androidharness.app.config

import kotlinx.serialization.json.*

/** Runtime validation is shared by the editor, preview, MCP gate and run startup. */
object ConfigValidation {
    fun validate(root: ConfigValue, texts: Map<String, String>, complete: Boolean = true) {
        fun bad(v: ConfigValue, message: String): Nothing {
            val prefix = texts[v.source].orEmpty().take(v.offset)
            throw HarnessConfigException(v.source, prefix.count { it == '\n' } + 1,
                v.offset - (prefix.lastIndexOf('\n') + 1) + 1, message)
        }
        fun obj(v: ConfigValue, keys: Set<String>? = null) {
            if (v.json !is JsonObject) bad(v, "Expected an object.")
            keys?.let { allowed -> v.fields.keys.firstOrNull { it !in allowed }?.let { bad(v.fields.getValue(it), "Unknown property '$it'.") } }
        }
        fun string(v: ConfigValue) { if (v.string() == null) bad(v, "Expected a string.") }
        fun bool(v: ConfigValue) { if ((v.json as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == null) bad(v, "Expected true or false.") }
        fun strings(v: ConfigValue) {
            if (v.json !is JsonArray) bad(v, "Expected an array of strings.")
            v.items.forEach { string(it); if (it.string().isNullOrBlank()) bad(it, "Empty entries are not allowed.") }
        }
        fun optional(v: ConfigValue, key: String, check: (ConfigValue) -> Unit) { v[key]?.let(check) }
        obj(root, setOf("\$schema", "instructions", "references", "skills", "plugins", "mcp", "permissions", "agents", "default_agent", "memory"))
        optional(root, "\$schema", ::string)
        optional(root, "instructions", ::strings)
        root["references"]?.let { refs ->
            if (refs.json !is JsonArray) bad(refs, "references must be an array.")
            refs.items.forEach { ref ->
                obj(ref, setOf("title", "path", "glob"))
                if (ref["path"] == null) bad(ref, "A reference needs a path.")
                optional(ref, "path", ::string); optional(ref, "title", ::string); optional(ref, "glob", ::bool)
            }
        }
        root["skills"]?.let { v ->
            obj(v, setOf("paths", "enable", "disable", "auto_load"))
            v.fields.filterValues { it.json !is JsonNull }.values.forEach(::strings)
        }
        root["plugins"]?.let { v ->
            obj(v, setOf("paths", "npm", "enable", "disable"))
            v.fields.filterValues { it.json !is JsonNull }.values.forEach(::strings)
        }
        root["mcp"]?.let { mcp ->
            obj(mcp)
            mcp.fields.filterValues { it.json !is JsonNull }.forEach { (name, v) ->
                if (!Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,31}").matches(name)) bad(v, "MCP names need 1–32 letters, digits, '-' or '_'.")
                obj(v, setOf("type", "command", "args", "env", "url", "headers", "enabled"))
                optional(v, "enabled", ::bool); optional(v, "args", ::strings)
                optional(v, "type", ::string); optional(v, "url", ::string)
                v["type"]?.let { if (it.string() !in setOf("local", "remote", "stdio", "http", "sse")) bad(it, "MCP type must be local, remote, stdio, http or sse.") }
                v["command"]?.let {
                    if (it.json is JsonArray) { strings(it); if (it.items.isEmpty()) bad(it, "MCP command cannot be empty.") }
                    else { string(it); if (it.string().isNullOrBlank()) bad(it, "MCP command cannot be empty.") }
                }
                if (complete && v["type"]?.string() in setOf("local", "stdio") && v["command"] == null) bad(v, "Local MCP needs a command.")
                if (complete && v["type"]?.string() in setOf("remote", "http", "sse") && v["url"] == null) bad(v, "Remote MCP needs a URL.")
                for (key in listOf("env", "headers")) v[key]?.let { map -> obj(map); map.fields.values.forEach(::string) }
                if (complete && v["command"] == null && v["url"] == null) bad(v, "MCP server needs a command or URL.")
                if (v["command"] != null && v["url"] != null) bad(v, "Choose a command or URL, not both.")
                v["url"]?.let { if (!it.string().orEmpty().startsWith("https://") && !it.string().orEmpty().startsWith("http://") &&
                    listOf("{env:", "{file:", "{secret:").none { prefix -> it.string().orEmpty().contains(prefix) }) bad(it, "MCP URL must use http:// or https://.") }
            }
            val normalized = mcp.fields.filterValues { it.json !is JsonNull }.keys.map { it.lowercase().replace('-', '_') }
            if (normalized.distinct().size != normalized.size) bad(mcp, "MCP names collide after tool-name normalization.")
        }
        root["permissions"]?.let { v ->
            obj(v, setOf("shell", "edit", "delete", "network", "outside_workspace"))
            v.fields.filterValues { it.json !is JsonNull }.values.forEach { if (it.string() !in setOf("allow", "ask", "deny")) bad(it, "Permission must be allow, ask or deny.") }
        }
        root["agents"]?.let { agents ->
            obj(agents)
            agents.fields.filterValues { it.json !is JsonNull }.forEach { (name, v) ->
                if (!Regex("[a-zA-Z0-9_-]{1,48}").matches(name)) bad(v, "Agent names need 1–48 letters, digits, '-' or '_'.")
                obj(v, setOf("description", "instructions", "tools"))
                optional(v, "description", ::string); optional(v, "instructions", ::strings)
                v["tools"]?.let { tools -> obj(tools); tools.fields.values.forEach(::bool) }
            }
        }
        optional(root, "default_agent", ::string)
        root["default_agent"]?.let { if (complete && it.string() != "default" && root["agents"]?.get(it.string().orEmpty()) == null) bad(it, "default_agent must name an existing agent.") }
        root["memory"]?.let { v ->
            obj(v, setOf("file", "topics_dir", "auto_recall", "max_core_lines"))
            optional(v, "auto_recall", ::bool)
            for (key in listOf("file", "topics_dir")) v[key]?.let { path ->
                string(path)
                val s = path.string().orEmpty()
                if (s.isBlank() || s.startsWith('/') || s.startsWith('~') || s.split('/', '\\').any { it == ".." } || '\\' in s)
                    bad(path, "Memory paths must be relative to the workspace.")
            }
            v["max_core_lines"]?.let { if ((it.json as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull !in 1..500) bad(it, "max_core_lines must be an integer from 1 to 500.") }
        }
    }
}
