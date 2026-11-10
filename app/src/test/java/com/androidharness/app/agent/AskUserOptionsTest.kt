package com.androidharness.app.agent

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserOptionsTest {
    private fun parse(raw: String): List<String> = AskUserOptions.parse(Json.parseToJsonElement(raw))

    @Test fun commasInsideAListItemStayInThatOption() {
        assertEquals(
            listOf("Pizza, with cheese, and basil", "Sushi, with rice, and nori"),
            parse("""["Pizza, with cheese, and basil", "Sushi, with rice, and nori"]"""),
        )
    }

    @Test fun twoPartItemsAndThreePartItemsBothStayWhole() {
        assertEquals(
            listOf("Yes, definitely", "Red, green, blue"),
            parse("""["Yes, definitely", "Red, green, blue"]"""),
        )
    }

    @Test fun objectItemsKeepTheirCommas() {
        assertEquals(
            listOf("Fast, cheap, and good", "Slow, careful"),
            parse("""[{"label":"Fast, cheap, and good"},{"text":"Slow, careful"}]"""),
        )
    }

    @Test fun aJsonEncodedListKeepsCommasInsideItsItems() {
        assertEquals(
            listOf("One, two, three", "Four"),
            parse("\"[\\\"One, two, three\\\", \\\"Four\\\"]\""),
        )
    }

    @Test fun pipeAndLineBreakSeparatorsKeepCommasInsideThePieces() {
        assertEquals(listOf("Tea, hot", "Coffee, iced, sweet"), parse("\"Tea, hot | Coffee, iced, sweet\""))
        assertEquals(listOf("Tea, hot", "Coffee, iced, sweet"), parse("\"Tea, hot\\nCoffee, iced, sweet\""))
    }

    @Test fun aBareCommaSeparatedStringStillSplitsIntoThreeOrMoreOptions() {
        assertEquals(listOf("Red", "Green", "Blue"), parse("\"Red, Green, Blue\""))
    }

    @Test fun aBareStringWithOneCommaOrTwoPiecesIsASingleOption() {
        assertEquals(listOf("Yes, definitely"), parse("\"Yes, definitely\""))
        assertEquals(listOf("Just one"), parse("\"Just one\""))
    }

    @Test fun aWrapperObjectStillSplitsItsBareString() {
        assertEquals(listOf("A", "B", "C"), parse("""{"options":"A, B, C"}"""))
        assertEquals(listOf("A, b, c", "D"), parse("""{"options":["A, b, c","D"]}"""))
    }

    @Test fun mapFormUsesTheValuesAsTheyAre() {
        assertEquals(listOf("a, b, c", "d"), parse("""{"1":"a, b, c","2":"d"}"""))
    }

    @Test fun emptyAndBrokenInputGivesNoOptions() {
        assertTrue(AskUserOptions.parse(null).isEmpty())
        assertTrue(parse("null").isEmpty())
        assertTrue(parse("[]").isEmpty())
        assertTrue(parse("""["", "   "]""").isEmpty())
    }

    @Test fun aRecommendedNameWithCommasStillMatchesItsOption() {
        val options = AskUserOptions.parse(Json.parseToJsonElement("""["Keep, merge, and test", "Discard"]"""))
        val named = AskUserOptions.parse(Json.parseToJsonElement("""["Keep, merge, and test"]"""))
        assertEquals(listOf(0), QuestionAutoContinue.resolve(options, named, multiSelect = false).recommended)
        assertEquals(listOf("Keep, merge, and test", "Discard"), options)
    }
}
