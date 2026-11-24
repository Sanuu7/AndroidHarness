package com.androidharness.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID

data class StoredImage(val file: File, val mime: String)

/** Persists user-attached images and serves them (downscaled) as base64. */
class ImageStore(private val context: Context) {

    private val dir: File = File(context.filesDir, "images").apply { mkdirs() }
    val imagesDir: File get() = dir

    /** Fetch through the same public-address and size checks used by Chat's image viewer. */
    suspend fun remoteImage(url: String): com.androidharness.app.core.ImageData? {
        val bitmap = com.androidharness.app.ui.chat.RemoteContentLoader.loadImage(context, url) ?: return null
        val out = java.io.ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) return null
        return com.androidharness.app.core.ImageData("image/jpeg", android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP))
    }

    internal suspend fun remoteOriginal(url: String): ByteArray? =
        com.androidharness.app.ui.chat.RemoteContentLoader.downloadOriginal(context, url)?.bytes

    internal suspend fun fetchRemote(url: String) = com.androidharness.app.ui.chat.RemoteContentLoader.fetchOriginal(context, url)

    internal fun metadata(ref: com.androidharness.app.core.ImageRef): com.androidharness.app.core.ImageRef {
        val file = savedImageFile(ref.name) ?: return ref
        val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, info)
        val original = originalFile(ref.name).takeIf { it.isFile }?.let { saved -> BitmapFactory.Options().apply {
            inJustDecodeBounds = true; BitmapFactory.decodeFile(saved.path, this)
        } }
        return ref.copy(width = ref.width ?: info.outWidth.takeIf { it > 0 }, height = ref.height ?: info.outHeight.takeIf { it > 0 },
            bytes = ref.bytes ?: file.length(), originalWidth = ref.originalWidth ?: original?.outWidth?.takeIf { it > 0 },
            originalHeight = ref.originalHeight ?: original?.outHeight?.takeIf { it > 0 }, originalMime = ref.originalMime ?: original?.outMimeType,
            originalBytes = ref.originalBytes ?: originalFile(ref.name).takeIf { it.isFile }?.length())
    }

    internal fun saveRemote(fetched: com.androidharness.app.ui.chat.RemoteImageFetch, imageId: String, caption: String): com.androidharness.app.core.ImageRef? {
        val bytes = fetched.bytes ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sampleSize(fetched.width ?: 1, fetched.height ?: 1, MAX_EDGE)
        }) ?: return null
        return try {
            val ref = saveCloseUp(bitmap, "chat-image")
            try {
                val original = originalFile(ref.name)
                original.parentFile?.mkdirs(); original.writeBytes(bytes)
                ref.copy(imageId = imageId, sourceUrl = fetched.url, caption = caption,
                    originalWidth = fetched.width, originalHeight = fetched.height, originalMime = fetched.mimeType, originalBytes = bytes.size.toLong(), origin = "remote_web")
            } catch (e: Exception) { delete(ref.name); throw e }
        } finally { bitmap.recycle() }
    }

    fun saveResolved(image: com.androidharness.app.core.ImageData): com.androidharness.app.core.ImageRef {
        val name = "${UUID.randomUUID()}.jpg"
        File(dir, name).writeBytes(android.util.Base64.decode(image.base64, android.util.Base64.DEFAULT))
        return com.androidharness.app.core.ImageRef(name, image.mime)
    }

    /** Chat keeps an original for close-ups; the normal preview stays bounded. */
    fun import(uri: Uri, keepOriginal: Boolean = false): StoredImage? {
        val sourceMime = context.contentResolver.getType(uri) ?: "image/png"
        val mime = if (sourceMime == "image/jpeg") "image/jpeg" else "image/png"
        val ext = if (mime == "image/jpeg") "jpg" else "png"
        val file = File(dir, "${UUID.randomUUID()}.$ext")
        val original = originalFile(file.name)
        return try {
            if (keepOriginal) {
                check(original.parentFile?.mkdirs() == true || original.parentFile?.isDirectory == true)
                val input = context.contentResolver.openInputStream(uri) ?: error("Image cannot be read")
                input.use { source -> original.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= ORIGINAL_BYTE_LIMIT) { "Image exceeds the attachment limit" }
                        output.write(buffer, 0, count)
                    }
                } }
            }
            fun open() = if (keepOriginal) original.inputStream() else
                context.contentResolver.openInputStream(uri) ?: error("Image cannot be read")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open().use { BitmapFactory.decodeStream(it, null, bounds) }
            check(bounds.outWidth > 0 && bounds.outHeight > 0)
            val bitmap = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE)
            }) } ?: error("Image cannot be decoded")
            try {
                file.outputStream().use { out ->
                    check(if (ext == "jpg") bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
                        else bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                }
            } finally { bitmap.recycle() }
            StoredImage(file, mime)
        } catch (e: Exception) {
            file.delete()
            if (keepOriginal) original.delete()
            null
        }
    }

    /** Only internal, generated names are resolved; a chat cannot pick a filesystem path. */
    internal fun originalFile(name: String): File = File(File(dir, "originals"), File(name).name)
    internal fun deleteOriginal(name: String) {
        if (name == File(name).name) runCatching { originalFile(name).delete() }
    }
    internal fun savedImageFile(name: String): File? =
        if (name == File(name).name) File(dir, name).takeIf { it.isFile } else null

    internal fun saveCloseUp(bitmap: Bitmap, prefix: String = "chat-zoom"): com.androidharness.app.core.ImageRef {
        val file = File(dir, "$prefix-${UUID.randomUUID()}.png")
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            return com.androidharness.app.core.ImageRef(file.name, "image/png", width = bitmap.width, height = bitmap.height, bytes = file.length())
        } catch (e: Exception) { file.delete(); throw e }
    }

    internal fun savePageCapture(bitmap: Bitmap, pageUrl: String, caption: String): com.androidharness.app.core.ImageRef {
        val scale = (MAX_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
        val preview = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        return try {
            val ref = saveCloseUp(preview, "chat-page")
            try {
                if (preview !== bitmap) originalFile(ref.name).also { it.parentFile?.mkdirs() }.outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                ref.copy(imageId = com.androidharness.app.tools.ChatImageInventory.id("stored:" + ref.name), sourceUrl = pageUrl,
                    caption = caption, origin = "browser_screenshot", originalWidth = bitmap.width, originalHeight = bitmap.height,
                    originalBytes = originalFile(ref.name).takeIf { it.isFile }?.length() ?: ref.bytes)
            } catch (e: Exception) { delete(ref.name); throw e }
        } finally { if (preview !== bitmap) preview.recycle() }
    }

    internal fun deleteChatZoom(name: String) {
        if (name.startsWith("chat-zoom-") && name == File(name).name) delete(name)
    }

    fun base64(file: File): String =
        android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)

    /** Removes an imported image (e.g. an attachment the user un-picked before sending). */
    fun delete(name: String) {
        if (name != File(name).name) return
        runCatching { File(dir, name).delete(); originalFile(name).delete(); File(File(dir, "text"), "$name.txt").delete() }
    }

    /** Resolves a persisted image reference to bytes for provider requests. */
    fun resolve(ref: com.androidharness.app.core.ImageRef): com.androidharness.app.core.ImageData? {
        val file = File(dir, ref.name)
        if (!file.exists()) return null
        return com.androidharness.app.core.ImageData(ref.mime, base64(file))
    }

    private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / sample > maxEdge) sample *= 2
        return sample
    }

    companion object {
        private const val MAX_EDGE = 1568
        private const val ORIGINAL_BYTE_LIMIT = 64L * 1024 * 1024
    }
}
