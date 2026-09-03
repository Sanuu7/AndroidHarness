package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageData
import com.androidharness.app.core.ImageRef
import com.androidharness.app.core.Role
import com.androidharness.app.llm.visionCapable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionDegradationTest {

    @Test
    fun `visionCapable correctly classifies multimodal vs text-only models`() {
        // Vision capable models
        assertTrue(visionCapable("gpt-4o"))
        assertTrue(visionCapable("gpt-4o-mini"))
        assertTrue(visionCapable("claude-3-7-sonnet-20250219"))
        assertTrue(visionCapable("claude-3-5-sonnet-latest"))
        assertTrue(visionCapable("gemini-2.5-flash"))
        assertTrue(visionCapable("qwen/qwen-2.5-vl-72b-instruct"))
        assertTrue(visionCapable("meta-llama/llama-3.2-11b-vision-instruct"))

        // Text-only models
        assertFalse(visionCapable("deepseek-chat"))
        assertFalse(visionCapable("deepseek-coder"))
        assertFalse(visionCapable("deepseek-reasoner"))
        assertFalse(visionCapable("deepseek/deepseek-r1"))
        assertFalse(visionCapable("qwen/qwen-2.5-coder-32b-instruct"))
        assertFalse(visionCapable("o1-mini"))
        assertFalse(visionCapable("codellama/codellama-34b-instruct"))
    }

    @Test
    fun `stripImages removes base64 imageData and inserts descriptive text placeholder`() {
        val imageRef = ImageRef("screenshot.png", "image/png")
        val imageData = ImageData("image/png", "fakeBase64Bytes==")
        val msgWithImage = ChatMessage(
            role = Role.USER,
            text = "Look at this screenshot",
            images = listOf(imageRef),
            imageData = listOf(imageData),
        )
        val textOnlyMsg = ChatMessage(
            role = Role.USER,
            text = "Plain message without image",
        )

        val stripped = ContextHygiene.stripImages(listOf(msgWithImage, textOnlyMsg))
        assertEquals(2, stripped.size)

        // First message: imageData cleared, notice added
        assertTrue(stripped[0].imageData.isEmpty())
        assertTrue(stripped[0].text.contains("Look at this screenshot"))
        assertTrue(stripped[0].text.contains("screenshot.png"))
        assertTrue(stripped[0].text.contains("omitted"))

        // Second message: unmodified
        assertEquals("Plain message without image", stripped[1].text)
        assertTrue(stripped[1].imageData.isEmpty())
    }

    @Test
    fun `isVisionError detects common provider error strings for unsupported vision`() {
        val errorOpenAi = "400 Bad Request: 'messages.[0].content.[1]' is invalid. model does not support image input."
        val errorOpenRouter = "HTTP 400: unsupported parameter: 'image_url' for this model"
        val errorAnthropic = "400 messages: images are not supported for this model"
        val errorGemini = "400 Invalid argument: Model does not support multimodal input (inlineData)"
        val errorGeneral = "HTTP 400: vision not supported"
        val unrelatedError = "HTTP 401: Unauthorized API key"

        assertTrue(AgentEngine.isVisionError(errorOpenAi))
        assertTrue(AgentEngine.isVisionError(errorOpenRouter))
        assertTrue(AgentEngine.isVisionError(errorAnthropic))
        assertTrue(AgentEngine.isVisionError(errorGemini))
        assertTrue(AgentEngine.isVisionError(errorGeneral))

        assertFalse(AgentEngine.isVisionError(unrelatedError))
    }
}
