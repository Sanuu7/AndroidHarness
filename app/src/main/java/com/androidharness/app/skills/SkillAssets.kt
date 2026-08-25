package com.androidharness.app.skills

import android.content.res.AssetManager

object SkillAssets {
    fun load(assets: AssetManager): Map<String, SkillStore.BundledSkill> {
        val names = assets.list("skills").orEmpty()
        val out = linkedMapOf<String, SkillStore.BundledSkill>()
        for (entry in names) {
            val skillMd = readAsset(assets, "skills/$entry/SKILL.md") ?: continue
            val parsed = runCatching { SkillParser.parse(skillMd) }.getOrNull() ?: continue
            val files = mutableMapOf<String, String>()
            for (folder in SkillStore.SUPPORT_DIRS) {
                collect(assets, "skills/$entry/$folder", folder, files)
            }
            out[parsed.name] = SkillStore.BundledSkill(entry, skillMd, files)
        }
        return out
    }

    private fun collect(
        assets: AssetManager,
        assetDir: String,
        rel: String,
        into: MutableMap<String, String>,
    ) {
        val children = runCatching { assets.list(assetDir) }.getOrNull() ?: return
        if (children.isEmpty()) {
            readAsset(assets, assetDir)?.let { into[rel] = it }
            return
        }
        for (child in children) {
            collect(assets, "$assetDir/$child", "$rel/$child", into)
        }
    }

    private fun readAsset(assets: AssetManager, path: String): String? =
        runCatching { assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()
}
