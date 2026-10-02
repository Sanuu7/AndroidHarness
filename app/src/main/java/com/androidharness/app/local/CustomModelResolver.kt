package com.androidharness.app.local

import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Public Hugging Face repositories/file pages, or direct HTTPS GGUF files. */
class CustomModelResolver(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS).followSslRedirects(false).build()) {
    data class Link(val url: String, val repository: String? = null, val revision: String? = null, val file: String? = null)

    fun resolve(text: String, format: LocalModelFormat = LocalModelFormat.GGUF): List<LocalModelSpec> {
        val link = parseLink(text, format)
        if (link.repository == null) return listOf(inspect(spec(link.url, link.url.substringBefore('?').substringAfterLast('/'), 24, "", link.url)))
        val endpoint = "https://huggingface.co/api/models/${link.repository}".toHttpUrlOrNull()!!.newBuilder()
        link.revision?.let { endpoint.addPathSegment("revision").addPathSegment(it) }
        endpoint.addQueryParameter("blobs", "true")
        val root = client.newCall(Request.Builder().url(endpoint.build()).build()).execute().use { response ->
            checkResponse(response.code)
            val bytes = readPrefix(checkNotNull(response.body).byteStream(), 6 * 1024 * 1024 + 1)
            check(bytes.size <= 6 * 1024 * 1024) { "Repository is too large to browse. Paste a direct HTTPS GGUF download link." }
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        }
        return if (format == LocalModelFormat.SAFETENSORS) listOf(parseSafetensors(link, root)) else parseRepository(link, root)
    }

