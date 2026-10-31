package com.androidharness.app.ui.learn

import com.androidharness.app.data.FeatureAvailability
import com.androidharness.app.ui.settings.AgentSettingsCatalog
import com.androidharness.app.ui.settings.SettingsPage
import com.androidharness.app.ui.settings.matchingSettingsEntries
import com.androidharness.app.ui.settings.settingsDeepLink
import com.androidharness.app.ui.settings.settingsHomePages
import com.androidharness.app.ui.settings.settingsTestStrings
import java.io.File
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class LearnCatalogTest {
    private val source = File("src/main/assets/learn/guide.json").readText()
    private val catalog = LearnCatalog.parse(source)

    @Test fun everySettingsSectionAndAppDestinationHasAnActualTutorialLink() {
        val actions = catalog.lessons.flatMap { it.actions }
        val pages = actions.filter { it.kind == "settings" }.map { SettingsPage.valueOf(it.target) }.toSet()
        assertEquals(SettingsPage.entries.toSet() - SettingsPage.LEARN, pages)
        val screens = actions.filter { it.kind == "screen" }.map { LearnDestination.valueOf(it.target) }.toSet()
        assertEquals(LearnDestination.entries.toSet(), screens)
        assertEquals(SettingsPage.LEARN, settingsHomePages.first())
        assertEquals(SettingsPage.LEARN, settingsDeepLink("learn"))
    }

    @Test fun lessonsHaveCompleteStepsLimitsRecoveryAndVerifiableOwningSources() {
        val ids = catalog.lessons.map { it.id }
        val groups = catalog.categories.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(groups.size, groups.toSet().size)
        assertEquals(groups.toSet(), catalog.lessons.map { it.category }.toSet())
        catalog.lessons.forEach { lesson ->
            assertTrue(lesson.id, lesson.title.isNotBlank() && lesson.summary.isNotBlank() && lesson.location.isNotBlank())
            assertTrue(lesson.id, lesson.before.isNotEmpty() && lesson.before.none(String::isBlank))
            assertTrue(lesson.id, lesson.steps.size >= 3 && lesson.steps.all { it.title.isNotBlank() && it.body.isNotBlank() })
            assertTrue(lesson.id, lesson.outcome.isNotBlank() && lesson.limits.size >= 2)
            assertTrue(lesson.id, lesson.fixes.isNotEmpty() && lesson.fixes.all { it.problem.isNotBlank() && it.solution.isNotBlank() })
            assertTrue(lesson.id, lesson.actions.isNotEmpty() && lesson.actions.all { it.kind in setOf("settings", "screen") && it.label.isNotBlank() })
            assertTrue(lesson.id, lesson.related.isNotEmpty() && lesson.related.all { it in ids && it != lesson.id })
            assertTrue(lesson.id, lesson.sources.isNotEmpty())
            lesson.sources.forEach { path -> assertTrue("${lesson.id}: $path", File("src/main/java/com/androidharness/app", path).isFile) }
            lesson.links.forEach { link ->
                val uri = URI(link.url)
                assertEquals("${lesson.id}: ${link.label}", "https", uri.scheme)
                assertFalse(link.label.isBlank())
                assertFalse(uri.host.isNullOrBlank())
                assertNull(uri.userInfo)
            }
        }
    }

    @Test fun beginnerSearchFindsActionsAndDetailsRatherThanOnlyTitles() {
        assertTrue(catalog.search("  BUY   subscription  ").any { it.id == "api-billing" })
        assertTrue(catalog.search("mocking module").any { it.id == "chat-visuals" })
        assertTrue(catalog.search("matching projector").any { it.id == "local-vision" })
        assertTrue(catalog.search("notification code").any { it.id == "wireless-adb" })
        assertEquals(listOf("github-import"), catalog.search("", category = "github", saved = setOf("github-import", "voice")).map { it.id })
        assertTrue(catalog.search("", saved = emptySet()).isEmpty())
        assertTrue(catalog.search("a-feature-that-does-not-exist").isEmpty())
    }

    @Test fun hiddenGlassIsAbsentFromGuideSettingsSearchAndAssistantControls() {
        assertFalse(FeatureAvailability.HARNESS_GLASS)
        assertFalse(source.contains("glass", ignoreCase = true))
        listOf("glass", "frosted", "refraction").forEach { term ->
            assertTrue(matchingSettingsEntries(term, settingsTestStrings()).isEmpty())
            assertTrue(AgentSettingsCatalog.search(term).isEmpty())
        }
        listOf("frosted_glass", "liquid_glass", "glass_transparency").forEach { assertNull(AgentSettingsCatalog[it]) }
    }
}
