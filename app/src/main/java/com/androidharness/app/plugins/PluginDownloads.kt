package com.androidharness.app.plugins

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ResolvedPluginDownload(val origin: PluginOrigin, val revision: String, val url: String,
    val integrity: String? = null, val headers: Map<String, String> = emptyMap())

/** Registry, archive and catalog APIs only: no npm lifecycle scripts, shell commands, or Git checkout hooks. */
class PluginDownloads(
    client: OkHttpClient = OkHttpClient(),
    private val npmRegistry: String = "https://registry.npmjs.org",
    private val githubApi: String = "https://api.github.com",
    private val mcpRegistry: String = "https://registry.modelcontextprotocol.io",
    private val githubToken: suspend () -> String? = { null },
    private val allowLocalHttp: Boolean = false,
) {
    private val client = client.newBuilder().followSslRedirects(false)
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()

    private fun checked(url: String): HttpUrl = url.toHttpUrl().also {
        require(it.scheme == "https" || allowLocalHttp && it.host == "127.0.0.1") { "Plugin downloads require HTTPS." }
        require(it.username.isEmpty() && it.password.isEmpty()) { "Do not include credentials in plugin URLs." }
    }
    private suspend fun <T> request(url: String, headers: Map<String, String> = emptyMap(), read: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(checked(url)).header("User-Agent", "AndroidHarness-Plugins")
            headers.forEach { (key, value) -> request.header(key, value) }
            val call = client.newCall(request.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            checked(it.request.url.toString())
                            check(it.isSuccessful) { "Plugin source returned HTTP ${it.code}. Check the package, repository, version and access." }
                            read(it)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
    private suspend fun metadata(url: String, headers: Map<String, String> = emptyMap()): JsonObject = request(url, headers) { response ->
        val bytes = requireNotNull(response.body).byteStream().use { PluginArchives.readLimited(it, 8L * 1024 * 1024) }
        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }
    suspend fun resolve(origin: PluginOrigin): ResolvedPluginDownload = when (origin.kind) {
        "npm" -> {
            val (name, requestedVersion) = npmTarget(origin.target)
            val version = origin.ref.ifBlank { requestedVersion }.also { npmTarget("$name@$it") }
            val url = npmRegistry.toHttpUrl().newBuilder().addPathSegment(name).addPathSegment(version).build()
            val pkg = metadata(url.toString())
            require(pkg["name"]?.jsonPrimitive?.content == name) { "npm returned a different package." }
            val resolved = pkg["version"]?.jsonPrimitive?.content ?: error("npm package has no version.")
            val dist = pkg["dist"]?.jsonObject ?: error("npm package has no distribution archive.")
            val integrity = dist["integrity"]?.jsonPrimitive?.contentOrNull
                ?: dist["shasum"]?.jsonPrimitive?.contentOrNull?.let { "sha1hex-$it" }
                ?: error("npm package is missing its integrity hash.")
            ResolvedPluginDownload(PluginOrigin("npm", name, version), resolved,
                checked(dist["tarball"]?.jsonPrimitive?.content ?: error("npm package has no tarball.")).toString(), integrity)
        }
        "github" -> {
            val repo = githubTarget(origin.target)
            val token = githubToken()
            val headers = buildMap { put("Accept", "application/vnd.github+json"); if (!token.isNullOrBlank()) put("Authorization", "Bearer $token") }
            val base = githubApi.toHttpUrl().newBuilder().addPathSegment("repos").addPathSegments(repo).build()
            val ref = origin.ref.ifBlank { metadata(base.toString(), headers)["default_branch"]?.jsonPrimitive?.content ?: "HEAD" }
            require(ref.length <= 200 && ref.none { it.isISOControl() }) { "Invalid GitHub reference." }
            val commitUrl = base.newBuilder().addPathSegment("commits").addPathSegment(ref).build()
            val sha = metadata(commitUrl.toString(), headers)["sha"]?.jsonPrimitive?.content.orEmpty()
            require(Regex("[a-f0-9]{40}").matches(sha)) { "GitHub did not resolve an immutable commit." }
            val archive = base.newBuilder().addPathSegment("zipball").addPathSegment(sha).build()
            ResolvedPluginDownload(PluginOrigin("github", repo, ref, origin.subdirectory), sha, archive.toString(), headers = headers)
        }
        "url" -> ResolvedPluginDownload(origin, origin.target, checked(origin.target).toString())
        "git" -> error("Only GitHub repositories can be downloaded. Ask the publisher for a GitHub, npm or archive source.")
        else -> error("Choose npm, GitHub or a local plugin bundle.")
    }
    private suspend fun githubHeaders(raw: Boolean = false): Map<String, String> {
        val token = githubToken()
        return buildMap {
            put("Accept", if (raw) "application/vnd.github.raw+json" else "application/vnd.github+json")
            if (!token.isNullOrBlank()) put("Authorization", "Bearer $token")
        }
    }
    /** The version document npm serves for one package version, used to tell MCP servers from installers. */
    suspend fun npmVersion(name: String, version: String): JsonObject {
        val (checkedName, checkedVersion) = npmTarget("$name@${version.ifBlank { "latest" }}")
        return metadata(npmRegistry.toHttpUrl().newBuilder().addPathSegment(checkedName).addPathSegment(checkedVersion).build().toString())
    }
    suspend fun marketplace(source: MarketplaceSource): JsonObject {
        val repo = githubTarget(source.repo)
        val url = githubApi.toHttpUrl().newBuilder().addPathSegment("repos").addPathSegments(repo)
            .addPathSegments("contents/.claude-plugin/marketplace.json").apply { if (source.ref.isNotBlank()) addQueryParameter("ref", source.ref) }.build()
        return try { metadata(url.toString(), githubHeaders(raw = true)) }
        catch (e: IllegalStateException) { throw IllegalStateException("${source.repo} has no readable .claude-plugin/marketplace.json.", e) }
    }
    suspend fun download(resolved: ResolvedPluginDownload, destination: File, progress: (Long, Long?) -> Unit = { _, _ -> }) {
        request(resolved.url, resolved.headers) { response ->
            val body = requireNotNull(response.body)
            val total = body.contentLength().takeIf { it >= 0 }
            require(total == null || total <= PluginArchives.MAX_DOWNLOAD) { "Plugin download exceeds 200 MiB." }
            body.byteStream().use { input -> destination.outputStream().use { out ->
                PluginArchives.copyLimited(input, out, PluginArchives.MAX_DOWNLOAD) { progress(it, total) }
            } }
        }
        resolved.integrity?.let { verifyIntegrity(destination, it) }
    }
    /** Any npm package can be checked; plugin, skill and MCP keywords rank first. */
    suspend fun searchNpm(query: String): List<PluginListing> {
        require(query.length <= 160)
        val text = query.trim().ifBlank { "keywords:claude-code-plugin,claude-skill,agent-skills,mcp-server,androidharness-plugin" }
        val url = npmRegistry.toHttpUrl().newBuilder().addPathSegments("-/v1/search")
            .addQueryParameter("text", text).addQueryParameter("size", "40").build()
        val data = metadata(url.toString())
        val relevant = setOf("mcp", "mcp-server", "modelcontextprotocol", "claude", "claude-code", "claude-skill", "claude-plugin", "skill", "skills",
            "agent-skills", "androidharness-plugin", "cursor", "ai-agent")
        return data["objects"]?.jsonArray.orEmpty().mapNotNull { item ->
            val pkg = item.jsonObject["package"]?.jsonObject ?: return@mapNotNull null
            val name = pkg["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            runCatching { npmTarget(name) }.getOrNull() ?: return@mapNotNull null
            val keywords = pkg["keywords"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() }
            val mcp = keywords.any { "mcp" in it || "modelcontextprotocol" in it } || Regex("(^|[-/@])mcp([-/]|$)").containsMatchIn(name)
            val score = keywords.count { it in relevant }
            val links = pkg["links"] as? JsonObject
            score to PluginListing(name, pkg["description"]?.jsonPrimitive?.content.orEmpty().take(2000),
                pkg["version"]?.jsonPrimitive?.content.orEmpty(), PluginOrigin("npm", name, "latest"),
                pkg["publisher"]?.jsonObject?.get("username")?.jsonPrimitive?.content.orEmpty(),
                kind = if (mcp) "mcp" else if (keywords.any { "skill" in it }) "skill" else "plugin", source = "npm",
                homepage = links?.get("repository").string()?.takeIf { "github.com" in it } ?: links?.get("homepage").string().orEmpty(),
                updatedAt = epochMillis(pkg["date"].string()),
                downloads = ((item.jsonObject["downloads"] as? JsonObject)?.get("monthly") as? JsonPrimitive)?.longOrNull)
        }.sortedByDescending { it.first }.map { it.second }
    }
    /** The official MCP registry. npm and PyPI packages run through npx or uvx; remote servers connect directly. */
    suspend fun searchMcpRegistry(query: String): List<PluginListing> {
        require(query.length <= 160)
        val url = mcpRegistry.toHttpUrl().newBuilder().addPathSegments("v0/servers").addQueryParameter("limit", "40")
            .apply { if (query.isNotBlank()) addQueryParameter("search", query.trim()) }.build()
        val data = metadata(url.toString())
        return data["servers"]?.jsonArray.orEmpty().mapNotNull { item ->
            val server = (item.jsonObject["server"] ?: item).jsonObject
            val id = server["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val config = mcpServerConfig(server) ?: return@mapNotNull null
            val short = PluginAdapter.sanitizeName(id.substringAfterLast('/'), "server").take(32)
            val official = ((item.jsonObject["_meta"] as? JsonObject)?.get("io.modelcontextprotocol.registry/official")) as? JsonObject
            PluginListing(server["title"]?.jsonPrimitive?.contentOrNull ?: id.substringAfterLast('/'), server["description"]?.jsonPrimitive?.contentOrNull.orEmpty().take(2000),
                server["version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                PluginOrigin("mcp", buildJsonObject { put(short, config) }.toString(), server["version"]?.jsonPrimitive?.contentOrNull.orEmpty(), name = short),
                id.substringBefore('/'), kind = "mcp", source = "MCP registry",
                homepage = server["repository"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull ?: server["websiteUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                updatedAt = epochMillis(official?.get("updatedAt").string()), createdAt = epochMillis(official?.get("publishedAt").string()))
        }.distinctBy { it.origin.target }
    }
    /** GitHub repositories tagged as skills or plugins for coding agents. */
    suspend fun searchGitHub(query: String): List<PluginListing> {
        require(query.length <= 160)
        val topics = "topic:claude-skills OR topic:agent-skills OR topic:claude-code-plugin OR topic:claude-code-skills OR topic:mcp-server"
        val url = githubApi.toHttpUrl().newBuilder().addPathSegments("search/repositories")
            .addQueryParameter("q", if (query.isBlank()) "$topics stars:>50" else "${query.trim()} in:name,description,topics")
            .addQueryParameter("sort", "stars").addQueryParameter("per_page", "30").build()
        val data = metadata(url.toString(), githubHeaders())
        return data["items"]?.jsonArray.orEmpty().mapNotNull { item ->
            val repo = item.jsonObject
            val fullName = repo["full_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val topicsList = repo["topics"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
            val stars = repo["stargazers_count"]?.jsonPrimitive?.intOrNull ?: 0
            PluginListing(repo["name"]?.jsonPrimitive?.content.orEmpty(), repo["description"]?.jsonPrimitive?.contentOrNull.orEmpty().take(2000),
                "", PluginOrigin("github", fullName), fullName.substringBefore('/'),
                kind = when { topicsList.any { "mcp" in it } -> "mcp"; topicsList.any { "skill" in it } -> "skill"; else -> "plugin" },
                source = "GitHub", homepage = repo["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty(), stars = stars,
                updatedAt = epochMillis(repo["pushed_at"].string()), createdAt = epochMillis(repo["created_at"].string()))
        }
    }
    /** Star count and dates of one repository, used to rank listings that only link to GitHub. */
    suspend fun githubStats(repo: String): RepoStats {
        val checked = githubTarget(repo)
        val data = metadata(githubApi.toHttpUrl().newBuilder().addPathSegment("repos").addPathSegments(checked).build().toString(), githubHeaders())
        return RepoStats(data["stargazers_count"]?.jsonPrimitive?.intOrNull ?: 0, epochMillis(data["pushed_at"].string()),
            epochMillis(data["created_at"].string()))
    }
    data class RepoStats(val stars: Int, val updatedAt: Long?, val createdAt: Long?)
    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    companion object {
        /** ISO 8601 time from a registry, or null when it is missing or malformed. */
        fun epochMillis(text: String?): Long? = text?.takeIf(String::isNotBlank)?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
        fun npmTarget(target: String): Pair<String, String> {
            val at = target.lastIndexOf('@').takeIf { it > 0 }
            val name = if (at == null) target.trim() else target.substring(0, at).trim()
            val version = if (at == null) "latest" else target.substring(at + 1).trim().removePrefix("=").removePrefix("v").ifBlank { "@" }
            require(Regex("(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*").matches(name) && name.length <= 214) { "Enter an npm package such as @team/plugin@1.2.0." }
            require(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,79}").matches(version)) { "Use an exact npm version or tag, such as 1.2.0 or latest." }
            return name to version
        }
        fun githubTarget(target: String): String {
            val text = target.trim().removePrefix("https://github.com/").removeSuffix("/").removeSuffix(".git")
            require(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}").matches(text) && text.substringAfter('/') !in setOf(".", "..")) {
                "Enter owner/repository or its GitHub URL. Put the branch/tag and subfolder in their own fields."
            }
            return text
        }
        /** A registry server entry as harness.json MCP configuration; container-only servers are left out. */
        fun mcpServerConfig(server: JsonObject): JsonObject? {
            fun placeholders(text: String) = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)\\}").replace(text) { "{env:${it.groupValues[1].uppercase()}}" }
            server["remotes"]?.jsonArray?.firstOrNull()?.jsonObject?.let { remote ->
                val url = remote["url"]?.jsonPrimitive?.contentOrNull ?: return@let
                if (!url.startsWith("https://")) return@let
                return buildJsonObject {
                    put("type", if (remote["type"]?.jsonPrimitive?.contentOrNull == "sse") "sse" else "http"); put("url", placeholders(url))
                    val headers = remote["headers"]?.jsonArray.orEmpty().mapNotNull { header ->
                        val h = header.jsonObject; val name = h["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        name to placeholders(h["value"]?.jsonPrimitive?.contentOrNull ?: "{${name.replace('-', '_')}}")
                    }
                    if (headers.isNotEmpty()) put("headers", JsonObject(headers.toMap().mapValues { JsonPrimitive(it.value) }))
                }
            }
            val pkg = server["packages"]?.jsonArray.orEmpty().map { it.jsonObject }.firstOrNull {
                it["registryType"]?.jsonPrimitive?.contentOrNull in setOf("npm", "pypi") && it["transport"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull.let { t -> t == null || t == "stdio" }
            } ?: return null
            val identifier = pkg["identifier"]?.jsonPrimitive?.contentOrNull ?: return null
            val version = pkg["version"]?.jsonPrimitive?.contentOrNull
            val npm = pkg["registryType"]?.jsonPrimitive?.contentOrNull == "npm"
            val positional = pkg["packageArguments"]?.jsonArray.orEmpty().mapNotNull { arg ->
                val a = arg.jsonObject
                val value = a["value"]?.jsonPrimitive?.contentOrNull ?: a["default"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (a["type"]?.jsonPrimitive?.contentOrNull == "named") listOfNotNull(a["name"]?.jsonPrimitive?.contentOrNull, placeholders(value))
                else listOf(placeholders(value))
            }.flatten()
            return buildJsonObject {
                put("command", if (npm) "npx" else "uvx")
                put("args", buildJsonArray {
                    if (npm) add("-y")
                    add(identifier + (version?.let { if (npm) "@$it" else "==$it" } ?: ""))
                    positional.forEach { add(it) }
                })
                val env = pkg["environmentVariables"]?.jsonArray.orEmpty().mapNotNull { variable ->
                    val name = variable.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    name to (variable.jsonObject["value"]?.jsonPrimitive?.contentOrNull?.let(::placeholders) ?: "{env:$name}")
                }
                if (env.isNotEmpty()) put("env", JsonObject(env.toMap().mapValues { JsonPrimitive(it.value) }))
            }
        }
        fun verifyIntegrity(file: File, integrity: String) {
            val tokens = integrity.split(' ')
            val token = tokens.firstOrNull { it.startsWith("sha512-") } ?: tokens.firstOrNull { it.startsWith("sha256-") }
                ?: tokens.firstOrNull { it.startsWith("sha1-") || it.startsWith("sha1hex-") } ?: error("Unsupported npm integrity hash.")
            val algorithm = when { token.startsWith("sha512-") -> "SHA-512"; token.startsWith("sha256-") -> "SHA-256"; else -> "SHA-1" }
            val digest = MessageDigest.getInstance(algorithm)
            file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            val expected = if (token.startsWith("sha1hex-")) {
                val hex = token.substringAfter('-'); require(Regex("[a-fA-F0-9]{40}").matches(hex)) { "Invalid integrity hash." }
                hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } else Base64.getDecoder().decode(token.substringAfter('-'))
            require(MessageDigest.isEqual(digest.digest(), expected)) { "Plugin download failed integrity verification. Nothing was installed." }
        }
    }
}
