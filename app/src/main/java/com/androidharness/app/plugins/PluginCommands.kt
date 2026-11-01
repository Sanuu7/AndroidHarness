package com.androidharness.app.plugins

import kotlinx.serialization.json.*

/** What a pasted command, package, link or JSON snippet asks the plugin manager to do. Nothing here runs anything. */
sealed interface InstallRequest {
    data class Source(val origin: PluginOrigin) : InstallRequest
    /** A package runner (npx, pnpm dlx, bunx, uvx, pipx run): either an MCP server or an installer, decided from package metadata. */
    data class Run(val runner: String, val pkg: String, val version: String, val args: List<String>) : InstallRequest {
        val command get() = (listOf(if (runner == "uvx") "uvx" else "npx") + (if (runner == "uvx") emptyList() else listOf("-y")) +
            (pkg + if (version.isBlank() || version == "latest") "" else if (runner == "uvx") "==$version" else "@$version") + args)
            .joinToString(" ") { PluginInstallers.quote(it) }
    }
    data class Marketplace(val source: MarketplaceSource) : InstallRequest
    data class MarketplacePlugin(val plugin: String, val marketplace: String) : InstallRequest
}

object InstallCommands {
    private val npmName = Regex("(@[a-z0-9][a-z0-9._~-]*/)?[a-z0-9][a-z0-9._~-]*", RegexOption.IGNORE_CASE)
    private val repoShorthand = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}")
    private val runners = setOf("npx", "bunx", "uvx", "pnpx")

    fun tokenize(input: String): List<String> {
        val out = mutableListOf<String>(); val word = StringBuilder(); var quote: Char? = null; var any = false; var i = 0
        val text = input.replace("\\\r\n", " ").replace("\\\n", " ")
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null && c == quote -> quote = null
                quote == '"' && c == '\\' && i + 1 < text.length -> { word.append(text[++i]) }
                quote != null -> word.append(c)
                c == '\'' || c == '"' -> { quote = c; any = true }
                c == '\\' && i + 1 < text.length -> word.append(text[++i])
                c.isWhitespace() -> { if (word.isNotEmpty() || any) out += word.toString(); word.clear(); any = false }
                else -> word.append(c)
            }
            i++
        }
        if (word.isNotEmpty() || any) out += word.toString()
        return out
    }
    private fun splitVersion(spec: String): Pair<String, String> {
        val at = spec.lastIndexOf('@').takeIf { it > 0 }
        return if (at == null) spec to "latest" else spec.substring(0, at) to spec.substring(at + 1).ifBlank { "latest" }
    }
    private fun npm(spec: String): InstallRequest {
        if (spec.startsWith("github:")) return github(spec.removePrefix("github:"))
        if (spec.startsWith("https://") || spec.startsWith("git+")) return parse(spec.removePrefix("git+"))
        val (name, version) = splitVersion(spec)
        require(npmName.matches(name)) { "Not an npm package name: $spec" }
        return InstallRequest.Source(PluginOrigin("npm", name.lowercase(), version))
    }
    /** GitHub links may name a branch and folder: /tree/<ref>/<path>, or a file under /blob/<ref>/. */
    private fun github(value: String, skills: List<String> = emptyList()): InstallRequest {
        val text = value.trim().removeSuffix("/").removeSuffix(".git")
        val path = text.removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("github.com/")
        val parts = path.split('/')
        require(parts.size >= 2) { "Enter a GitHub repository as owner/name or its link." }
        val repo = "${parts[0]}/${parts[1].substringBefore('#')}"
        require(repoShorthand.matches(repo)) { "Not a GitHub repository: $value" }
        var ref = parts[1].substringAfter('#', "")
        var sub = ""
        if (parts.size >= 4 && parts[2] in setOf("tree", "blob")) {
            ref = parts[3]
            sub = parts.drop(4).joinToString("/")
            if (parts[2] == "blob") sub = sub.substringBeforeLast('/', "")
        }
        if (skills.size == 1 && sub.isBlank()) return InstallRequest.Source(PluginOrigin("github", repo, ref, entry = buildJsonObject {
            put("name", skills.single()); put("skills", buildJsonArray { add("skills/${skills.single()}") })
        }.toString(), name = skills.single()))
        return InstallRequest.Source(PluginOrigin("github", repo, ref, sub))
    }
    private fun marketplaceRepo(value: String): MarketplaceSource {
        val request = github(value) as InstallRequest.Source
        return MarketplaceSource(request.origin.target, request.origin.ref)
    }
    private fun mcp(name: String, server: JsonObject) = InstallRequest.Source(PluginOrigin("mcp", buildJsonObject { put(name, server) }.toString(), name = name))

    fun parse(input: String): InstallRequest {
        val trimmed = input.trim().lines().joinToString(" ") { it.trim().removePrefix("$ ").removePrefix("> ") }.trim()
        require(trimmed.isNotEmpty()) { "Paste a command, package, repository or link." }
        if (trimmed.startsWith("{")) {
            val obj = runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrElse { throw IllegalArgumentException("That JSON could not be read.") }
            val servers = (obj["mcpServers"] as? JsonObject) ?: (obj["servers"] as? JsonObject) ?: (obj["mcp"] as? JsonObject)
                ?: if ("command" in obj || "url" in obj) buildJsonObject { put("server", obj) } else obj
            require(servers.values.all { it is JsonObject }) { "Paste an MCP server configuration such as {\"mcpServers\": {...}}." }
            return InstallRequest.Source(PluginOrigin("mcp", servers.toString(), name = servers.keys.singleOrNull().orEmpty()))
        }
        // Use the first installing step of a chained command, such as "cd x && npx tool init".
        val segments = trimmed.split(Regex("\\s*(?:&&|\\|\\||;)\\s*")).filter { it.isNotBlank() }
        val segment = segments.firstOrNull { s -> tokenize(s).firstOrNull()?.let { it in runners + setOf("npm", "pnpm", "yarn", "bun", "claude", "git", "pipx", "uv", "/plugin") } == true }
            ?: segments.first()
        var words = tokenize(segment)
        while (words.firstOrNull()?.let { it == "sudo" || it == "env" || Regex("[A-Za-z_][A-Za-z0-9_]*=.*").matches(it) } == true) words = words.drop(1)
        require(words.isNotEmpty()) { "Paste a command, package, repository or link." }
        val head = words.first()
        val rest = words.drop(1)
        fun packageArgs(list: List<String>, valued: Set<String> = setOf("-p", "--package", "--registry", "--cache", "--prefix", "--from", "--with", "--python")): Pair<String?, List<String>> {
            var i = 0
            while (i < list.size && list[i].startsWith("-")) { if (list[i] in valued) i++; i++ }
            return list.getOrNull(i) to list.drop(i + 1)
        }
        fun runner(runner: String, list: List<String>): InstallRequest {
            val (spec, args) = packageArgs(list)
            require(spec != null) { "The command does not name a package to run." }
            val (pkg, version) = if (runner == "uvx") spec.split("==", limit = 2).let { it[0] to it.getOrElse(1) { "latest" } } else splitVersion(spec)
            if (runner != "uvx") when (pkg) {
                // Skill installers that only copy a GitHub repository's skills are handled without running them.
                "skills", "add-skill", "@vercel/skills", "skills-cli" -> {
                    val source = args.dropWhile { it == "add" || it == "install" || it.startsWith("-") }.firstOrNull()
                    if (source != null && !source.startsWith("-")) {
                        val chosen = args.windowed(2).filter { it[0] == "--skill" || it[0] == "-s" }.map { it[1] }
                        return github(source, chosen)
                    }
                }
                "@anthropic-ai/claude-code", "claude" -> return parse((listOf("claude") + args).joinToString(" ") { PluginInstallers.quote(it) })
            }
            require(Regex("[A-Za-z0-9@][A-Za-z0-9@._/~-]*").matches(pkg)) { "Not a package name: $spec" }
            return InstallRequest.Run(runner, pkg, version, args)
        }
        return when {
            head == "npx" || head == "pnpx" -> runner("npx", rest)
            head == "bunx" -> runner("npx", rest)
            head == "uvx" -> runner("uvx", rest)
            head == "pipx" && rest.firstOrNull() == "run" -> runner("uvx", rest.drop(1))
            head == "uv" && rest.take(2) == listOf("tool", "run") -> runner("uvx", rest.drop(2))
            head == "bun" && rest.firstOrNull() == "x" -> runner("npx", rest.drop(1))
            head in setOf("pnpm", "yarn") && rest.firstOrNull() == "dlx" -> runner("npx", rest.drop(1))
            head == "npm" && rest.firstOrNull() in setOf("exec", "x") -> runner("npx", rest.drop(1).filterNot { it == "--" })
            head == "npm" && rest.firstOrNull() in setOf("i", "install", "add", "isntall") ->
                npm(packageArgs(rest.drop(1), setOf("--registry", "--prefix")).first ?: error("The command does not name a package."))
            head in setOf("pnpm", "yarn", "bun") && rest.firstOrNull() in setOf("add", "i", "install") ->
                npm(packageArgs(rest.drop(1), setOf("--registry")).first ?: error("The command does not name a package."))
            head == "git" && rest.firstOrNull() == "clone" -> {
                val url = packageArgs(rest.drop(1), setOf("-b", "--branch", "--depth", "--origin")).first ?: error("The command does not name a repository.")
                val branch = rest.windowed(2).firstOrNull { it[0] == "-b" || it[0] == "--branch" }?.get(1).orEmpty()
                val parsed = github(url) as InstallRequest.Source
                InstallRequest.Source(parsed.origin.copy(ref = branch.ifBlank { parsed.origin.ref }))
            }
            head == "claude" || head == "/plugin" -> claude(if (head == "/plugin") listOf("plugin") + rest else rest)
            head.startsWith("https://github.com/") || head.startsWith("github.com/") || head.startsWith("http://github.com/") -> github(head)
            head.startsWith("https://www.npmjs.com/package/") -> npm(head.removePrefix("https://www.npmjs.com/package/").substringBefore("/v/").trimEnd('/') +
                head.substringAfter("/v/", "").let { if (it.isBlank()) "" else "@$it" })
            head.startsWith("https://") && Regex("\\.(zip|tgz|tar\\.gz)(\\?.*)?$").containsMatchIn(head) -> InstallRequest.Source(PluginOrigin("url", head))
            head.startsWith("https://") || head.startsWith("http://") ->
                mcp(PluginAdapter.sanitizeName(java.net.URI(head).host?.removePrefix("mcp.")?.substringBefore('.') ?: "server", "server"),
                    buildJsonObject { put("type", if (head.trimEnd('/').endsWith("/sse")) "sse" else "http"); put("url", head) })
            head.startsWith("github:") -> github(head.removePrefix("github:"))
            words.size == 1 && head.startsWith("@") -> npm(head)
            words.size == 1 && repoShorthand.matches(head.substringBefore('#')) -> github(head)
            words.size == 1 -> npm(head)
            else -> throw IllegalArgumentException("This command is not an install command. Paste an npm, npx, pnpm, yarn, bun, uvx, git clone, claude or /plugin command, a package name or a link.")
        }
    }
    /** claude plugin install x@m, claude plugin marketplace add o/r, claude mcp add [...], claude mcp add-json n '{...}'. */
    private fun claude(words: List<String>): InstallRequest {
        val (group, action) = words.getOrNull(0) to words.getOrNull(1)
        val args = words.drop(2)
        return when {
            group == "plugin" && action == "marketplace" && words.getOrNull(2) == "add" ->
                InstallRequest.Marketplace(marketplaceRepo(words.getOrNull(3) ?: error("Name the marketplace repository.")))
            group == "plugin" && action in setOf("install", "i", "add") -> {
                val spec = args.firstOrNull { !it.startsWith("-") } ?: error("Name the plugin to install.")
                if ('@' in spec && !spec.startsWith("@")) InstallRequest.MarketplacePlugin(spec.substringBefore('@'), spec.substringAfter('@'))
                else InstallRequest.MarketplacePlugin(spec, "")
            }
            group == "mcp" && action == "add-json" -> {
                val name = args.firstOrNull { !it.startsWith("-") } ?: error("Name the MCP server.")
                val server = args.dropWhile { it != name }.drop(1).firstOrNull { it.startsWith("{") } ?: error("Paste the server's JSON.")
                mcp(name, Json.parseToJsonElement(server).jsonObject)
            }
            group == "mcp" && action == "add" -> {
                val split = args.indexOf("--")
                val options = if (split >= 0) args.take(split) else args
                val command = if (split >= 0) args.drop(split + 1) else emptyList()
                var transport = "stdio"; val env = linkedMapOf<String, String>(); val headers = linkedMapOf<String, String>(); val positional = mutableListOf<String>()
                var i = 0
                while (i < options.size) {
                    val option = options[i]
                    when {
                        option == "-t" || option == "--transport" -> transport = options.getOrNull(++i).orEmpty()
                        option.startsWith("--transport=") -> transport = option.substringAfter('=')
                        option == "-e" || option == "--env" -> options.getOrNull(++i)?.let { env[it.substringBefore('=')] = it.substringAfter('=') }
                        option == "-H" || option == "--header" -> options.getOrNull(++i)?.let { headers[it.substringBefore(':').trim()] = it.substringAfter(':').trim() }
                        option == "-s" || option == "--scope" -> i++
                        option.startsWith("-") -> Unit
                        else -> positional += option
                    }
                    i++
                }
                val name = positional.firstOrNull() ?: error("Name the MCP server.")
                val target = command.ifEmpty { positional.drop(1) }
                require(target.isNotEmpty()) { "Add the server's command after --, or its URL." }
                mcp(name, buildJsonObject {
                    if (transport in setOf("http", "sse") || target.first().startsWith("http")) {
                        put("type", if (transport == "sse") "sse" else "http"); put("url", target.first())
                        if (headers.isNotEmpty()) put("headers", JsonObject(headers.mapValues { JsonPrimitive(it.value) }))
                    } else {
                        put("command", target.first()); if (target.size > 1) put("args", JsonArray(target.drop(1).map(::JsonPrimitive)))
                    }
                    if (env.isNotEmpty()) put("env", JsonObject(env.mapValues { JsonPrimitive(it.value) }))
                })
            }
            else -> throw IllegalArgumentException("Supported claude commands: plugin install, plugin marketplace add, mcp add and mcp add-json.")
        }
    }
}
