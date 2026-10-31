package com.androidharness.app.local

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** A `Content-Range: bytes first-last/total` header. [total] is null when the server sent `*`. */
internal data class ContentRange(val first: Long, val last: Long, val total: Long?)

internal fun parseContentRange(header: String?): ContentRange? {
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(header?.trim().orEmpty()) ?: return null
    val first = match.groupValues[1].toLongOrNull() ?: return null
    val last = match.groupValues[2].toLongOrNull() ?: return null
    return ContentRange(first, last, match.groupValues[3].toLongOrNull()).takeIf { last >= first }
}

class LocalModelStore(private val root: File, private val find: (String) -> LocalModelSpec? = LocalModelCatalog::find) {
    init { check(root.isDirectory || root.mkdirs()) { "Cannot create local model storage." } }
    fun file(id: String): File {
        require(id.matches(Regex("[a-z0-9-]{1,80}")) && find(id) != null) { "Unknown local model." }
        return File(root, "$id.gguf")
    }
    fun partial(id: String) = File(root, "${file(id).name}.part")
    fun projectorFile(id: String) = File(root, "${file(id).nameWithoutExtension}-mmproj.gguf")
    fun sourceDirectory(id: String) = File(root, "${file(id).nameWithoutExtension}-source")
    fun installed(model: LocalModelSpec) = file(model.id).let { it.isFile && it.length() == model.bytes } &&
        (model.projector == null || projectorFile(model.id).let { it.isFile && it.length() == model.projector.bytes })

    /** Bytes already stored for an interrupted download of [model], or 0 when nothing can be resumed. */
    fun resumableBytes(model: LocalModelSpec): Long {
        if (installed(model)) return 0
        if (model.format == LocalModelFormat.SAFETENSORS) {
            val source = sourceDirectory(model.id)
            if (!source.isDirectory) return 0
            return model.assets.sumOf { asset ->
                val done = File(source, asset.filename)
                if (done.isFile && done.length() == asset.bytes) asset.bytes
                else File(source, asset.filename + PART).takeIf { it.isFile && it.length() < asset.bytes }?.length() ?: 0L
            }
        }
        return partial(model.id).takeIf { it.isFile && it.length() < model.bytes }?.length() ?: 0L
    }

    /**
     * Startup cleanup. Interrupted downloads that can still continue are kept; anything else
     * a finished or abandoned transfer left behind is removed.
     */
    fun discardStale(model: LocalModelSpec) {
        if (installed(model)) { clearPartial(model.id); return }
        removeFile(File(root, "${partial(model.id).name}.unquantized"))
        removeFile(File(root, "${projectorFile(model.id).name}.part"))
        val part = partial(model.id)
        if (model.format == LocalModelFormat.SAFETENSORS) {
            // The conversion output is never resumable. The downloaded source files are.
            removeFile(part)
            return
        }
        if (part.exists() && part.length() >= model.bytes) removeFile(part)
        check(!sourceDirectory(model.id).exists() || sourceDirectory(model.id).deleteRecursively()) { "Could not remove conversion staging files." }
    }

    fun clearPartial(id: String) {
        removeFile(partial(id))
        removeFile(File(root, "${partial(id).name}.unquantized"))
        removeFile(File(root, "${projectorFile(id).name}.part"))
        check(!sourceDirectory(id).exists() || sourceDirectory(id).deleteRecursively()) { "Could not remove conversion staging files." }
    }
    fun remove(id: String) {
        clearPartial(id)
        removeFile(file(id))
        removeFile(projectorFile(id))
        removeFile(File(root, "$id.json"))
        removeFile(File(root, "$id.json.bak"))
        removeFile(File(root, "$id.json.new"))
    }
    private fun removeFile(file: File) {
        check(!file.exists() || file.delete()) { "Could not delete ${file.name}. Storage has not been released." }
    }

