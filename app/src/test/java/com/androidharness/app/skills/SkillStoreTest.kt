package com.androidharness.app.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun skill(name: String, category: String, extra: String = "Body of $name."): String = """
        ---
        name: $name
        description: Trigger line for $name that tells the model when to load it.
        category: $category
        ---

        $extra
    """.trimIndent()

    private fun store(
        bundled: Map<String, SkillStore.BundledSkill> = mapOf(
            "git" to SkillStore.BundledSkill("git", skill("git", "repo")),
            "sketch" to SkillStore.BundledSkill(
                "sketch",
                skill("sketch", "design"),
                mapOf("references/catalog.md" to "# brands\nStripe: #635bff"),
            ),
        ),
        disabled: Set<String> = emptySet(),
        project: java.io.File? = null,
    ): SkillStore {
        val user = tmp.newFolder("user-skills")
        return SkillStore(
            bundled = bundled,
            userDir = user,
            projectDir = { project },
            disabled = { disabled },
        )
    }

    @Test
    fun `catalog lists enabled bundled skills grouped by category`() {
        val catalog = store().catalog()
        assertTrue(catalog.contains("  design:"))
        assertTrue(catalog.contains("    - sketch:"))
        assertTrue(catalog.contains("  repo:"))
        assertTrue(catalog.contains("    - git:"))
        assertTrue(catalog.contains("<available_skills>"))
    }

    @Test
    fun `disabled skills drop out of catalog and slash names`() {
        val s = store(disabled = setOf("git"))
        assertTrue("git" !in s.slashNames())
        assertTrue(!s.catalog().contains("- git:"))
        assertTrue(s.view("git").isFailure)
    }

    @Test
    fun `user skill overrides bundled`() {
        val s = store()
        s.saveUser(skill("git", "repo", "User git body."))
        val viewed = s.view("git").getOrThrow()
        assertEquals(SkillSource.USER, viewed.source)
        assertTrue(viewed.content.contains("User git body."))
    }

    @Test
    fun `project skill wins over user and bundled`() {
        val project = tmp.newFolder("project-skills")
        val dir = java.io.File(project, "git").apply { mkdirs() }
        java.io.File(dir, "SKILL.md").writeText(skill("git", "repo", "Project git body."))
        val s = store(project = project)
        s.saveUser(skill("git", "repo", "User git body."))
        val viewed = s.view("git").getOrThrow()
        assertEquals(SkillSource.PROJECT, viewed.source)
        assertTrue(viewed.content.contains("Project git body."))
    }

    @Test
    fun `support file loads and traversal is rejected`() {
        val s = store()
        val ok = s.view("sketch", "references/catalog.md").getOrThrow()
        assertTrue(ok.content.contains("Stripe"))
        assertTrue(s.view("sketch", "../secrets").isFailure)
        assertTrue(s.view("sketch", "SKILL.md").isFailure)
    }

    @Test
    fun `unknown skill lists available`() {
        val err = sError(store().view("nope"))
        assertTrue(err.contains("not found"))
        assertTrue(err.contains("git"))
    }

    @Test
    fun `patch copies bundled skill into user dir`() {
        val s = store()
        val result = s.patchUserOrCopy(
            "git",
            "Body of git.",
            "Body of git, plus never force-push.",
        )
        assertTrue(result.isSuccess)
        val viewed = s.view("git").getOrThrow()
        assertEquals(SkillSource.USER, viewed.source)
        assertTrue(viewed.content.contains("never force-push"))
    }

    @Test
    fun `delete user skill falls back to bundled`() {
        val s = store()
        s.saveUser(skill("git", "repo", "User override."))
        assertTrue(s.deleteUser("git"))
        val viewed = s.view("git").getOrThrow()
        assertEquals(SkillSource.BUNDLED, viewed.source)
    }

    private fun sError(result: Result<SkillView>): String =
        result.exceptionOrNull()?.message.orEmpty()
}
