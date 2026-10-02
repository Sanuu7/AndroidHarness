package com.androidharness.app.local

import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.Json

/** Download jobs and provider IDs survive process death through this catalog. */
class CustomModelRegistry(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val target = File(root, "custom-models.json")
    var loadError: String? = null
        private set
    var models: List<LocalModelSpec> = runCatching {
        if (target.exists()) json.decodeFromString<List<LocalModelSpec>>(target.readText()).onEach(::validate)
        else emptyList()
    }.getOrElse {
        loadError = "Custom model catalog could not be read. Model files were kept. Paste their links again to recover them."
        emptyList()
    }
        private set

    fun add(model: LocalModelSpec) {
        validate(model)
        save(models.filterNot { it.id == model.id } + model)
    }

    fun remove(id: String) { save(models.filterNot { it.id == id }) }

    private fun save(next: List<LocalModelSpec>) {
        check(root.isDirectory || root.mkdirs()) { "Cannot create model storage." }
        val temporary = File(root, "custom-models.json.new")
        try {
            temporary.outputStream().use {
                it.write(json.encodeToString(next).toByteArray())
                it.fd.sync()
            }
            check(temporary.renameTo(target)) { "Cannot save custom model." }
            models = next
            loadError = null
        } finally { temporary.delete() }
    }

    private fun validate(model: LocalModelSpec) {
        require(model.custom && model.id.matches(Regex("custom-[a-f0-9]{32}"))) { "Invalid custom model ID." }
        require(model.bytes >= 24 && model.kvBytesPerToken > 0)
        require(model.sha256.isEmpty() || model.sha256.matches(Regex("[a-f0-9]{64}")))
        require(model.maxContext > 0)
        if (!model.localFile) CustomModelResolver.parseLink(model.url, model.format)
        else require(model.filename.isNotBlank() && '/' !in model.filename && '\\' !in model.filename && model.filename !in listOf(".", ".."))
        model.assets.forEach { asset ->
            require('\\' !in asset.filename && asset.filename.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
            require(asset.bytes > 0 && (model.localFile && asset.url.isEmpty() || asset.url.toHttpUrlOrNull()?.isHttps == true))
        }
    }
}
