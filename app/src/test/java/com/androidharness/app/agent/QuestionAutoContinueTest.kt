package com.androidharness.app.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class QuestionAutoContinueTest {
    @Test fun withoutAHintTheFirstOptionIsRecommended() {
        val result = QuestionAutoContinue.resolve(listOf("Room", "SQLDelight"), emptyList(), multiSelect = false)
        assertEquals(listOf("Room", "SQLDelight"), result.labels)
        assertEquals(listOf(0), result.recommended)
    }

    @Test fun theModelCanNameTheRecommendationByTextOrByNumber() {
        val options = listOf("Keep both", "Use the new file", "Use the old file")
        assertEquals(listOf(1), QuestionAutoContinue.resolve(options, listOf("use the NEW file"), false).recommended)
        assertEquals(listOf(2), QuestionAutoContinue.resolve(options, listOf("3"), false).recommended)
        assertEquals(listOf(0), QuestionAutoContinue.resolve(options, listOf("something else", "9"), false).recommended)
    }

    @Test fun aRecommendedMarkerInTheLabelCountsAndIsRemovedFromTheText() {
        val result = QuestionAutoContinue.resolve(listOf("Skip", "Retry (Recommended)", "Abort [recommended]"), emptyList(), multiSelect = false)
        assertEquals(listOf("Skip", "Retry", "Abort"), result.labels)
        assertEquals(listOf(1), result.recommended)
    }

    @Test fun aSingleChoiceKeepsOneRecommendationAndAMultipleChoiceKeepsAll() {
        val options = listOf("Tests", "Docs", "Lint")
        assertEquals(listOf(0), QuestionAutoContinue.resolve(options, listOf("Tests", "Lint"), false).recommended)
        assertEquals(listOf(0, 2), QuestionAutoContinue.resolve(options, listOf("Lint", "Tests"), true).recommended)
    }

    @Test fun aQuestionWithoutOptionsRecommendsNothing() {
        val result = QuestionAutoContinue.resolve(emptyList(), listOf("1"), multiSelect = false)
        assertTrue(result.labels.isEmpty() && result.recommended.isEmpty())
        assertNull(QuestionAutoContinue.answer(QuestionRequest("c", "What name?", emptyList())))
    }

    @Test fun theAnswerJoinsTheRecommendedOptions() {
        assertEquals("Docs", QuestionAutoContinue.answer(QuestionRequest("c", "Next?", listOf("Tests", "Docs"), recommended = listOf(1))))
        assertEquals("Tests; Lint", QuestionAutoContinue.answer(
            QuestionRequest("c", "Next?", listOf("Tests", "Docs", "Lint"), multiSelect = true, recommended = listOf(0, 2))))
    }

    @Test fun theModelIsToldNoPersonMadeTheChoice() {
        val text = QuestionAutoContinue.toolResult("Docs")
        assertTrue(text.contains("Docs"))
        assertTrue(text.contains("not the user's own choice"))
        assertFalse(text.startsWith("The user answered"))
    }

    @Test fun theCountdownReadsAsMinutesAndSeconds() {
        assertEquals("5:00", QuestionAutoContinue.remaining(300_000, 0))
        assertEquals("1:01", QuestionAutoContinue.remaining(61_000, 0))
        assertEquals("0:01", QuestionAutoContinue.remaining(999, 0))
        assertEquals("0:00", QuestionAutoContinue.remaining(1_000, 5_000))
    }

    @Test fun theDefaultWaitIsFiveMinutes() {
        assertEquals(5 * 60 * 1000L, QuestionAutoContinue.DELAY_MS)
    }

    @Test fun anUnansweredQuestionExpiresAfterTheDelay() = runBlocking {
        val open = MutableStateFlow(true)
        var expired = false
        val askedAt = System.currentTimeMillis()
        withTimeout(5_000) {
            QuestionAutoContinue.watch(askedAt, MutableStateFlow(true), open,
                onDeadline = {}, onExpired = { expired = true; open.value = false }, delayMs = 60)
        }
        assertTrue(expired)
        assertTrue(System.currentTimeMillis() - askedAt >= 55)
    }

    @Test fun theDeadlineStartsWhenTheQuestionWasAsked() = runBlocking {
        val deadlines = Collections.synchronizedList(mutableListOf<Long?>())
        val open = MutableStateFlow(true)
        val askedAt = System.currentTimeMillis() - 100
        val job = launch {
            QuestionAutoContinue.watch(askedAt, MutableStateFlow(true), open,
                onDeadline = { deadlines += it }, onExpired = { open.value = false }, delayMs = 160)
        }
        withTimeout(5_000) { job.join() }
        assertEquals(listOf<Long?>(askedAt + 160), deadlines.toList())
    }

    @Test fun anAnsweredQuestionNeverExpires() = runBlocking {
        val open = MutableStateFlow(true)
        var expired = false
        val job = launch {
            QuestionAutoContinue.watch(System.currentTimeMillis(), MutableStateFlow(true), open,
                onDeadline = {}, onExpired = { expired = true }, delayMs = 150)
        }
        delay(30)
        open.value = false
        withTimeout(5_000) { job.join() }
        delay(200)
        assertFalse(expired)
    }

    @Test fun turnedOffMeansNoCountdownAtAll() = runBlocking {
        val deadlines = Collections.synchronizedList(mutableListOf<Long?>())
        var expired = false
        val job = launch {
            QuestionAutoContinue.watch(System.currentTimeMillis(), MutableStateFlow(false), MutableStateFlow(true),
                onDeadline = { deadlines += it }, onExpired = { expired = true }, delayMs = 50)
        }
        delay(200)
        job.cancel()
        assertFalse(expired)
        assertEquals(listOf<Long?>(null), deadlines.toList())
    }

    @Test fun turningItOffCancelsTheCountdownAndTurningItOnStartsAFreshOne() = runBlocking {
        val deadlines = Collections.synchronizedList(mutableListOf<Long?>())
        val enabled = MutableStateFlow(true)
        val open = MutableStateFlow(true)
        var expiredAt = 0L
        val job = launch {
            QuestionAutoContinue.watch(System.currentTimeMillis(), enabled, open,
                onDeadline = { deadlines += it }, onExpired = { expiredAt = System.currentTimeMillis(); open.value = false }, delayMs = 250)
        }
        delay(60)
        enabled.value = false
        delay(400)
        assertEquals("the old countdown must not fire while the setting is off", 0L, expiredAt)
        val reenabled = System.currentTimeMillis()
        enabled.value = true
        withTimeout(5_000) { job.join() }
        assertTrue("a fresh wait starts when it is turned back on", expiredAt - reenabled >= 240)
        assertEquals(3, deadlines.size)
        assertNull(deadlines[1])
        assertTrue(deadlines[2]!! >= reenabled + 250)
    }
}
