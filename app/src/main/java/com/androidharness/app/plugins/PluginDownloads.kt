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

/** Registry/archive APIs only: no npm lifecycle scripts, shell commands, or Git checkout hooks. */
class PluginDownloads(
    client: OkHttpClient = OkHttpClient(),
    private val npmRegistry: String = "https://registry.npmjs.org",
    private val githubApi: String = "https://api.github.com",
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
        else -> error("Choose npm, GitHub or a local plugin bundle.")
    }
    suspend fun download(resolved: ResolvedPluginDownload, destination: File, progress: (Long, Long?) -> Unit = { _, _ -> }) {
        request(resolved.url, resolved.headers) { response ->
            val body = requireNotNull(response.body)
            val total = body.contentLength().takeIf { it >= 0 }
            require(total == null || total <= PluginArchives.MAX_DOWNLOAD) { "Plugin download exceeds 25 MiB." }
            body.byteStream().use { input -> destination.outputStream().use { out ->
                PluginArchives.copyLimited(input, out, PluginArchives.MAX_DOWNLOAD) { progress(it, total) }
            } }
        }
        resolved.integrity?.let { verifyIntegrity(destination, it) }
    }
    suspend fun searchNpm(query: String): List<PluginListing> {
        require(query.length <= 160)
        val url = npmRegistry.toHttpUrl().newBuilder().addPathSegments("-/v1/search")
            .addQueryParameter("text", "keywords:androidharness-plugin $query").addQueryParameter("size", "30").build()
        val data = metadata(url.toString())
        return data["objects"]?.jsonArray.orEmpty().mapNotNull { item ->
            val pkg = item.jsonObject["package"]?.jsonObject ?: return@mapNotNull null
            val name = pkg["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            runCatching { npmTarget(name) }.getOrNull() ?: return@mapNotNull null
            PluginListing(name, pkg["description"]?.jsonPrimitive?.content.orEmpty().take(2000),
                pkg["version"]?.jsonPrimitive?.content.orEmpty(), PluginOrigin("npm", name, "latest"),
                pkg["publisher"]?.jsonObject?.get("username")?.jsonPrimitive?.content.orEmpty())
        }
    }
    companion object {
        fun npmTarget(target: String): Pair<String, String> {
            val at = target.lastIndexOf('@').takeIf { it > 0 }
            val name = if (at == null) target.trim() else target.substring(0, at).trim()
            val version = if (at == null) "latest" else target.substring(at + 1)
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
