package com.androidharness.app.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AutomationAiPlannerTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.parse("2026-09-07T12:30:00+05:30[Asia/Kolkata]")

    @Test fun parsesFollowUpQuestion() {
        val reply = AutomationAiPlanner.parse(
            """{"type":"question","message":"What time should it run?"}""",
            zone,
            now,
        )
        assertEquals("What time should it run?", (reply as AutomationAiReply.Question).message)
    }

    @Test fun parsesOneTimeDraftIntoLocalInstant() {
        val reply = AutomationAiPlanner.parse(
            """{"type":"draft","message":"Ready","title":"Build APK","prompt":"Build the debug APK","checkCommand":"","schedule":"ONCE","date":"2026-09-08","hour":20,"minute":15}""",
            zone,
            now,
        ) as AutomationAiReply.Draft
        assertEquals(AutomationSchedule.ONCE, reply.automation.schedule)
        assertEquals(
            ZonedDateTime.parse("2026-09-08T20:15:00+05:30[Asia/Kolkata]").toInstant().toEpochMilli(),
            reply.automation.scheduledAt,
        )
    }

    @Test fun rejectsPastOneTimeDraft() {
        val result = runCatching {
            AutomationAiPlanner.parse(
                """{"type":"draft","message":"Ready","title":"Build APK","prompt":"Build the debug APK","checkCommand":"","schedule":"ONCE","date":"2026-09-07","hour":8,"minute":0}""",
                zone,
                now,
            )
        }
        assertTrue(result.isFailure)
    }
}