    /**
     * Streams [source] into [part]. With [offset] > 0 the existing bytes are kept and hashed first,
     * so a resumed file is verified as a whole. Interrupted transfers keep their bytes; the caller
     * decides what to do once the result is complete.
     */
    private fun copyInto(part: File, offset: Long, limit: Long, source: InputStream, isCancelled: () -> Boolean,
        progress: (Long) -> Unit): Pair<Long, MessageDigest> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        var count = 0L
        if (offset > 0) part.inputStream().use { existing ->
            while (count < offset) {
                val read = existing.read(buffer, 0, minOf(buffer.size.toLong(), offset - count).toInt())
                check(read > 0) { "Partial download is damaged. Restart the download." }
                digest.update(buffer, 0, read); count += read
            }
        }
        FileOutputStream(part, offset > 0).use { out ->
            while (true) {
                check(!isCancelled()) { "Download cancelled." }
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                check(count + read <= limit) { "Download exceeds expected size." }
                out.write(buffer, 0, read); digest.update(buffer, 0, read); count += read
                progress(count)
            }
            out.fd.sync()
        }
        return count to digest
    }

    /**
     * Writes one asset of a multi-file model. With [part] the transfer is resumable: pass the number of
     * bytes already stored in [offset] and the file is renamed to [target] only once verified.
     * Without [part] the bytes go straight to [target] and a failed transfer leaves nothing behind.
     */
    fun installAsset(asset: LocalModelAsset, source: InputStream, target: File, isCancelled: () -> Boolean,
        part: File? = null, offset: Long = 0, progress: (Long) -> Unit) {
        check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true)
        val file = part ?: target
        val start = if (part != null && offset > 0 && file.isFile && file.length() == offset && offset < asset.bytes) offset else 0L
        if (start == 0L) file.delete()
        var verifying = false
        try {
            val (count, digest) = copyInto(file, start, asset.bytes, source, isCancelled, progress)
            check(!isCancelled()) { "Incomplete or cancelled download." }
            check(count == asset.bytes) { "Incomplete or cancelled download." }
            verifying = true
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(asset.sha256.isEmpty() || hash == asset.sha256) { "Model file checksum mismatch." }
            if (part != null) check(file.renameTo(target)) { "Cannot finalize ${asset.filename}." }
        } catch (e: Exception) {
            // A cut connection or a pause keeps a resumable file. Anything that failed verification is discarded.
            val resumable = part != null && !verifying && !(e.message ?: "").contains("exceeds expected size")
            if (!resumable) file.delete()
            throw e
        }
    }

    /**
     * Installs [model] from [source]. Pass [resumeFrom] with the length of the existing `.part` file to
     * continue an interrupted download; when it does not match the file on disk the download restarts.
     */
    fun install(
        model: LocalModelSpec,
        source: InputStream,
        isCancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
        resumeFrom: Long = 0,
    ) {
        check(!file(model.id).exists()) { "Model already installed. Remove it before reinstalling." }
        val target = partial(model.id)
        val offset = if (resumeFrom > 0 && target.isFile && target.length() == resumeFrom && resumeFrom < model.bytes) resumeFrom else 0L
        if (offset == 0L) clearPartial(model.id)
        val (count, digest) = try {
            copyInto(target, offset, model.bytes, source, isCancelled, onProgress)
        } catch (e: Exception) {
            if ((e.message ?: "").contains("exceeds expected size") || (e.message ?: "").contains("damaged")) clearPartial(model.id)
            throw e
        }
        check(!isCancelled()) { "Download cancelled." }
        check(count == model.bytes) { "Incomplete download. Resume to continue where it stopped." }
        try {
            val magic = target.inputStream().use { readPrefix(it, 4) }
            check(magic.contentEquals(byteArrayOf(71, 71, 85, 70))) { "Not a GGUF model." }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(model.custom && model.sha256.isEmpty() || hash == model.sha256) { "Model checksum mismatch. Download discarded." }
            if (model.custom) GgufMetadata.inspect(target.inputStream().use { readPrefix(it, GgufMetadata.PROBE_BYTES) })
            check(target.renameTo(file(model.id))) { "Cannot finalize model download." }
        } finally {
            clearPartial(model.id)
        }
    }

    /** What the model folder holds, grouped for the storage screen. */
    fun usage(models: List<LocalModelSpec>): LocalStorageReport {
        val known = models.map { it.id }.toSet()
        val rows = models.mapNotNull { model ->
            val installedBytes = file(model.id).takeIf { it.isFile }?.length() ?: 0L
            val projector = projectorFile(model.id).takeIf { it.isFile }?.length() ?: 0L
            val stored = installedBytes + projector
            val paused = (partial(model.id).takeIf { it.isFile }?.length() ?: 0L) +
                (File(root, "${projectorFile(model.id).name}.part").takeIf { it.isFile }?.length() ?: 0L) +
                (File(root, "${partial(model.id).name}.unquantized").takeIf { it.isFile }?.length() ?: 0L) +
                treeBytes(sourceDirectory(model.id))
            if (stored == 0L && paused == 0L) null
            else LocalModelUsage(model.id, model.title, stored, paused, installed(model))
        }
        val accounted = rows.sumOf { it.installedBytes + it.pausedBytes } +
            known.sumOf { id -> listOf("$id.json", "$id.json.bak", "$id.json.new").sumOf { File(root, it).takeIf { f -> f.isFile }?.length() ?: 0L } }
        val total = treeBytes(root)
        val leftovers = orphans(known)
        val leftoverBytes = leftovers.sumOf { treeBytes(it) }
        return LocalStorageReport(rows.sortedByDescending { it.installedBytes + it.pausedBytes }, leftoverBytes,
            (total - accounted - leftoverBytes).coerceAtLeast(0), total)
    }

    /** Files and folders named like model storage that no known model owns. */
    internal fun orphans(known: Set<String>): List<File> = root.listFiles().orEmpty().filter { entry ->
        val match = ORPHAN_NAME.matchEntire(entry.name) ?: return@filter false
        val id = match.groupValues[1]
        id !in known && id != "custom-models" &&
            (entry.isDirectory == entry.name.endsWith("-source"))
    }

    fun clearOrphans(known: Set<String>): Long {
        var freed = 0L
        orphans(known).forEach { entry ->
            val size = treeBytes(entry)
            if (entry.deleteRecursively()) freed += size
        }
        return freed
    }

    private fun treeBytes(file: File): Long = when {
        file.isFile -> file.length()
        file.isDirectory -> file.listFiles().orEmpty().sumOf(::treeBytes)
        else -> 0L
    }

    private companion object {
        const val PART = ".part"
        val ORPHAN_NAME = Regex("([a-z0-9-]{1,80}?)(?:\\.gguf(?:\\.part(?:\\.unquantized)?)?|-mmproj\\.gguf(?:\\.part)?|-source|\\.json(?:\\.bak|\\.new)?)")
    }
}

data class LocalModelUsage(val id: String, val title: String, val installedBytes: Long, val pausedBytes: Long, val complete: Boolean)

/**
 * [leftoverBytes] is storage no known model owns and that is safe to delete.
 * [otherBytes] is everything else in the folder (the catalog file, import staging, settings).
 */
data class LocalStorageReport(val models: List<LocalModelUsage>, val leftoverBytes: Long, val otherBytes: Long, val totalBytes: Long) {
    val installedBytes get() = models.sumOf { it.installedBytes }
    val pausedBytes get() = models.sumOf { it.pausedBytes }
}
