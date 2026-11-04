package com.androidharness.app.core

import org.junit.Assert.*
import org.junit.Test

class ChatResponseImagesTest {
    private val photo = ImageRef("photo.png", "image/png", imageId = "img_photo")
    private val crop = ImageRef("crop.png", "image/png", imageId = "img_crop", origin = "image_crop")
    @Test fun finalAnswerReferencesBindOnlyToImagesFromThisChat() {
        val history = listOf(ChatMessage(Role.USER, images = listOf(photo)), ChatMessage(Role.TOOL, images = listOf(crop)))
        val answer = "A short explanation.\n\n![Close-up](harness-image:img_crop)\n![Photo](harness-image:img_photo)\n![Other chat](harness-image:img_other)"
        assertEquals(listOf(crop, photo), ChatResponseImages.referenced(answer, history))
        assertTrue(ChatResponseImages.referenced(answer, emptyList()).isEmpty())
        assertNull(ChatResponseImages.imageId("file:///data/private.png"))
        assertNull(ChatResponseImages.imageId("harness-image:../../private.png"))
        assertTrue(ChatResponseImages.available(listOf(ChatMessage(Role.USER, images = listOf(photo.copy(name = "../private.png"))))).isEmpty())
    }
    @Test fun examplesInCodeDoNotAttachImagesAndUnlabelledUploadsKeepStableIds() {
        val history = listOf(ChatMessage(Role.USER, images = listOf(photo)))
        assertTrue(ChatResponseImages.referenced("```markdown\n![Example](harness-image:img_photo)\n```", history).isEmpty())
        assertTrue(ChatResponseImages.referenced("`![Example](harness-image:img_photo)`", history).isEmpty())
        val ref = ImageRef("upload.jpg", "image/jpeg")
        assertEquals(ChatResponseImages.id(ref), ChatResponseImages.id(ref.copy(caption = "A new caption")))
    }
    @Test fun chatImageGuideIsBriefAndZoomIsExplicitlyOptional() {
        val guide = ChatExperience.SYSTEM_PROMPT.substringAfter("Chat image tools:").substringBefore("\n\n")
        assertTrue(guide.split(Regex("\\s+")).size <= 190)
        for (tool in listOf("web_fetch", "browser_read", "view_chat_image", "zoom_chat_image")) assertTrue(guide.contains(tool))
        assertTrue(guide.contains("Zoom is optional")); assertTrue(guide.contains("Do not routinely zoom every image"))
        assertTrue(guide.contains("display_url")); assertTrue(guide.contains("wherever it best supports the answer"))
        assertFalse(com.androidharness.app.core.response.RichResponse.INSTRUCTIONS.contains("harness-image:"))
    }
}
