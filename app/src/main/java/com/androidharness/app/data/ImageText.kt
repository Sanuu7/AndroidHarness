package com.androidharness.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageRef
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Reads the text in images on the phone, for models that cannot see images. It uses ML Kit's
 * Latin text recognizer, which ships inside the app: it works offline and nothing leaves the
 * device. What it reads for a saved image is kept next to the image, so a chat never reads the
 * same picture twice.
 */
class ImageText(private val store: ImageStore) {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val lock = Mutex()

    /** The text in [bitmap], or "" when there is none, or null when reading failed. */
    suspend fun read(bitmap: Bitmap): String? {
        val usable = if (bitmap.config == Bitmap.Config.HARDWARE)
            withContext(Dispatchers.Default) { bitmap.copy(Bitmap.Config.ARGB_8888, false) } ?: return null else bitmap
        return try {
            suspendCancellableCoroutine { done ->
                recognizer.process(InputImage.fromBitmap(usable, 0))
                    .addOnSuccessListener { done.resume(it.text.trim()) }
                    .addOnFailureListener { done.resume(null) }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        } finally {
            if (usable !== bitmap) usable.recycle()
        }
    }

    /** The text in a saved image, read once and then kept. Null when the image is gone or unreadable. */
    suspend fun of(ref: ImageRef): String? = lock.withLock {
        val cache = cacheFile(ref.name) ?: return@withLock null
        if (cache.isFile) return@withLock runCatching { cache.readText() }.getOrNull()
        // The full-size original reads small print far better than the bounded preview.
        val source = store.originalFile(ref.name).takeIf { it.isFile } ?: store.savedImageFile(ref.name) ?: return@withLock null
        val bitmap = withContext(Dispatchers.IO) { decode(source) } ?: return@withLock null
        val text = try { read(bitmap) } finally { bitmap.recycle() } ?: return@withLock null
        withContext(Dispatchers.IO) { runCatching { cache.parentFile?.mkdirs(); cache.writeText(text) } }
        text
    }

    /** Reads every image in [messages] and hands back a lookup by image name. */
    suspend fun forMessages(messages: List<ChatMessage>): (ImageRef) -> String? {
        val found = HashMap<String, String>()
        for (ref in messages.flatMap { it.images }.distinctBy { it.name }) of(ref)?.let { found[ref.name] = it }
        return { ref -> found[ref.name] }
    }

    private fun cacheFile(name: String): File? =
        if (name == File(name).name) File(File(store.imagesDir, "text"), "$name.txt") else null

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        /** Large enough for small print on a phone screenshot, small enough to read quickly. */
        private const val MAX_EDGE = 2_400

        /** What the model gets in place of an image it cannot see. */
        fun block(label: String, text: String): String =
            if (text.isBlank()) "[No text was found in the image $label. The model cannot see images, so its picture is not included.]"
            else "[Text read from the image $label on the device, because this model cannot see images. " +
                "Layout, pictures and colors are not included.]\n$text"
    }
}
