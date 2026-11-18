package com.androidharness.app.ui.chat.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.androidharness.app.ui.chat.RemoteContentLoader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Saves and shares chat images: remote pictures and the photos a user sent. */
internal object ChatImageActions {
    suspend fun download(context: Context, imageUrl: String): Boolean = withContext(Dispatchers.IO) {
        val image = RemoteContentLoader.downloadOriginal(context, imageUrl) ?: return@withContext false
        saveToGallery(context, image.bytes, image.mimeType)
    }

    suspend fun share(context: Context, imageUrl: String): Boolean = withContext(Dispatchers.IO) {
        val image = RemoteContentLoader.downloadOriginal(context, imageUrl) ?: return@withContext false
        shareBytes(context, image.bytes, image.mimeType)
    }

    /** A sent photo, at full quality when the original was kept. */
    private fun localImage(dir: File, ref: com.androidharness.app.core.ImageRef): Pair<ByteArray, String>? {
        val name = File(ref.name).name
        val file = File(File(dir, "originals"), name).takeIf { it.isFile } ?: File(dir, name).takeIf { it.isFile } ?: return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        return file.readBytes() to (bounds.outMimeType ?: ref.mime.takeUnless { it.contains('*') } ?: "image/jpeg")
    }

    suspend fun saveLocal(context: Context, dir: File, ref: com.androidharness.app.core.ImageRef): Boolean = withContext(Dispatchers.IO) {
        val (bytes, mime) = localImage(dir, ref) ?: return@withContext false
        saveToGallery(context, bytes, mime)
    }

    suspend fun shareLocal(context: Context, dir: File, ref: com.androidharness.app.core.ImageRef): Boolean = withContext(Dispatchers.IO) {
        val (bytes, mime) = localImage(dir, ref) ?: return@withContext false
        shareBytes(context, bytes, mime)
    }

    private fun saveToGallery(context: Context, bytes: ByteArray, mimeType: String): Boolean {
        val name = "harness-${System.currentTimeMillis()}.${extensionFor(mimeType)}"
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AndroidHarness")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching false
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching false
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                val parent = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                    ?: File(context.filesDir, "images")
                val dir = File(parent, "AndroidHarness").apply { mkdirs() }
                val file = File(dir, name)
                file.writeBytes(bytes)
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mimeType), null)
                true
            }
        }.getOrDefault(false)
    }

    private fun shareBytes(context: Context, bytes: ByteArray, mimeType: String): Boolean = runCatching {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        val file = File(dir, "harness-${System.currentTimeMillis()}.${extensionFor(mimeType)}")
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.update", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    private fun extensionFor(mimeType: String): String = when (mimeType) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/avif" -> "avif"
        else -> "jpg"
    }
}
