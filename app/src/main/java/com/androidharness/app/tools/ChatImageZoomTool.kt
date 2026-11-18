package com.androidharness.app.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageRef
import com.androidharness.app.core.Role
import com.androidharness.app.data.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Stable identities refer only to images actually discovered in the owning chat. */
internal sealed interface ChatImageSource {
    val label: String
    val origin: String
    val id: String
    val displayRole: String
    data class Stored(val ref: ImageRef, override val label: String, override val origin: String = "user_upload", override val displayRole: String = "user") : ChatImageSource {
        override val id get() = ref.imageId ?: ChatImageInventory.id("stored:" + ref.name)
    }
    data class Remote(val url: String, override val label: String, override val origin: String = "remote_web", override val displayRole: String = "assistant") : ChatImageSource {
        override val id get() = ChatImageInventory.remoteId(url)
    }
}

internal object ChatImageInventory {
    private val markdownImage = Regex("!\\[([^\\]]*)]\\((https?://(?:[^()\\s]|\\([^()\\s]*\\))+)\\)")
    fun id(value: String): String = com.androidharness.app.core.ChatResponseImages.stableId(value)
    fun remoteId(url: String): String = id("url:" + (httpImageUrl(url) ?: url))
    fun from(messages: List<ChatMessage>): List<ChatImageSource> = messages.asReversed().asSequence()
        .filter { it.role == Role.USER || it.role == Role.ASSISTANT && it.toolCallId == null ||
            it.role == Role.TOOL && it.toolName in setOf("view_chat_image", "browser_read") &&
                (!it.isError || ImageInspectionDelivery.envelope(it.text)?.body?.get("available_image") is JsonObject) }
        .flatMap { message ->
            val role = message.role.name.lowercase()
            val who = if (message.role == Role.USER) "user attachment" else "assistant image"
            val envelope = if (message.role == Role.TOOL) ImageInspectionDelivery.envelope(message.text)?.body else null
            val context = if (message.role == Role.TOOL) null else cleanPageImageText(message.text.substringBefore('\n'))?.take(120)
            fun field(key: String) = (envelope?.get(key) as? JsonPrimitive)?.contentOrNull
            val stored = message.images.asReversed().map { original ->
                val ref = original.copy(
                    imageId = original.imageId ?: field("image_id"),
                    sourceUrl = original.sourceUrl ?: field("source_url"),
                    caption = original.caption?.takeUnless { it.trimStart().startsWith('{') || it.contains("\"image_attached\"") } ?: field("caption"),
                    origin = original.origin ?: field("origin"))
                ChatImageSource.Stored(ref, ref.caption ?: (who + context?.let { text -> ": $text" }.orEmpty()), provenance(ref, message.role), role)
            }
            val remote = if (message.role == Role.ASSISTANT) markdownImage.findAll(message.text).toList().asReversed().mapNotNull {
                val url = httpImageUrl(it.groupValues[2]) ?: return@mapNotNull null
                ChatImageSource.Remote(url, cleanPageImageText(it.groupValues[1])?.take(200) ?: who)
            } else emptyList()
            val rich = if (message.role == Role.ASSISTANT) com.androidharness.app.core.response.RichResponse.images(message.text)
                .asReversed().mapNotNull { image -> httpImageUrl(image.url)?.let { ChatImageSource.Remote(it, image.caption.ifBlank { who }.take(200)) } } else emptyList()
            (rich + remote + stored).asSequence()
        }.distinctBy { it.id }.take(20).toList()

    fun select(args: JsonObject, sources: List<ChatImageSource>): ChatImageSource? {
        val imageId = (args["image_id"] as? JsonPrimitive)?.content
        val number = (args["image_number"] as? JsonPrimitive)?.intOrNull
        if ("image_number" in args && number == null) return null
        val byId = imageId?.let { key -> sources.firstOrNull { it.id == key } }
        if (imageId != null && byId == null) return null
        val byNumber = number?.let { sources.getOrNull(it - 1) }
        if (number != null && byNumber == null) return null
        if (byId != null && byNumber != null && byId.id != byNumber.id) return null
        return byId ?: byNumber ?: sources.firstOrNull()
    }

    private fun provenance(ref: ImageRef, role: Role): String = when {
        ref.origin == "browser_screenshot" -> "browser_screenshot"
        ref.sourceUrl != null || ref.origin in setOf("web_image", "remote_web") -> "remote_web"
        ref.origin == "user_upload" || role == Role.USER -> "user_upload"
        ref.origin in setOf("generated_image", "image_crop", "tool_image") -> ref.origin!!
        role == Role.TOOL -> "tool_image"
        else -> "unknown_saved"
    }

    fun prompt(messages: List<ChatMessage>, metadata: (ImageRef) -> ImageRef = { it }): String =
        promptSources(from(messages), metadata)

