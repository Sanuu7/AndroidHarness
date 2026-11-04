package com.androidharness.app.core.response

import com.androidharness.app.core.ChatExperience
import org.junit.Assert.*
import org.junit.Test

class RichResponseFeaturesTest {
    private val types = setOf("table", "tabs", "gallery", "calculator", "evidence", "diff", "writing")
    private fun examples(): List<String> = RichResponse.INSTRUCTIONS.lineSequence().filter { line -> types.any { line.startsWith("{\"type\":\"$it\"") } }
        .map { it.substringBefore(" (") }.toList()

    @Test fun everyAdvertisedComponentParsesAndProducesReadableCopyWithoutProtocolMarkup() {
        val examples = examples()
        assertEquals(types.size, examples.size)
        for (example in examples) {
            assertNotNull(example, RichResponse.decode(example))
            val fence = "```harness-ui\n$example\n```"
            val parts = RichResponse.parse("Introduction\n\n$fence\n\nConclusion")
            assertEquals(3, parts.size)
            assertTrue(parts[1] is RichResponse.Part.Content)
            assertFalse(RichResponse.plainText(fence).contains("harness-ui"))
            assertFalse(RichResponse.plainText(fence).isBlank())
            assertEquals(1, RichResponse.parse(ChatRichResponse.normalize(example)).filterIsInstance<RichResponse.Part.Content>().size)
        }
    }
    @Test fun bothAgentCatalogsAdvertiseEveryNewComponentWithRealExamples() {
        for (type in types + "chart") {
            assertTrue(ChatExperience.SYSTEM_PROMPT.contains("\"type\":\"$type\""))
            assertTrue(RichResponse.INSTRUCTIONS.contains("\"type\":\"$type\""))
        }
        assertTrue(ChatExperience.SYSTEM_PROMPT.contains("Chat does not create or read separate artifacts"))
        assertTrue(RichResponse.INSTRUCTIONS.contains("never claim a test passed"))
    }
    @Test fun partialNewComponentsStayHiddenUntilTheFenceCloses() {
        for (example in examples()) {
            val source = "```harness-ui\n$example\n```"
            for (end in "```harness".length until source.length) {
                assertTrue(RichResponse.parse(source.take(end), true).none { it is RichResponse.Part.Content })
            }
            assertTrue(RichResponse.parse(source, true).single() is RichResponse.Part.Content)
        }
    }
    @Test fun galleriesAcceptTheRequestedCountBeyondTwelveAndKeepPayloadAndUrlValidation() {
        val urls = (1..30).map { "https://example.com/photo-$it.jpg" }
        fun gallery(images: List<String>) = """{"type":"gallery","title":"Requested pictures","images":[${images.joinToString(",") { """{"url":"$it"}""" }}]}"""
        val decoded = RichResponse.decode(gallery(urls)) as RichResponse.Node.Gallery
        assertEquals(urls, decoded.images.map { it.url })
        assertNull(RichResponse.decode(gallery(emptyList())))
        assertNull(RichResponse.decode(gallery(urls + "file:///private/image")))
        val oversized = gallery(List(3_000) { urls.first() })
        assertTrue(oversized.length > RichResponse.MAX_JSON_CHARS)
        assertNull(RichResponse.decode(oversized))
    }
    @Test fun tableRejectsRaggedRowsAndSortsNumericValuesBeforeTextWithStableFiltering() {
        val source = """{"type":"table","title":"Scores","columns":["Name","Score"],"rows":[["B","10"],["A","2"],["C","unknown"],["A two","-1"]]}"""
        val table = RichResponse.decode(source) as RichResponse.Node.Table
        assertEquals(listOf(3, 1, 0, 2), ResponseTableData.indices(table, "", 1, true))
        assertEquals(listOf(1, 3), ResponseTableData.indices(table, "a", 0, true))
        assertEquals(listOf(2, 0, 1, 3), ResponseTableData.indices(table, "", 1, false))
        assertNull(RichResponse.decode(source.replace("[\"B\",\"10\"]", "[\"B\"]")))
        val quoted = table.copy(rows = listOf(listOf("A, B", "line\n\"quote\"")))
        assertTrue(ResponseTableData.csv(quoted, listOf(0)).contains("\"A, B\",\"line\n\"\"quote\"\"\""))
    }
    @Test fun chartsSupportAlignedDatasetsButRejectMismatchedLabelsAndExcessiveData() {
        val source = RichResponse.INSTRUCTIONS.lineSequence().first { it.startsWith("{\"type\":\"chart\",\"title\":\"Performance") }.substringBefore(" (")
        val chart = RichResponse.decode(source) as RichResponse.Node.Chart
        assertEquals(2, chart.series.size)
        assertTrue(RichResponse.describe(chart).contains("After\nDay 1: 12 ms"))
        assertNull(RichResponse.decode(source.replace("\"label\":\"Day 1\",\"value\":12", "\"label\":\"Different\",\"value\":12")))
        val tooMany = (0..120).joinToString(",") { "{\"label\":\"$it\",\"value\":$it}" }
        assertNull(RichResponse.decode("""{"type":"chart","title":"Too many","points":[$tooMany]}"""))
    }
    @Test fun calculatorsEvaluateArithmeticAndRejectExecutableOrMalformedExpressions() {
        assertEquals("0.001", CalculatorMath.format(0.001))
        assertEquals("0.3333333333", CalculatorMath.format(1.0 / 3))
        val vars = mapOf("hours" to 4.0, "rate" to 10.0)
        assertEquals(40.0, CalculatorMath.evaluate("hours * rate", vars)!!, 0.0)
        assertEquals(12.0, CalculatorMath.evaluate("max(2, hours) + sqrt(16) + abs(-4)", vars)!!, 0.0)
        assertEquals(0.25, CalculatorMath.evaluate("2 ^ -2", vars)!!, 0.0)
        assertEquals(-4.0, CalculatorMath.evaluate("-2 ^ 2", vars)!!, 0.0)
        assertEquals(10.0, CalculatorMath.evaluate("round((hours + 1) * 2)", vars)!!, 0.0)
        for (invalid in listOf("fetch('url')", "System.exit(0)", "unknown + 1", "1 +", "min(1)", "abs(1,2)", "(".repeat(30) + "1" + ")".repeat(30))) {
            assertFalse(invalid, CalculatorMath.isValid(invalid, vars.keys))
            assertNull(CalculatorMath.evaluate(invalid, vars))
        }
        assertTrue(CalculatorMath.isValid("1 / hours", vars.keys))
        assertNull(CalculatorMath.evaluate("1 / hours", vars + ("hours" to 0.0)))
        assertNull(CalculatorMath.evaluate("sqrt(-1)", vars))
        assertNull(CalculatorMath.evaluate("10 ^ 999", vars))
    }
    @Test fun invalidRangesUnknownInputsUnsafeLinksAndUnboundedPayloadsFailClosed() {
        val calc = examples().first { it.startsWith("{\"type\":\"calculator\"") }
        assertNull(RichResponse.decode(calc.replace("\"step\":0.5", "\"step\":0")))
        assertNull(RichResponse.decode(calc.replace("hours * rate", "unknown * rate")))
        assertNull(RichResponse.decode(calc.replace("\"id\":\"rate\"", "\"id\":\"hours\"")))
        for (url in listOf("file:///private/image", "javascript:alert(1)", "https://user:secret@example.com/image")) {
            assertNull(RichResponse.decode("""{"type":"gallery","title":"Image","images":[{"url":"$url"}]}"""))
        }
        assertNull(RichResponse.decode("""{"type":"evidence","title":"Check","items":[{"title":"Result","line":0}]}"""))
        assertNull(RichResponse.decode("""{"type":"diff","title":"Too much","before":"${"line\\n".repeat(401)}"}"""))
        var nested = """{"type":"text","text":"End"}"""
        repeat(8) { nested = """{"type":"tabs","title":"Nested","tabs":[{"title":"A","children":[$nested]}]}""" }
        assertNull(RichResponse.decode(nested))
    }
    @Test fun lineDiffPreservesBothOriginalsAndHandlesInsertionsAndDeletions() {
        for ((before, after) in listOf("" to "one", "one" to "", "a\nb\nc" to "a\nx\nc", "a\na\nb\n" to "a\nb\na\n")) {
            val diff = ResponseDiff.lines(before, after)
            assertEquals(before, diff.filter { it.marker != "+" }.joinToString("\n") { it.text })
            assertEquals(after, diff.filter { it.marker != "-" }.joinToString("\n") { it.text })
        }
        assertEquals(listOf(" ", "-", "+", " "), ResponseDiff.lines("a\nb\nc", "a\nx\nc").map { it.marker })
    }
}
