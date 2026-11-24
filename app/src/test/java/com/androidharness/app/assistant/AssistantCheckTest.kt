package com.androidharness.app.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantCheckTest {
    private fun facts(
        active: Boolean = false,
        voiceFeature: Boolean = true,
        roleAvailable: Boolean? = true,
        mainProfile: Boolean = true,
        manufacturer: String = "vivo",
    ) = AssistantFacts(active, voiceFeature, roleAvailable, mainProfile, lowRam = false,
        manufacturer = manufacturer, brand = manufacturer, model = "X1", release = "15", sdk = 35)

    @Test fun `active assistant needs no hint whatever else is true`() {
        assertEquals(AssistantAvailability.ACTIVE, AssistantCheck.availability(facts(active = true, voiceFeature = false)))
    }

    @Test fun `a second profile explains itself before the role does`() {
        assertEquals(AssistantAvailability.OTHER_PROFILE, AssistantCheck.availability(facts(mainProfile = false, roleAvailable = false)))
    }

    @Test fun `missing feature or role means the phone does not allow it`() {
        assertEquals(AssistantAvailability.UNSUPPORTED, AssistantCheck.availability(facts(voiceFeature = false)))
        assertEquals(AssistantAvailability.UNSUPPORTED, AssistantCheck.availability(facts(roleAvailable = false)))
    }

    @Test fun `phones without roles are judged by the feature alone`() {
        assertEquals(AssistantAvailability.PICK, AssistantCheck.availability(facts(roleAvailable = null)))
    }

    @Test fun `samsung gets its own path and everyone else the search tip`() {
        assertEquals(AssistantAvailability.PICK_SAMSUNG, AssistantCheck.availability(facts(manufacturer = "samsung")))
        assertEquals(AssistantAvailability.PICK, AssistantCheck.availability(facts(manufacturer = "Xiaomi")))
    }

    @Test fun `report names the phone, the result and every check`() {
        val report = AssistantCheck.report(facts(roleAvailable = null, voiceFeature = false), "4.0")
        listOf("App version: 4.0", "Phone: vivo X1", "Android: 15 (API 35)", "Result: unsupported",
            "Voice assistant feature: no", "Assistant role available: not on this Android version", "Main profile: yes")
            .forEach { assertTrue(it, report.contains(it)) }
    }
}
