package com.androidharness.app.core.response

import org.junit.Assert.assertEquals
import org.junit.Test

class ClaimMarkerTextTest {
    @Test fun copiedAndSpokenTextLeavesClaimMarkersOut() {
        val answer = "The bridge opened in 1932 [Sources give 1931 and 1932](check:https://a.test/x,https://b.test/y). It is 503 m long."
        assertEquals("The bridge opened in 1932. It is 503 m long.", RichResponse.plainText(answer))
    }

    @Test fun ordinaryLinksStay() {
        val answer = "See [the docs](https://a.test/docs)."
        assertEquals(answer, RichResponse.plainText(answer))
    }
}