    fun promptSources(images: List<ChatImageSource>, metadata: (ImageRef) -> ImageRef = { it }): String {
        if (images.isEmpty()) return ""
        return "\n## Images available for inspection\nUse image_id for stable identity. image_number stays fixed throughout this assistant turn; new images are appended. Numbers may change on the next user turn. origin describes provenance; display_role describes where it appeared. Unknown dimensions are reported after inspection.\n" +
            images.mapIndexed { i, source ->
                val ref = (source as? ChatImageSource.Stored)?.ref?.let(metadata)
                buildJsonObject {
                    put("image_number", JsonPrimitive(i + 1))
                    put("image_id", JsonPrimitive(source.id))
                    put("origin", JsonPrimitive(source.origin))
                    put("display_role", source.displayRole)
                    put("caption", JsonPrimitive(source.label.take(200)))
                    put("display_url", if (source is ChatImageSource.Stored) com.androidharness.app.core.ChatResponseImages.url(source.id)
                        else (source as ChatImageSource.Remote).url)
                    put("url", ((source as? ChatImageSource.Remote)?.url ?: ref?.sourceUrl)?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("width", ref?.width?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("height", ref?.height?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("mime_type", ref?.mime?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("original_width", ref?.originalWidth?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("original_height", ref?.originalHeight?.let { JsonPrimitive(it) } ?: JsonNull)
                }.toString()
            }.joinToString("\n") + "\n"
    }
}

/** Append new discoveries without changing any number already supplied to this turn's model. */
internal class ChatImageTurnInventory(messages: List<ChatMessage>) {
    private val entries = linkedMapOf<String, ChatImageSource>()
    init { update(messages) }
    fun update(messages: List<ChatMessage>) {
        ChatImageInventory.from(messages).forEach { source ->
            val previous = entries[source.id]
            // Prefer a saved copy over an expiring remote fetch, preserving its assigned slot.
            if (previous == null || source is ChatImageSource.Stored || previous !is ChatImageSource.Stored)
                entries[source.id] = source
        }
    }
    fun sources(): List<ChatImageSource> = entries.values.toList()
}

/** Chat-only image close-ups. Coordinates describe the entire image on a 0..1000 scale. */
internal class ChatImageZoomTool(
    private val images: ImageStore,
    private val history: () -> List<ChatMessage>,
) : Tool {
    private var sourceLookup: (() -> List<ChatImageSource>)? = null
    internal fun forInventory(lookup: () -> List<ChatImageSource>) = ChatImageZoomTool(images, history).apply { sourceLookup = lookup }
    override val name = "zoom_chat_image"
    override val description = "Inspect a close-up of an image in this chat. Prefer image_id from the current chat image list; image_number is the position in that exact list. Omit the rectangle for the full frame. Coordinates are relative to the full image, from 0 to 1000, with (0,0) at the top left. Use this when fine detail matters."
    override val parametersSchema = Schema.obj(mapOf(
        "image_id" to Schema.string("Stable image_id from the chat image list; preferred over a number."),
        "image_number" to Schema.integer("Image number from the chat image list; defaults to 1."),
        "left" to Schema.integer("Left edge from 0 to 1000."),
        "top" to Schema.integer("Top edge from 0 to 1000."),
        "right" to Schema.integer("Right edge from 0 to 1000; greater than left."),
        "bottom" to Schema.integer("Bottom edge from 0 to 1000; greater than top."),
    ))
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        if (ctx.sessionId == null) return@withContext imageInspectionFailure("image_not_found", "Open a chat first.")
        if (!ctx.imageInputAllowed) return@withContext imageInspectionFailure("images_disabled", "The current model cannot receive images.")
        val sources = sourceLookup?.invoke() ?: ChatImageInventory.from(history())
        val source = ChatImageInventory.select(args, sources)
            ?: return@withContext imageInspectionFailure("image_not_found", "Choose an image_id or image_number from the current list. Conflicting identities are rejected.")
        val number = sources.indexOf(source) + 1
        val keys = listOf("left", "top", "right", "bottom")
        val hasCrop = keys.any { it in args }
        if (hasCrop && !keys.all { it in args }) return@withContext imageInspectionFailure("invalid_crop", "Provide all four crop coordinates or omit all four for the full frame.", source)
        fun coordinate(name: String, default: Int) = if (name !in args) default else (args[name] as? JsonPrimitive)?.intOrNull
        val left = coordinate("left", 0)
        val top = coordinate("top", 0)
        val right = coordinate("right", 1000)
        val bottom = coordinate("bottom", 1000)
        if (left == null || top == null || right == null || bottom == null ||
            left !in 0..999 || top !in 0..999 || right !in 1..1000 || bottom !in 1..1000 || left >= right || top >= bottom)
            return@withContext imageInspectionFailure("invalid_crop", "Use a rectangle on the full image's 0..1000 coordinate scale.", source)
        var httpStatus: Int? = null
        var cached: Boolean? = null
        var mime: String? = null
        var originalBytes: Long? = null
        var sourceWidth = 0
        var sourceHeight = 0
        val original: Boolean
        val open: () -> java.io.InputStream = when (source) {
            is ChatImageSource.Stored -> {
                val file = images.savedImageFile(source.ref.name)
                    ?: return@withContext imageInspectionFailure("image_not_found", "The image is no longer available in this chat.", source)
                val savedOriginal = images.originalFile(source.ref.name)
                original = savedOriginal.isFile
                val selected = if (original) savedOriginal else file
                val info = images.metadata(source.ref)
                mime = info.originalMime ?: info.mime; cached = true; originalBytes = selected.length();
                { selected.inputStream() }
            }
            is ChatImageSource.Remote -> {
                val fetched = images.fetchRemote(source.url)
                val bytes = fetched.bytes?.takeIf { fetched.error == null }
                    ?: return@withContext imageInspectionFailure(fetched.error ?: "fetch_failed", "The source image could not be loaded. Do not describe unseen content.", source, fetched)
                httpStatus = fetched.httpStatus; cached = fetched.cached; mime = fetched.mimeType; originalBytes = bytes.size.toLong()
                original = true
                { bytes.inputStream() }
            }
        }
        fun bounds(width: Int, height: Int): Rect {
            if (sourceWidth == 0) { sourceWidth = width; sourceHeight = height }
            require(width in 1..100_000 && height in 1..100_000) { "Image dimensions are unsupported" }
            fun start(value: Int, size: Int) = (value.toLong() * size / 1000).toInt()
            fun end(value: Int, size: Int) = ((value.toLong() * size + 999) / 1000).toInt().coerceAtMost(size)
            return Rect(start(left, width), start(top, height), end(right, width), end(bottom, height))
        }
        var fallbackDownscaled = false
        val result = runCatching {
            // Region decoding avoids shrinking a large image before extracting its details.
            runCatching {
                open().use { stream ->
                    @Suppress("DEPRECATION")
                    val decoder = BitmapRegionDecoder.newInstance(stream, false) ?: error("Unsupported region decoder")
                    try {
                        val rect = bounds(decoder.width, decoder.height)
                        var sample = 1
                        while (maxOf(rect.width(), rect.height()) / sample > 1568) sample *= 2
                        val crop = decoder.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample })
                            ?: error("Could not decode the selected region")
                        try { images.saveCloseUp(crop) } finally { crop.recycle() }
                    } finally { decoder.recycle() }
                }
            }.getOrElse {
                // Some formats cannot be region-decoded. Bound the full decode,
                // then disclose when that fallback had to reduce detail.
                val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                open().use { stream -> BitmapFactory.decodeStream(stream, null, info) }
                bounds(info.outWidth, info.outHeight)
                var sample = 1
                while (maxOf(info.outWidth, info.outHeight) / sample > 1568) sample *= 2
                fallbackDownscaled = sample > 1
                val bitmap = open().use { stream -> BitmapFactory.decodeStream(stream, null,
                    BitmapFactory.Options().apply { inSampleSize = sample }) } ?: error("Unsupported image format")
                try {
                    val rect = bounds(bitmap.width, bitmap.height)
                    val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
                    try { images.saveCloseUp(crop) } finally { if (crop !== bitmap) crop.recycle() }
                } finally { bitmap.recycle() }
            }
        }.getOrElse { return@withContext imageInspectionFailure("decode_failed", "Could not create a close-up of this image: ${it.message ?: "unsupported image"}.", source) }
        val quality = when {
            !original -> "The original is unavailable; this close-up uses the saved reduced image, so fine detail may be lost."
            fallbackDownscaled -> "The original image had to be reduced before cropping, so fine detail may be lost."
            else -> "The close-up was made from the original image."
        }
        val identified = result.copy(imageId = ChatImageInventory.id("crop:${source.id}:$left,$top,$right,$bottom"),
            sourceUrl = (source as? ChatImageSource.Remote)?.url ?: (source as? ChatImageSource.Stored)?.ref?.sourceUrl,
            caption = source.label, origin = "image_crop")
        imageInspectionSuccess(identified, source, number, description = "Close-up of chat image $number ($left,$top to $right,$bottom on the full image). $quality",
            httpStatus = httpStatus, cached = cached, contentType = mime, sourceWidth = sourceWidth, sourceHeight = sourceHeight, sourceBytes = originalBytes,
            crop = listOf(left, top, right, bottom), degraded = !original || fallbackDownscaled, originalAvailable = original,
            fetchSource = if (source is ChatImageSource.Stored) "saved_copy" else if (cached == true) "cache" else "network")
    }
}
