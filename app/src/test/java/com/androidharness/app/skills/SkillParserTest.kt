package com.androidharness.app.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillParserTest {

    private val valid = """
        ---
        name: systematic-debugging
        description: Something is broken and the cause is unknown. Reproduce, isolate, fix, prove.
        category: software-development
        ---

        # Debug

        Do the work.
    """.trimIndent()

    @Test
    fun `parses valid skill`() {
        val p = SkillParser.parse(valid)
        assertEquals("systematic-debugging", p.name)
        assertEquals("software-development", p.category)
        assertTrue(p.body.contains("Do the work."))
    }

    @Test
    fun `defaults category to general`() {
        val p = SkillParser.parse(
            """
            ---
            name: foo
            description: A short trigger line for this skill.
            ---
            Body here.
            """.trimIndent(),
        )
        assertEquals("general", p.category)
    }

    @Test(expected = SkillParseException::class)
    fun `missing name fails`() {
        SkillParser.parse(
            """
            ---
            description: hello there this is a trigger
            ---
            Body.
            """.trimIndent(),
        )
    }

    @Test(expected = SkillParseException::class)
    fun `missing description fails`() {
        SkillParser.parse(
            """
            ---
            name: foo
            ---
            Body.
            """.trimIndent(),
        )
    }

    @Test(expected = SkillParseException::class)
    fun `name with spaces fails`() {
        SkillParser.parse(
            """
            ---
            name: Foo Bar
            description: trigger line
            ---
            Body.
            """.trimIndent(),
        )
    }

    @Test(expected = SkillParseException::class)
    fun `name with parent path fails`() {
        SkillParser.parse(
            """
            ---
            name: ../evil
            description: trigger line
            ---
            Body.
            """.trimIndent(),
        )
    }

    @Test(expected = SkillParseException::class)
    fun `empty body fails`() {
        SkillParser.parse(
            """
            ---
            name: foo
            description: trigger line
            ---
            """.trimIndent(),
        )
    }

    @Test
    fun `catalog description is truncated to 80`() {
        val long = "A".repeat(120)
        val p = SkillParser.parse(
            """
            ---
            name: foo
            description: $long
            ---
            Body.
            """.trimIndent(),
        )
        assertEquals(80, p.catalogDescription.length)
        assertTrue(p.catalogDescription.endsWith("..."))
    }

    @Test
    fun `quoted description is unwrapped`() {
        val p = SkillParser.parse(
            """
            ---
            name: sketch
            description: "Throwaway HTML mockups: 2-3 design variants to compare."
            ---
            Body.
            """.trimIndent(),
        )
        assertEquals("Throwaway HTML mockups: 2-3 design variants to compare.", p.description)
    }

    @Test
    fun `slash expand includes instruction`() {
        val msg = buildSlashSkillMessage("git", "# Git\nDo git.", "commit this")
        assertTrue(msg.startsWith("[IMPORTANT: The user has invoked the \"git\" skill."))
        assertTrue(msg.contains("The user has provided the following instruction alongside the skill invocation: commit this"))
        assertEquals("git", slashInvokedSkillName(msg))
    }

    @Test
    fun `slash expand without instruction has no instruction line`() {
        val msg = buildSlashSkillMessage("git", "# Git\nDo git.", "")
        assertTrue(!msg.contains("instruction alongside"))
        assertEquals("git", slashInvokedSkillName(msg))
    }

    @Test
    fun `normal user text is not a slash skill`() {
        assertNull(slashInvokedSkillName("please fix the crash"))
    }

    @Test
    fun `catalog block is empty when index is blank`() {
        assertEquals("", buildSkillsPromptBlock(""))
    }

    @Test
    fun `catalog block wraps index`() {
        val block = buildSkillsPromptBlock("  design:\n    - sketch: Compare variants.\n")
        assertTrue(block.contains("## Skills (mandatory)"))
        assertTrue(block.contains("<available_skills>"))
        assertTrue(block.contains("skill_view(name)"))
        assertTrue(block.contains("sketch: Compare variants."))
    }
}
