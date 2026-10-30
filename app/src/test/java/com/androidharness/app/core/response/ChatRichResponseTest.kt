package com.androidharness.app.core.response

import org.junit.Assert.*
import org.junit.Test

class ChatRichResponseTest {
    private val card = """{"type":"callout","tone":"info","title":"Today's lead story","text":"A report with {braces} and an escaped \"quote\"."}"""

    @Test fun screenshotStyleBareComponentsRenderWithSurroundingProseAndCitations() {
        val reply = "Here is today's news.\n$card\n[Source](https://example.com/report)"
        val parts = RichResponse.parse(ChatRichResponse.normalize(reply))
        assertTrue(parts[0] is RichResponse.Part.Markdown)
        assertEquals("Today's lead story", (parts[1] as RichResponse.Part.Content).node.let { it as RichResponse.Node.Callout }.title)
        assertTrue((parts[2] as RichResponse.Part.Markdown).text.contains("[Source]"))
        assertTrue("Code parsing remains strict", RichResponse.parse(reply).all { it is RichResponse.Part.Markdown })
    }

    @Test fun everyStreamedPrefixWithholdsComponentMetadataUntilItCanRender() {
        for (length in 1..card.length) {
            val parts = RichResponse.parse(ChatRichResponse.normalize("News\n" + card.take(length), true), true)
            assertFalse("prefix $length", parts.filterIsInstance<RichResponse.Part.Markdown>().any { it.text.contains('{') || it.text.contains("\"type\"") })
            assertTrue(parts.any { it is RichResponse.Part.Content || it is RichResponse.Part.Pending })
        }
    }

    @Test fun explicitCodeFencesAndOrdinaryJsonStayLiteralWhilePrettyComponentsRecover() {
        for (fence in listOf("```json", "```kotlin", "~~~json")) {
            val text = "$fence\n$card\n${fence.take(3)}\nordinary prose"
            assertEquals(text, ChatRichResponse.normalize(text))
        }
        val plain = "Data\n{\"type\":\"person\",\"name\":\"Ada\"}\nEnd"
        assertEquals(plain, ChatRichResponse.normalize(plain))
        val pretty = "{\n  \"type\": \"callout\",\n  \"title\": \"Summary\", \"text\": \"Details\"\n}"
        assertTrue(RichResponse.parse(ChatRichResponse.normalize(pretty)).single() is RichResponse.Part.Content)
        val alreadyFenced = "```harness-ui\n$card\n```"
        assertEquals(alreadyFenced, ChatRichResponse.normalize(alreadyFenced))
    }

    @Test fun brokenComponentsNeverLeakMetadataAndDoNotSwallowTrailingTextAfterCompleteObjects() {
        val broken = """{"type":"callout","title":12}"""
        val parts = RichResponse.parse(ChatRichResponse.normalize("$broken\nUseful trailing prose"))
        assertEquals(RichResponse.Part.Unavailable, parts[0])
        assertTrue((parts[1] as RichResponse.Part.Markdown).text.contains("Useful trailing prose"))
        assertEquals(RichResponse.Part.Unavailable, RichResponse.parse(ChatRichResponse.normalize(card.dropLast(1))).single())
    }

    @Test fun validBareComponentDoesNotRequireTypeToBeFirst() {
        val response = "Before\n{\"title\":\"Summary\",\"text\":\"Details\",\"type\":\"callout\"}\nAfter"
        val parts = RichResponse.parse(ChatRichResponse.normalize(response))
        assertEquals("Summary", ((parts[1] as RichResponse.Part.Content).node as RichResponse.Node.Callout).title)
        assertTrue((parts[2] as RichResponse.Part.Markdown).text.contains("After"))
        val ordinary = "{\"name\":\"Ada\",\"type\":\"person\"}"
        assertEquals(ordinary, ChatRichResponse.normalize(ordinary))
    }

    @Test fun screenshotStyleDirectivesRenderAndKeepSurroundingText() {
        val reply = """Intro
::card
title: Mobile Harness
text: A description, with punctuation.
::
::callout
tone: info
title: The verdict
text: A useful summary.
::
::profile
name: AndroidHarness
subtitle: Android app
description: An app for coding.
facts: [{label:License,value:MIT,source:https://example.com/license}]
sources: [{title:Repository,url:https://example.com/repo}]
::
After"""
        val parts = RichResponse.parse(ChatRichResponse.normalize(reply))
        val nodes = parts.filterIsInstance<RichResponse.Part.Content>().map { it.node }
        assertEquals("Mobile Harness", (nodes[0] as RichResponse.Node.Card).title)
        assertEquals("The verdict", (nodes[1] as RichResponse.Node.Callout).title)
        val profile = nodes[2] as RichResponse.Node.Profile
        assertEquals("MIT", profile.facts.single().value)
        assertEquals("https://example.com/repo", profile.sources.single().url)
        assertTrue((parts.last() as RichResponse.Part.Markdown).text.contains("After"))
        val fenced = "```text\n::card\ntitle: Example\ntext: source\n::\n```"
        assertEquals(fenced, ChatRichResponse.normalize(fenced))
        assertTrue(RichResponse.parse(ChatRichResponse.normalize("::card\ntitle: Partial", true), true).single() is RichResponse.Part.Pending)
        val preview = "::preview\ntitle: Demo\nhtml: <p>Hello</p>\n::"
        val previewNode = (RichResponse.parse(ChatRichResponse.normalize(preview)).single() as RichResponse.Part.Content).node as RichResponse.Node.Preview
        assertEquals("<p>Hello</p>", previewNode.html)
    }

    @Test fun attributeDirectivesTakeTheirBodyAsText() {
        val reply = """Bottom line
::callout{tone="info" title="Key takeaway"}
The risk comes mainly from *smoking* it.
Avoiding smoke removes the exposure.
::
:::card{title="Next step"}
Talk to a doctor.
:::
After"""
        val parts = RichResponse.parse(ChatRichResponse.normalize(reply))
        val nodes = parts.filterIsInstance<RichResponse.Part.Content>().map { it.node }
        val callout = nodes[0] as RichResponse.Node.Callout
        assertEquals("Key takeaway", callout.title)
        assertEquals("info", callout.tone)
        assertEquals("The risk comes mainly from *smoking* it.\nAvoiding smoke removes the exposure.", callout.text)
        assertEquals("Talk to a doctor.", (nodes[1] as RichResponse.Node.Card).text)
        assertTrue((parts.last() as RichResponse.Part.Markdown).text.contains("After"))
        assertTrue(parts.none { it is RichResponse.Part.Markdown && it.text.contains("::") })
    }
}
