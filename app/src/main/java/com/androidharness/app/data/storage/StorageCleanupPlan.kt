package com.androidharness.app.data.storage

import java.io.File
import java.nio.file.Path

/**
 * What a cleanup choice does when it runs. [confirm] kinds lose something the app cannot download again,
 * so the screen asks once more and says what goes.
 */
enum class CleanupKind(val confirm: Boolean = false) {
    WEB_DATA,
    CHAT_IMAGES(confirm = true),
    DOWNLOADS,
    SCRATCH,
    CACHE,
    CANVAS,
    MODEL(confirm = true),
    CHAT_HISTORY(confirm = true),
    SETTINGS(confirm = true),
    PLUGIN,
    SKILL(confirm = true),
    LINUX_PACKAGE(confirm = true),
}

/** Why an item is never offered for cleanup. */
enum class KeepReason {
    /** The 9router install and its data. */
    ROUTER,
    /** The Linux environment the shell and agent tools run on. */
    LINUX_CORE,
    /** Provider keys, sign-ins and secrets. Settings cleanup leaves them in place. */
    SECRETS,
    /** The installed app and what ships inside it. */
    APP_PACKAGE,
    /** Workspace folders and other project files. */
    PROJECT_FILES,
    /** Undo checkpoints, file history and unsaved editor drafts. */
    UNDO_HISTORY,
    /** Files the app needs while it runs (scheduled work, plugin registry, GPU runtime). */
    APP_RUNTIME,
    /** A Linux package another package or the app needs; see [KeptEntry.protection]. */
    PACKAGE,
    /** Installed with npm or pip, which keep their own records. */
    PACKAGE_MANAGER,
}

data class CleanupEntry(val item: StorageItem, val kind: CleanupKind) {
    val id get() = item.id
    val bytes get() = item.bytes ?: 0L
}

/** A kept item. [item] is null for things the scan has no size for, such as secrets. */
data class KeptEntry(val reason: KeepReason, val item: StorageItem? = null, val protection: PackageProtection? = null) {
    val bytes get() = item?.bytes ?: 0L
}

data class CleanupPlan(val entries: List<CleanupEntry>, val kept: List<KeptEntry>) {
    /** Only ids of offered entries count; anything else, including a kept item's id, is ignored. */
    fun chosen(ids: Set<String>): List<CleanupEntry> = entries.filter { it.id in ids }
    fun bytes(ids: Set<String>): Long = chosen(ids).sumOf { it.bytes }
    /** The chosen entries that need a second, explicit confirmation. */
    fun needsConfirmation(ids: Set<String>): List<CleanupEntry> = chosen(ids).filter { it.kind.confirm }
    /** Drops ids that are no longer offered after a rescan. */
    fun keepValid(ids: Set<String>): Set<String> = ids.intersect(entries.map { it.id }.toSet())
    /** Entries grouped by storage category in display order, biggest first inside each group. */
    fun groups(): List<Pair<StorageCategory, List<CleanupEntry>>> = StorageCategory.entries.mapNotNull { category ->
        entries.filter { it.item.category == category }.takeIf { it.isNotEmpty() }?.let { category to it.sortedByDescending(CleanupEntry::bytes) }
    }
    /** Kept items by reason, in [KeepReason] order. */
    fun keptGroups(): List<Pair<KeepReason, List<KeptEntry>>> = KeepReason.entries.mapNotNull { reason ->
        kept.filter { it.reason == reason }.takeIf { it.isNotEmpty() }?.let { reason to it }
    }
    /** Caches and old downloads are the only choices made for the user; everything else starts unticked. */
    fun defaultSelection(): Set<String> = entries.filter { it.kind == CleanupKind.CACHE || it.kind == CleanupKind.DOWNLOADS }.map { it.id }.toSet()
}

internal object CleanupPlanner {
    /** What cleaning an item of this role does, or null when the role is never offered. */
    fun kind(role: StorageRole): CleanupKind? = when (role) {
        StorageRole.WEB_DATA -> CleanupKind.WEB_DATA
        StorageRole.CHAT_IMAGES -> CleanupKind.CHAT_IMAGES
        StorageRole.CACHE_DEB -> CleanupKind.DOWNLOADS
        StorageRole.SCRATCH, StorageRole.LINUX_TMP -> CleanupKind.SCRATCH
        StorageRole.CACHE_NPM, StorageRole.CACHE_PIP, StorageRole.CACHE_PACKAGE_OTHER, StorageRole.CACHE_GRADLE,
        StorageRole.CACHE_GRADLE_WRAPPER, StorageRole.CACHE_APT, StorageRole.CACHE_HOME_OTHER, StorageRole.CACHE_PLUGIN_PREVIEWS,
        StorageRole.CACHE_SHARE, StorageRole.CACHE_REMOTE_IMAGES, StorageRole.CACHE_AUDIO, StorageRole.CACHE_MCP_LOGS,
        StorageRole.CACHE_MODEL_CATALOG, StorageRole.CACHE_APP_OTHER -> CleanupKind.CACHE
        StorageRole.CANVAS -> CleanupKind.CANVAS
        StorageRole.MODEL, StorageRole.MODEL_PARTIAL, StorageRole.MODEL_OTHER -> CleanupKind.MODEL
        StorageRole.CHAT_DATABASE -> CleanupKind.CHAT_HISTORY
        StorageRole.SETTINGS -> CleanupKind.SETTINGS
        StorageRole.PLUGIN, StorageRole.PLUGIN_LEFTOVERS -> CleanupKind.PLUGIN
        StorageRole.SKILL_USER -> CleanupKind.SKILL
        StorageRole.LINUX_PACKAGE -> CleanupKind.LINUX_PACKAGE
        else -> null
    }

