package com.androidharness.app.tools

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Installed skill folders sit outside the workspace: commands may read and run them, and never change them. */
class ShellPolicySkillFoldersTest {
    // Not under /tmp: the sandbox already allows that, which would hide what these tests check.
    private val root = File("build/tmp/skill-folders-${System.nanoTime()}").absoluteFile.apply { mkdirs() }
    private val workspace = root.resolve("workspace").apply { mkdirs() }
    private val skill = root.resolve("plugins/pkg/skills/founder").apply { mkdirs(); resolve("panel.py").writeText("print(1)") }
    private val sibling = root.resolve("plugins/pkg/skills/founder-evil").apply { mkdirs() }

    @After fun cleanUp() { root.deleteRecursively() }

    private fun deny(command: String, withSkill: Boolean = true) =
        ShellPolicy.denyReason(command, workspace, workspace, if (withSkill) listOf(skill) else emptyList())

    private fun allowed(vararg commands: String) = commands.forEach { assertNull(it, deny(it)) }
    private fun blocked(vararg commands: String) = commands.forEach { assertNotNull("$it should be blocked", deny(it)) }

    @Test fun `without registered folders a skill path stays outside the sandbox`() {
        assertTrue(deny("python3 \"${skill.path}/panel.py\" status", withSkill = false)!!.contains("outside workspace"))
        assertTrue(deny("cat ${skill.path}/panel.py", withSkill = false)!!.contains("outside workspace"))
    }

    @Test fun `scripts in a skill folder can be run and read`() {
        allowed(
            "python3 \"${skill.path}/panel.py\" init --customer founder/customer.json",
            "python3 ${skill.path}/panel.py status | head -20",
            "bash ${skill.path}/run.sh",
            "node ${skill.path}/tool.js",
            "cat ${skill.path}/panel.py",
            "head -c 60 ${skill.path}/panel.py",
            "ls ${skill.path}",
            "grep -n def ${skill.path}/panel.py",
            "find ${skill.path} -name '*.json'",
            "echo \$(cat ${skill.path}/panel.py)",
            "${skill.path}/run.sh --quick",
        )
    }

    @Test fun `files can be copied out of a skill folder but not into one`() {
        allowed(
            "cp ${skill.path}/customer.example.json founder/customer.json",
            "cp -r ${skill.path}/templates ./templates",
            "cp ${skill.path}/a ${skill.path}/b ./",
        )
        blocked(
            "cp notes.md ${skill.path}/notes.md",
            "cp -t ${skill.path} notes.md",
            "cp --target-directory=${skill.path} notes.md",
            "cp -rt ${skill.path} notes.md",
        )
    }

    @Test fun `commands that change files cannot touch a skill folder`() {
        blocked(
            "rm ${skill.path}/panel.py",
            "rm -rf ${skill.path}",
            "mv ${skill.path}/panel.py ./panel.py",
            "touch ${skill.path}/new",
            "mkdir ${skill.path}/sub",
            "chmod +x ${skill.path}/panel.py",
            "sed -i s/1/2/ ${skill.path}/panel.py",
            "tee ${skill.path}/panel.py",
            "ln -s ${skill.path}/panel.py link",
            "find ${skill.path} -delete",
            "find ${skill.path} -exec rm {} ;",
            "tar -xf a.tar -C ${skill.path}",
            "git -C ${skill.path} checkout .",
        )
    }

    @Test fun `redirection and cd into a skill folder are refused`() {
        blocked(
            "echo hi > ${skill.path}/out.txt",
            "echo hi >> ${skill.path}/out.txt",
            "cat ${skill.path}/panel.py > ${skill.path}/copy.py",
            "cd ${skill.path} && python3 panel.py",
            "python3 ${skill.path}/panel.py > ${skill.path}/log",
        )
        allowed("python3 ${skill.path}/panel.py > founder/out.txt")
    }

    @Test fun `option values into a skill folder follow the same rule as other arguments`() {
        blocked(
            "mv notes.md --target-directory=${skill.path}",
            "tar -x -C${skill.path} -f a.tar",
            "tar -x --directory=${skill.path} -f a.tar",
            "install --target-directory=${skill.path} a",
        )
        allowed("python3 ${skill.path}/panel.py --customer=${skill.path}/customer.example.json")
    }

    @Test fun `inline code gets nothing extra from the skill folders`() {
        val inline = "python3 -c \"open('${skill.path}/panel.py','w').write('x')\""
        assertEquals(deny(inline, withSkill = false) == null, deny(inline) == null)
    }

    @Test fun `lookalike folders do not get the allowance`() {
        blocked(
            "cat ${sibling.path}/x",
            "cat ${skill.path}/../skills/founder-evil/x",
            "cat ${skill.path}-evil/x",
        )
    }

    @Test fun `a variable holding a skill path can be read but not used to change it`() {
        allowed(
            "PANEL=\"${skill.path}/panel.py\"; python3 \"\$PANEL\" status",
            "S=${skill.path} && python3 \"\$S/panel.py\" status",
        )
        blocked(
            "S=${skill.path}; rm -rf \"\$S\"",
            "S=${skill.path}; touch \$S/x",
        )
    }

    @Test fun `a link inside a skill folder cannot reach outside it`() {
        val secret = root.resolve("secret.txt").apply { writeText("x") }
        java.nio.file.Files.createSymbolicLink(skill.resolve("escape").toPath(), secret.toPath())
        blocked("cat ${skill.path}/escape")
    }

    @Test fun `everything outside the workspace and skill folders is still blocked`() {
        blocked("cat /etc/hosts", "ls /data/data", "python3 /data/local/tmp/x.py", "cat ../outside.txt")
        allowed("python3 main.py", "ls", "cat founder/customer.json")
    }
}
