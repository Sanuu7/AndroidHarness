package com.androidharness.app.skills

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillVariablesTest {
    private fun expand(text: String, skill: String? = "/data/skills/founder", plugin: String? = "/data/plugins/founder", project: String? = "/work/app") =
        SkillVariables.expand(text, skill, plugin, project)

    @Test fun `braced and bare forms both expand`() {
        assertEquals("python3 \"/data/skills/founder/panel.py\" next", expand("python3 \"\${CLAUDE_SKILL_DIR}/panel.py\" next"))
        assertEquals("cd /data/skills/founder && ls", expand("cd \$CLAUDE_SKILL_DIR && ls"))
        assertEquals("/data/plugins/founder/bin", expand("\${CLAUDE_PLUGIN_ROOT}/bin"))
        assertEquals("/work/app/out", expand("\${CLAUDE_PROJECT_DIR}/out"))
    }

    @Test fun `every occurrence is expanded`() {
        assertEquals("/data/skills/founder and /data/skills/founder", expand("\${CLAUDE_SKILL_DIR} and \$CLAUDE_SKILL_DIR"))
    }

    @Test fun `longer names and other variables are left alone`() {
        val text = "\$CLAUDE_SKILL_DIRECTORY \${CLAUDE_SKILL_DIR_X} \$HOME \${HOME} \$CLAUDE_SKILL_DIR_"
        assertEquals(text, expand(text))
    }

    @Test fun `a variable with no known value stays as written`() {
        assertEquals("\${CLAUDE_PLUGIN_ROOT}/x \$CLAUDE_PROJECT_DIR", expand("\${CLAUDE_PLUGIN_ROOT}/x \$CLAUDE_PROJECT_DIR", plugin = null, project = null))
    }

    @Test fun `dollar signs and backslashes in a path are inserted literally`() {
        assertEquals("/odd\$1\\dir/run.sh", expand("\${CLAUDE_SKILL_DIR}/run.sh", skill = "/odd\$1\\dir"))
    }

    @Test fun `text without a dollar sign is returned unchanged`() {
        assertEquals("plain text", expand("plain text"))
    }
}
