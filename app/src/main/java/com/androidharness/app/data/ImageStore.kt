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

    /** Copies the picked image in, downscaling to at most 1568px on the long edge. */
    fun import(uri: Uri): StoredImage? {
        val mime = context.contentResolver.getType(uri) ?: "image/png"
        val ext = when (mime) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "png"
        }
        return runCatching {
            val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, opts)
                opts
            } ?: return null

            val sample = sampleSize(decoded.outWidth, decoded.outHeight, MAX_EDGE)
            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null,
                    BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null

            val file = File(dir, "${UUID.randomUUID()}.$ext")
            file.outputStream().use { out ->
                if (ext == "jpg") bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
                else bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            StoredImage(file, mime)
        }.getOrNull()
    }

    fun base64(file: File): String =
        android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)

    /** Removes an imported image (e.g. an attachment the user un-picked before sending). */
    fun delete(name: String) {
        runCatching { File(dir, name).delete() }
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
    }
}
