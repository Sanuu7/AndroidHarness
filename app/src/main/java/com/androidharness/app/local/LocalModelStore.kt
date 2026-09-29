package com.androidharness.app.local

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

class LocalModelStore(private val root: File) {
    init { check(root.isDirectory || root.mkdirs()) { "Cannot create local model storage." } }
    fun file(id: String): File {
        require(LocalModelCatalog.find(id) != null) { "Unknown local model." }
        return File(root, "$id.gguf")
    }
    fun partial(id: String) = File(root, "${file(id).name}.part")
    fun installed(model: LocalModelSpec) = file(model.id).let { it.isFile && it.length() == model.bytes }
    fun clearPartial(id: String) { removeFile(partial(id)) }
    fun remove(id: String) {
        clearPartial(id)
        removeFile(file(id))
        removeFile(File(root, "$id.json"))
        removeFile(File(root, "$id.json.bak"))
        removeFile(File(root, "$id.json.new"))
    }
    private fun removeFile(file: File) {
        check(!file.exists() || file.delete()) { "Could not delete ${file.name}. Storage has not been released." }
    }
    fun install(
        model: LocalModelSpec,
        source: InputStream,
        isCancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ) {
        check(!file(model.id).exists()) { "Model already installed. Remove it before reinstalling." }
        val target = partial(model.id)
        clearPartial(model.id)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            val magic = ByteArray(4)
            target.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    check(!isCancelled()) { "Download cancelled." }
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    check(count + read <= model.bytes) { "Download exceeds expected size." }
                    for (index in 0 until minOf(read, (4 - count).coerceAtLeast(0).toInt())) magic[count.toInt() + index] = buffer[index]
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    count += read
                    onProgress(count)
                }
                output.fd.sync()
            }
            check(!isCancelled()) { "Download cancelled." }
            check(count == model.bytes) { "Incomplete download. Retry to download a fresh copy." }
            check(magic.contentEquals(byteArrayOf(71, 71, 85, 70))) { "Not a GGUF model." }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(hash == model.sha256) { "Model checksum mismatch. Download discarded." }
            check(target.renameTo(file(model.id))) { "Cannot finalize model download." }
        } finally {
            clearPartial(model.id)
        }
    }
}