    internal fun parseRepository(link: Link, root: JsonObject): List<LocalModelSpec> {
        val revision = root["sha"]?.jsonPrimitive?.content.orEmpty()
        require(revision.matches(Regex("[a-f0-9]{40}"))) { "Hugging Face did not return a pinned model revision." }
        val license = root["cardData"]?.let { it as? JsonObject }?.get("license")?.let { it as? JsonPrimitive }?.content
            ?: "See model source for license terms"
        val repo = requireNotNull(link.repository)
        val files = root["siblings"]?.jsonArray.orEmpty().mapNotNull { entry ->
            val obj = entry.jsonObject
            val filename = obj["rfilename"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (!isChatFile(filename) || link.file != null && link.file != filename) return@mapNotNull null
            val lfs = obj["lfs"] as? JsonObject
            val size = obj["size"]?.jsonPrimitive?.longOrNull ?: lfs?.get("size")?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val hash = lfs?.get("sha256")?.jsonPrimitive?.content.orEmpty()
            if (size < 24 || !hash.matches(Regex("[a-f0-9]{64}"))) return@mapNotNull null
            val url = "https://huggingface.co".toHttpUrlOrNull()!!.newBuilder()
            repo.split('/').forEach(url::addPathSegment)
            url.addPathSegment("resolve").addPathSegment(revision)
            filename.split('/').forEach(url::addPathSegment)
            spec(url.build().toString(), filename.substringAfterLast('/'), size, hash, "https://huggingface.co/$repo")
                .copy(repository = repo, revision = revision, filename = filename, license = license)
        }.sortedBy { it.bytes }
        require(files.isNotEmpty()) { "No supported single-file GGUF found. Paste an Instruct/chat GGUF model link; safetensors, split files and vision projectors cannot be used here." }
        return files
    }

    internal fun parseSafetensors(link: Link, root: JsonObject): LocalModelSpec {
        val revision = root["sha"]?.jsonPrimitive?.content.orEmpty()
        require(revision.matches(Regex("[a-f0-9]{40}"))) { "The model source did not provide a pinned revision." }
        val repo = requireNotNull(link.repository) { "Use a Hugging Face repository so config and tokenizer files are included." }
        val folder = link.file?.substringBeforeLast('/', "").orEmpty()
        val files = root["siblings"]?.jsonArray.orEmpty().mapNotNull { entry ->
            val obj = entry.jsonObject
            val filename = obj["rfilename"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (filename.substringBeforeLast('/', "") != folder) return@mapNotNull null
            val name = filename.substringAfterLast('/')
            if (!name.endsWith(".safetensors") && name !in listOf("config.json", "tokenizer.json", "tokenizer_config.json", "generation_config.json", "chat_template.jinja")) return@mapNotNull null
            val lfs = obj["lfs"] as? JsonObject
            val size = obj["size"]?.jsonPrimitive?.longOrNull ?: lfs?.get("size")?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            require(size > 0 && filename.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
            val url = "https://huggingface.co".toHttpUrlOrNull()!!.newBuilder()
            repo.split('/').forEach(url::addPathSegment)
            url.addPathSegment("resolve").addPathSegment(revision)
            filename.split('/').forEach(url::addPathSegment)
            LocalModelAsset(name, url.build().toString(), size, lfs?.get("sha256")?.jsonPrimitive?.content.orEmpty())
        }
        val selectedNames = LocalModelImportFiles.select(files.map { it.filename }).toSet()
        val selectedFiles = files.filter { it.filename in selectedNames }
        val page = "https://huggingface.co/$repo/tree/$revision"
        val id = MessageDigest.getInstance("SHA-256").digest((page + folder).toByteArray()).take(16).joinToString("") { "%02x".format(it) }
        return LocalModelSpec("custom-$id", repo.substringAfter('/'), repo, revision, "model.safetensors", selectedFiles.sumOf { it.bytes }, "",
            1, 256 * 1024, "Safetensors converted on this device to Q4 GGUF. Tools depend on the model's chat template and training.",
            sourcePage = page, downloadUrl = page, custom = true, format = LocalModelFormat.SAFETENSORS, assets = selectedFiles)
    }

    fun resolveProjector(text: String): LocalModelAsset {
        val link = parseLink(text, projector = true)
        val url = link.url.toHttpUrlOrNull()!!.newBuilder()
        if (link.repository != null) {
            require(link.file != null) { "Paste the matching projector file link, not a repository page." }
            url.encodedPath("/${link.repository}/resolve/${link.revision}/${link.file}")
        }
        val model = inspect(spec(url.build().toString(), link.file?.substringAfterLast('/') ?: "mmproj.gguf", 24, "", text))
        return LocalModelAsset(model.filename, model.url, model.bytes, model.sha256)
    }

    fun inspect(model: LocalModelSpec): LocalModelSpec {
        if (model.format == LocalModelFormat.SAFETENSORS) return model
        val request = Request.Builder().url(model.url).header("Range", "bytes=0-${GgufMetadata.PROBE_BYTES - 1}").build()
        return client.newCall(request).execute().use { response ->
            checkResponse(response.code)
            val body = checkNotNull(response.body) { "Empty model response." }
            val size = if (response.code == 206) response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                else body.contentLength().takeIf { it > 0 }
            require(size != null && size >= 24) { "The server must provide the complete GGUF file size." }
            require(model.bytes == 24L || model.bytes == size) { "The file size changed. Paste the link again." }
            val estimate = body.byteStream().use { GgufMetadata.inspect(readPrefix(it, GgufMetadata.PROBE_BYTES)) }
            model.copy(bytes = size, kvBytesPerToken = estimate.kvBytesPerToken, maxContext = estimate.maxContext)
        }
    }

    private fun spec(url: String, title: String, size: Long, hash: String, page: String): LocalModelSpec {
        val id = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
        return LocalModelSpec("custom-$id", title.removeSuffix(".gguf"), "", "", title, size, hash,
            1, 256 * 1024, "Custom GGUF. Requires a chat template supported by the local engine. RAM is estimated from the model header, with a conservative fallback.",
            "See model source for license terms", url, page, true)
    }

    private fun checkResponse(code: Int) {
        check(code in 200..299) { when (code) {
            401, 403 -> "This model is private or gated. Use a public GGUF download link."
            404 -> "Model or file not found. Check the link."
            429 -> "Download service is busy. Try again later."
            else -> "Model server returned HTTP $code. Try again later."
        } }
    }

    companion object {
        fun parseLink(text: String, format: LocalModelFormat = LocalModelFormat.GGUF, projector: Boolean = false): Link {
            val url = text.trim().toHttpUrlOrNull() ?: error("Paste a valid Hugging Face or HTTPS GGUF link.")
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty()) { "Use an HTTPS link without a username or password." }
            val clean = url.newBuilder().fragment(null).build()
            val parts = clean.pathSegments.filter { it.isNotEmpty() }
            if (clean.host in listOf("huggingface.co", "www.huggingface.co")) {
                require(parts.size >= 2 && parts.take(2).all { it.matches(Regex("[A-Za-z0-9_.-]+")) }) { "Paste a Hugging Face model repository or GGUF file link." }
                val repo = parts.take(2).joinToString("/")
                if (parts.size == 2) return Link(clean.toString(), repo)
                require(parts.size >= 4 && parts[2] in listOf("blob", "resolve", "tree")) { "Paste a model repository, file page or GGUF download link." }
                val file = if (parts[2] == "tree") null else parts.drop(4).joinToString("/")
                require(file == null || (if (format == LocalModelFormat.SAFETENSORS) file.endsWith(".safetensors") else if (projector) file.endsWith(".gguf", true) else isChatFile(file))) { "Choose a single-file chat GGUF, not safetensors, a split model or a vision projector." }
                return Link(clean.toString(), repo, parts[3], file)
            }
            require(format == LocalModelFormat.GGUF && (if (projector) parts.lastOrNull().orEmpty().endsWith(".gguf", true) else isChatFile(parts.lastOrNull().orEmpty()))) { "This must be a direct link to a single .gguf model file." }
            return Link(clean.toString())
        }
        private fun isChatFile(name: String): Boolean = name.endsWith(".gguf", ignoreCase = true) &&
            !name.substringAfterLast('/').startsWith("mmproj", ignoreCase = true) &&
            !Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE).containsMatchIn(name)
    }
}

internal fun readPrefix(input: java.io.InputStream, limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var count = 0
    while (count < limit) {
        val read = input.read(buffer, count, limit - count)
        if (read < 0) break
        if (read > 0) count += read
    }
    return buffer.copyOf(count)
}
