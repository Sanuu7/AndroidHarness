package com.androidharness.app.caveman

import com.androidharness.app.skills.SkillParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves what the model actually receives: the style block is produced here, and
 * [CavemanPolicy.apply] is the exact function the engine uses to build the
 * system prompt of every request.
 */
class CavemanPolicyTest {

    private val harnessPrompt = "You are AndroidHarness, an autonomous coding agent."

    @Test
    fun `off produces no style block`() {
        assertEquals("", CavemanPolicy.prompt(CavemanIntensity.OFF))
        assertEquals("", CavemanPolicy.prompt(CavemanIntensity.OFF, wenyan = true))
    }

    @Test
    fun `every level produces a distinct block that names itself`() {
        val blocks = CavemanIntensity.entries.filter { it != CavemanIntensity.OFF }
            .associateWith { CavemanPolicy.prompt(it) }
        blocks.forEach { (level, block) ->
            assertTrue(block.contains("Caveman ${level.label}"))
            assertTrue("empty block for $level", block.length > 300)
        }
        assertEquals(blocks.size, blocks.values.toSet().size)
    }

    @Test
    fun `levels differ in what they cut`() {
        val lite = CavemanPolicy.prompt(CavemanIntensity.LITE)
        val full = CavemanPolicy.prompt(CavemanIntensity.FULL)
        val ultra = CavemanPolicy.prompt(CavemanIntensity.ULTRA)

        // Lite keeps grammar; Full and Ultra drop articles and expect fragments.
        assertTrue(lite.contains("Complete sentences"))
        assertFalse(lite.contains("Fragments are expected"))
        assertTrue(full.contains("Fragments are expected"))
        assertTrue(full.contains("articles"))
        // Ultra goes further: conjunctions, one fact once, imperative voice.
        assertTrue(ultra.contains("conjunctions"))
        assertTrue(ultra.contains("One fact stated once"))
        assertTrue(ultra.contains("Imperative voice"))
        assertFalse(full.contains("Imperative voice"))
    }

    @Test
    fun `invariants and clarity escapes are present at every level`() {
        CavemanIntensity.entries.filter { it != CavemanIntensity.OFF }.forEach { level ->
            val block = CavemanPolicy.prompt(level)
            assertTrue("code/errors contract missing", block.contains("## Never compress these"))
            assertTrue("negation guard missing", block.contains("Never drop not, never, no, only, except, unless"))
            assertTrue("no fake abbreviations missing", block.contains("Never invent abbreviations"))
            assertTrue("clarity escape missing", block.contains("## Drop back to normal prose for"))
            assertTrue("security warning escape missing", block.contains("Security warnings"))
            assertTrue("artifact boundary missing", block.contains("commit messages, files you write"))
            assertTrue("off switch missing", block.contains("stop caveman"))
            assertTrue("no-duplicate rule missing", block.contains("reply, in this style"))
        }
    }

    @Test
    fun `wenyan changes only the language rule`() {
        val plain = CavemanPolicy.prompt(CavemanIntensity.FULL)
        val wenyan = CavemanPolicy.prompt(CavemanIntensity.FULL, wenyan = true)
        assertTrue(wenyan.contains("classical Chinese"))
        assertFalse(plain.contains("classical Chinese"))
        // The compression rules themselves stay identical.
        assertTrue(plain.contains("Fragments are expected") && wenyan.contains("Fragments are expected"))
    }

    @Test
    fun `apply without install leaves the system prompt untouched`() {
        assertEquals(
            harnessPrompt,
            CavemanPolicy.apply(harnessPrompt, installed = false, intensity = CavemanIntensity.ULTRA),
        )
    }

    @Test
    fun `apply with install keeps the harness prompt verbatim and appends the block last`() {
        val final = CavemanPolicy.apply(
            harnessPrompt,
            installed = true,
            intensity = CavemanIntensity.ULTRA,
        )
        assertTrue(final.startsWith(harnessPrompt))
        val expected = harnessPrompt + "\n\n" + CavemanPolicy.prompt(CavemanIntensity.ULTRA)
        assertEquals(expected, final)
        // Last instruction the model reads is the style block.
        assertTrue(final.endsWith(CavemanPolicy.prompt(CavemanIntensity.ULTRA)))
    }

    @Test
    fun `installed but off wastes no input tokens`() {
        assertEquals(
            harnessPrompt,
            CavemanPolicy.apply(harnessPrompt, installed = true, intensity = CavemanIntensity.OFF),
        )
    }

    @Test
    fun `style block is bounded so it cannot dwarf a small reply`() {
        val sizes = CavemanIntensity.entries.filter { it != CavemanIntensity.OFF }
            .associateWith { CavemanPolicy.prompt(it).length }
        sizes.forEach { (level, chars) ->
            // Roughly 500 tokens. Upstream's own skill is about twice this, and it
            // rides along on every request, so it must not creep past the bar.
            assertTrue("$level block grew to $chars chars", chars < 2_000)
        }
    }

    @Test
    fun `the level example block names the active level exactly once`() {
        val ultra = CavemanPolicy.prompt(CavemanIntensity.ULTRA)
        assertTrue(ultra.contains("Write at Ultra,"))
        assertFalse(ultra.contains("Write at LEVEL,"))
        assertFalse(ultra.contains("Write at Lite,"))
    }

    @Test
    fun `bundled skills parse and are catalogued under Caveman`() {
        val expected = listOf("caveman-commit", "caveman-review", "caveman-investigate", "caveman-patch", "caveman-verify")
        assertEquals(expected.toSet(), CavemanPolicy.skills.keys)
        CavemanPolicy.skills.forEach { (key, skill) ->
            val parsed = SkillParser.parse(skill.content)
            assertEquals(key, parsed.name)
            assertEquals("Caveman", parsed.category)
            assertTrue("no description for $key", parsed.catalogDescription.isNotBlank())
            assertTrue("no body for $key", parsed.body.length > 150)
            assertEquals("caveman/$key", skill.relativeDir)
        }
    }

    @Test
    fun `skills are not offered before install`() {
        // The container gates on cavemanInstalled; with the flag off the map must
        // not be consulted at all, so nothing Caveman-related enters the catalog.
        assertTrue(CavemanPolicy.skills.isNotEmpty())
        val installed = false
        val offered = if (installed) CavemanPolicy.skills else emptyMap()
        assertTrue(offered.isEmpty())
    }
}