    /** Why an item of this role is always kept, or null when it can be offered. */
    fun keep(role: StorageRole): KeepReason? = when (role) {
        StorageRole.ROUTER -> KeepReason.ROUTER
        StorageRole.APP_PACKAGE, StorageRole.SKILL_BUNDLED -> KeepReason.APP_PACKAGE
        StorageRole.WORKSPACE, StorageRole.OTHER_REPOSITORIES, StorageRole.SKILL_PROJECT -> KeepReason.PROJECT_FILES
        StorageRole.UNDO_CHECKPOINTS, StorageRole.FILE_HISTORY, StorageRole.EDITOR_DRAFTS -> KeepReason.UNDO_HISTORY
        StorageRole.LINUX_SHARED_FILES, StorageRole.CODEGRAPH_RUNTIME, StorageRole.LINUX_HOME, StorageRole.LINUX_OTHER,
        StorageRole.PACKAGE_RECORDS, StorageRole.LINUX_STAGING, StorageRole.SHELL_TIER_COPY -> KeepReason.LINUX_CORE
        StorageRole.NPM_GLOBAL, StorageRole.PIP_PACKAGE -> KeepReason.PACKAGE_MANAGER
        StorageRole.TELEGRAM, StorageRole.BACKGROUND_WORK, StorageRole.GPU_RUNTIME, StorageRole.PLUGIN_REGISTRY,
        StorageRole.OTHER_APP_DATA -> KeepReason.APP_RUNTIME
        StorageRole.LINUX_UNTRACKED_PACKAGE -> KeepReason.PACKAGE
        else -> null
    }

    /** Kinds that always show a row, even when the scan found no bytes, because removing them is the point. */
    private val alwaysListed = setOf(CleanupKind.PLUGIN, CleanupKind.SKILL, CleanupKind.LINUX_PACKAGE, CleanupKind.MODEL)

    /**
     * Splits the scanned items into what the user may remove and what stays. [packages] maps every installed
     * Linux package to the reason it stays, or null when it can be removed. A package missing from the map is
     * treated as kept, so an unknown state never offers a removal.
     */
    fun plan(report: StorageReport, packages: Map<String, PackageProtection?>): CleanupPlan {
        val entries = mutableListOf<CleanupEntry>()
        val kept = mutableListOf<KeptEntry>()
        report.items.forEach { item ->
            val reason = keep(item.role)
            if (reason != null) {
                val protection = if (item.role == StorageRole.LINUX_UNTRACKED_PACKAGE)
                    packages[item.key] ?: PackageProtection(PackageBlock.UNTRACKED) else null
                kept += KeptEntry(reason, item, protection)
                return@forEach
            }
            val kind = kind(item.role) ?: return@forEach
            if (kind == CleanupKind.LINUX_PACKAGE) {
                val protection = if (item.key in packages) packages[item.key] else PackageProtection(PackageBlock.UNTRACKED)
                if (protection != null) { kept += KeptEntry(KeepReason.PACKAGE, item, protection); return@forEach }
            }
            val bytes = item.bytes ?: return@forEach // Unmeasured items are never offered.
            if (bytes > 0 || kind in alwaysListed) entries += CleanupEntry(item, kind)
        }
        kept += KeptEntry(KeepReason.SECRETS)
        return CleanupPlan(entries, kept)
    }
}

/**
 * The last line of defense: cleanup never deletes inside a protected folder unless the target sits in one of the
 * listed exceptions (Linux caches and temp files live inside the protected Linux prefix), and never deletes a
 * folder that contains a protected one.
 */
internal class StorageGuard(protectedRoots: Collection<File>, exceptions: Collection<File> = emptyList()) {
    private val protected = protectedRoots.map(::path)
    private val exceptions = exceptions.map(::path)

    fun allows(target: File): Boolean {
        val t = path(target)
        if (exceptions.any { t == it || t.startsWith(it) }) return protected.none { it.startsWith(t) && it != t && !exceptions.any { e -> it.startsWith(e) } }
        return protected.none { t == it || t.startsWith(it) || it.startsWith(t) }
    }

    private companion object {
        fun path(file: File): Path = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile).toPath().normalize()
    }
}
