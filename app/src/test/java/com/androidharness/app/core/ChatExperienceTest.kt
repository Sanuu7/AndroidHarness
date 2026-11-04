package com.androidharness.app.core

import org.junit.Assert.*
import org.junit.Test

class ChatExperienceTest {
    @Test fun ordinaryChatAndCodeQuestionsDoNotOfferHandoff() {
        for (text in listOf("Help me brainstorm an idea", "Explain this Kotlin function", "What does Continue in Code mean?", "Don't continue in Code")) {
            assertFalse(text, ChatExperience.offersCode(listOf(ChatMessage(Role.USER, text))))
        }
    }

    @Test fun explicitRequestsAndSuccessfulAssistantOffersAreConditionalOnLatestTurn() {
        assertTrue(ChatExperience.offersCode(listOf(ChatMessage(Role.USER, "Please continue in Code"))))
        assertTrue(ChatExperience.offersCode(listOf(ChatMessage(Role.USER, "Can we switch to Code?"))))
        val turn = listOf(ChatMessage(Role.USER, "Apply this to my project"),
            ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("offer", "offer_code_handoff", "{}"))),
            ChatMessage(Role.TOOL, "Offered", toolCallId = "offer", toolName = "offer_code_handoff"))
        assertTrue(ChatExperience.offersCode(turn))
        assertFalse(ChatExperience.offersCode(turn.dropLast(1)))
        assertFalse(ChatExperience.offersCode(turn.dropLast(1) + turn.last().copy(isError = true)))
        assertFalse(ChatExperience.offersCode(turn + ChatMessage(Role.USER, "Just explain the idea for now")))
    }

    @Test fun cardCopyComesOnlyFromASuccessfulOfferInTheCurrentTurn() {
        val turn = listOf(ChatMessage(Role.USER, "Build this in my project"),
            ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("card", "offer_code_handoff",
                """{"title":"Build your reading tracker","reason":"Create the screens and connect local storage."}"""))),
            ChatMessage(Role.TOOL, "Offered", toolCallId = "card", toolName = "offer_code_handoff"))
        assertEquals(ChatExperience.CodeHandoffOffer("Build your reading tracker", "Create the screens and connect local storage."), ChatExperience.codeHandoffOffer(turn))
        assertNull(ChatExperience.codeHandoffOffer(turn.dropLast(1)))
        assertNull(ChatExperience.codeHandoffOffer(turn.dropLast(1) + turn.last().copy(isError = true)))
        assertNull(ChatExperience.codeHandoffOffer(turn + ChatMessage(Role.USER, "Just explain it")))
        assertEquals(ChatExperience.CodeHandoffOffer(), ChatExperience.codeHandoffOffer(turn + ChatMessage(Role.USER, "Switch to Code")))
    }

    @Test fun oldAndMalformedOffersUseFallbackCopyWithoutLeakingArguments() {
        fun offer(args: String) = ChatExperience.codeHandoffOffer(listOf(ChatMessage(Role.USER, "Apply this"),
            ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("card", "offer_code_handoff", args))),
            ChatMessage(Role.TOOL, "Offered", toolCallId = "card")))
        assertEquals(ChatExperience.CodeHandoffOffer(), offer("{}"))
        assertEquals(ChatExperience.CodeHandoffOffer(), offer("not json"))
        assertEquals(ChatExperience.CodeHandoffOffer(), offer("""{"title":false,"reason":4}"""))
        assertEquals(ChatExperience.CodeHandoffOffer("A useful title", "A short reason"), offer("""{"title":"  A   useful title  ","reason":"A  short reason"}"""))
        assertEquals(80, offer("""{"title":"${"x".repeat(200)}"}""")?.title?.length)
    }

    @Test fun chatPromptUsesTheLocalCalendarDateAcrossTimezoneBoundaries() {
        val instant = java.time.Instant.parse("2026-10-21T20:00:00Z")
        val india = ChatExperience.systemPrompt(instant.atZone(java.time.ZoneId.of("Asia/Kolkata")))
        val utc = ChatExperience.systemPrompt(instant.atZone(java.time.ZoneId.of("UTC")))
        assertTrue(india.contains("Current local date: 2026-10-22 (Asia/Kolkata)"))
        assertTrue(utc.contains("Current local date: 2026-10-21 (UTC)"))
    }

    @Test fun titlesUseTheUsersWordsWithoutAttachmentBodies() {
        assertEquals("Help me brainstorm an idea", ChatExperience.title("  Help me\nbrainstorm an idea  "))
        assertEquals("Review this", ChatExperience.title("Review this\n\n[Attached file: notes.txt (text/plain, 12 B)]\n```\nprivate text\n```"))
        assertEquals(72, ChatExperience.title("x".repeat(100)).length)
    }

    @Test fun chatModelLabelsDropProviderPrefixes() {
        assertEquals("deepseek-v4", ChatExperience.modelLabel("deepseek/deepseek-v4"))
        assertEquals("llama-3.1-70b", ChatExperience.modelLabel("meta-llama/llama-3.1-70b"))
        assertEquals("gpt-5", ChatExperience.modelLabel("gpt-5"))
        assertNull(ChatExperience.modelLabel(null))
        assertNull(ChatExperience.modelLabel("/"))
    }

    @Test fun answerHasImageSeesMarkdownBareAndComponentPictures() {
        assertTrue(ChatExperience.answerHasImage("Intro\n\n![Eiffel Tower](https://example.com/tower.jpg)"))
        assertTrue(ChatExperience.answerHasImage("![Upload](harness-image:img_1)"))
        assertTrue(ChatExperience.answerHasImage("Look:\nhttps://example.com/photo.webp\n"))
        assertTrue(ChatExperience.answerHasImage("```harness-ui\n{\"type\":\"profile\",\"name\":\"A\",\"image\":\"https://example.com/a.png\"}\n```"))
        assertTrue(ChatExperience.answerHasImage("```harness-ui\n{\"type\": \"gallery\",\"images\":[]}\n```"))
        assertFalse(ChatExperience.answerHasImage("Plain answer with a [link](https://example.com/page)."))
    }

    @Test fun alwaysImagePromptAsksForAPictureInTheFirstAnswer() {
        val always = ChatExperience.imageFrequencyPrompt(com.androidharness.app.data.ImageFrequency.ALWAYS)
        assertTrue(always.contains("first answer in a chat must have one"))
        assertTrue(always.contains("images=true"))
        assertFalse(ChatExperience.SYSTEM_PROMPT.contains("skip decorative pictures"))
    }

    @Test fun imageFrequencyPromptStatesTheChatSetting() {
        val none = ChatExperience.imageFrequencyPrompt(com.androidharness.app.data.ImageFrequency.NONE)
        assertTrue(none.contains("Images are turned off"))
        assertTrue(none.contains("do not call web_fetch"))
        assertTrue(ChatExperience.imageFrequencyPrompt(com.androidharness.app.data.ImageFrequency.LESS).contains("sparingly"))
        assertTrue(ChatExperience.imageFrequencyPrompt(com.androidharness.app.data.ImageFrequency.ALWAYS).contains("even if the user did not ask"))
        assertEquals(
            com.androidharness.app.data.ImageFrequency.MORE,
            ChatExperience.effectiveImageFrequency(
                com.androidharness.app.data.ImageFrequency.DEFAULT,
                com.androidharness.app.data.ImageFrequency.MORE,
            ),
        )
        assertEquals(
            com.androidharness.app.data.ImageFrequency.NONE,
            ChatExperience.effectiveImageFrequency(
                com.androidharness.app.data.ImageFrequency.NONE,
                com.androidharness.app.data.ImageFrequency.ALWAYS,
            ),
        )
    }

    @Test fun enabledImageFrequenciesLeaveImageCountAndPlacementToTheRequest() {
        for (frequency in com.androidharness.app.data.ImageFrequency.entries.filter { it != com.androidharness.app.data.ImageFrequency.NONE }) {
            val prompt = ChatExperience.imageFrequencyPrompt(frequency)
            assertTrue(prompt.contains("honor the user's requested count and placement"))
            assertTrue(prompt.contains("before, between, or after prose and rich components"))
            assertFalse(prompt.contains("One image is usually enough"))
            assertFalse(prompt.contains("two or three representative pictures"))
            assertFalse(prompt.contains("do not add galleries"))
        }
        assertFalse(ChatExperience.IMAGE_NUDGE.contains("near the top"))
    }

    @Test fun researchOffAllowsReadingLinksWithoutEncouragingSearch() {
        val prompt = ChatExperience.researchDepthPrompt(com.androidharness.app.data.SearchDepth.OFF)
        assertTrue(prompt.contains("web_search tool is disabled"))
        assertTrue(prompt.contains("still read links"))
        assertFalse(prompt.contains("source budget"))
    }

    @Test fun researchDepthPromptStatesTheTurnBudget() {
        val instant = ChatExperience.researchDepthPrompt(com.androidharness.app.data.SearchDepth.INSTANT)
        assertTrue(instant.contains("at most 5 distinct sources"))
        assertTrue(instant.contains("enforces this turn's source budget"))

        val deep = ChatExperience.researchDepthPrompt(com.androidharness.app.data.SearchDepth.DEEP)
        assertTrue(deep.contains("up to 20 distinct sources"))

        val research = ChatExperience.researchDepthPrompt(com.androidharness.app.data.SearchDepth.DEEP_RESEARCH)
        assertTrue(research.contains("until the 50-source budget is used"))
        assertTrue(research.contains("keep searching"))

        val max = ChatExperience.researchDepthPrompt(com.androidharness.app.data.SearchDepth.MAX_RESEARCH)
        assertTrue(max.contains("until the 100-source budget is used"))
        assertTrue(max.contains("keep searching"))

        val auto = ChatExperience.researchDepthPrompt(com.androidharness.app.data.SearchDepth.AUTO)
        assertTrue(auto.contains("no fixed source limit"))
        assertFalse(auto.contains("enforces this turn's source budget"))
    }
}
