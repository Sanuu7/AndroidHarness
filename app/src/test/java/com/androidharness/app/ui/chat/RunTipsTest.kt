package com.androidharness.app.ui.chat

import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.SteeringMode
import com.androidharness.app.ui.settings.SettingsPage
import com.androidharness.app.ui.settings.settingsDeepLink
import com.androidharness.app.ui.settings.settingsDeepLinkAnchor
import com.androidharness.app.ui.settings.settingsTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunTipsTest {
    private val settings = AppSettings()

    @Test fun idsAreUnique() {
        assertEquals(RunTips.all.size, RunTips.all.map { it.id }.toSet().size)
    }

    @Test fun turningTipsOffShowsNothing() {
        assertNull(RunTips.next(settings.copy(runTipsEnabled = false), isChat = true))
    }

    @Test fun rotationVisitsEveryFittingTipBeforeRepeating() {
        for (isChat in listOf(true, false)) {
            val seen = mutableListOf<String>()
            var cursor = 0
            repeat(RunTips.all.size) {
                val (tip, next) = RunTips.next(settings, isChat, cursor)!!
                seen += tip.id
                cursor = next
            }
            val fitting = RunTips.all.filter {
                (it.scope == TipScope.BOTH || (it.scope == TipScope.CHAT) == isChat) && it.relevant(settings)
            }.map { it.id }
            assertEquals(fitting.toSet(), seen.toSet())
            assertEquals(fitting, seen.take(fitting.size))
        }
    }

    @Test fun chatAndCodeOnlyTipsStayInTheirSection() {
        val chatTips = generateSequence(0 to "") { (cursor, _) ->
            RunTips.next(settings, isChat = true, cursor)?.let { (tip, next) -> next to tip.id }
        }.drop(1).take(RunTips.all.size).map { it.second }.toSet()
        assertTrue("permissions" !in chatTips && "plan" !in chatTips)
        assertTrue("chat_agents" in chatTips)
    }

    @Test fun tipsForThingsAlreadyOnAreSkipped() {
        val auto = RunTips.all.filter { it.relevant(settings) }.map { it.id }
        assertTrue("steer_auto" in auto && "steer_after" !in auto)
        val after = RunTips.all.filter { it.relevant(settings.copy(steeringMode = SteeringMode.AFTER_TASK, rumbleEnabled = true)) }.map { it.id }
        assertTrue("steer_after" in after && "steer_auto" !in after && "rumble" !in after)
    }

    @Test fun cursorWrapsAround() {
        val (_, next) = RunTips.next(settings, isChat = false, RunTips.all.size - 1)!!
        assertTrue(next in 0 until RunTips.all.size)
        assertNotEquals(null, RunTips.next(settings, isChat = false, -7))
    }

    @Test fun settingsLinksOpenThePageAndControl() {
        for (tip in RunTips.all) {
            val page = tip.page ?: continue
            val target = settingsTarget(page, tip.anchor)
            assertEquals(page, settingsDeepLink(target))
            assertEquals(tip.anchor, settingsDeepLinkAnchor(target))
        }
        assertEquals(SettingsPage.AGENT, settingsDeepLink(settingsTarget(SettingsPage.AGENT)))
        assertNull(settingsDeepLinkAnchor(settingsTarget(SettingsPage.AGENT)))
        assertNull(settingsDeepLink("page:NOPE:0"))
    }
}
