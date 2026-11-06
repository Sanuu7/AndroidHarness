package com.androidharness.app.data.storage

/** The groups the Storage screen shows, in display order. */
enum class StorageCategory { APP_DATA, PLUGINS, SKILLS, WORKSPACES, LINUX_PACKAGES, LOCAL_MODELS, CACHES }

/**
 * What an item is. The screen turns a role into its title and explanation, so the data layer never builds
 * display text. Roles marked [named] show the item's own name (a plugin, package, model or workspace).
 */
enum class StorageRole(val category: StorageCategory, val named: Boolean = false) {
    APP_PACKAGE(StorageCategory.APP_DATA),
    CHAT_DATABASE(StorageCategory.APP_DATA),
    CHAT_IMAGES(StorageCategory.APP_DATA),
    UNDO_CHECKPOINTS(StorageCategory.APP_DATA),
    FILE_HISTORY(StorageCategory.APP_DATA),
    EDITOR_DRAFTS(StorageCategory.APP_DATA),
    CANVAS(StorageCategory.APP_DATA),
    SETTINGS(StorageCategory.APP_DATA),
    SCRATCH(StorageCategory.APP_DATA),
    ROUTER(StorageCategory.APP_DATA),
    TELEGRAM(StorageCategory.APP_DATA),
    WEB_DATA(StorageCategory.APP_DATA),
    BACKGROUND_WORK(StorageCategory.APP_DATA),
    OTHER_APP_DATA(StorageCategory.APP_DATA),

    PLUGIN(StorageCategory.PLUGINS, named = true),
    PLUGIN_LEFTOVERS(StorageCategory.PLUGINS),
    PLUGIN_REGISTRY(StorageCategory.PLUGINS),

    SKILL_USER(StorageCategory.SKILLS, named = true),
    SKILL_PROJECT(StorageCategory.SKILLS, named = true),
    SKILL_BUNDLED(StorageCategory.SKILLS, named = true),

    WORKSPACE(StorageCategory.WORKSPACES, named = true),
    OTHER_REPOSITORIES(StorageCategory.WORKSPACES),

    LINUX_PACKAGE(StorageCategory.LINUX_PACKAGES, named = true),
    LINUX_UNTRACKED_PACKAGE(StorageCategory.LINUX_PACKAGES, named = true),
    LINUX_SHARED_FILES(StorageCategory.LINUX_PACKAGES),
    NPM_GLOBAL(StorageCategory.LINUX_PACKAGES, named = true),
    PIP_PACKAGE(StorageCategory.LINUX_PACKAGES, named = true),
    CODEGRAPH_RUNTIME(StorageCategory.LINUX_PACKAGES),
    LINUX_HOME(StorageCategory.LINUX_PACKAGES),
    LINUX_TMP(StorageCategory.LINUX_PACKAGES),
    LINUX_OTHER(StorageCategory.LINUX_PACKAGES),
    PACKAGE_RECORDS(StorageCategory.LINUX_PACKAGES),
    LINUX_STAGING(StorageCategory.LINUX_PACKAGES),
    SHELL_TIER_COPY(StorageCategory.LINUX_PACKAGES),

    MODEL(StorageCategory.LOCAL_MODELS, named = true),
    MODEL_PARTIAL(StorageCategory.LOCAL_MODELS, named = true),
    MODEL_OTHER(StorageCategory.LOCAL_MODELS),
    GPU_RUNTIME(StorageCategory.LOCAL_MODELS),

    CACHE_NPM(StorageCategory.CACHES),
    CACHE_PIP(StorageCategory.CACHES),
    CACHE_PACKAGE_OTHER(StorageCategory.CACHES),
    CACHE_GRADLE(StorageCategory.CACHES),
    CACHE_GRADLE_WRAPPER(StorageCategory.CACHES),
    CACHE_APT(StorageCategory.CACHES),
    CACHE_HOME_OTHER(StorageCategory.CACHES),
    CACHE_DEB(StorageCategory.CACHES),
    CACHE_PLUGIN_PREVIEWS(StorageCategory.CACHES),
    CACHE_SHARE(StorageCategory.CACHES),
    CACHE_REMOTE_IMAGES(StorageCategory.CACHES),
    CACHE_AUDIO(StorageCategory.CACHES),
    CACHE_MCP_LOGS(StorageCategory.CACHES),
    CACHE_MODEL_CATALOG(StorageCategory.CACHES),
    CACHE_APP_OTHER(StorageCategory.CACHES),
}

/** Why an item has no size of its own. Only the [included] reasons are counted elsewhere in the total. */
enum class StorageGap(val included: Boolean = false) {
    PERMISSION_DENIED,
    SAF_FOLDER,
    SSH_HOST,
    MISSING,
    TIMED_OUT,
    SCAN_ERROR,
    REGISTRY_UNREADABLE,
    /** A second workspace that points at a folder another workspace already measured. */
    SAME_FOLDER(included = true),
    /** Ships inside the app package, which is measured as its own item. */
    INSIDE_APP_PACKAGE(included = true),
    /** Installed before file tracking; its files are part of "Other Linux files". */
    UNTRACKED(included = true),
}

data class StorageItem(
    val role: StorageRole,
    /** Stable owner key: a plugin id, package name, project id, model id or a path. */
    val key: String,
    /** The dynamic name for [StorageRole.named] roles; empty otherwise. */
    val name: String = "",
    val path: String? = null,
    /** Other locations measured as part of this item, after [path]. */
    val morePaths: List<String> = emptyList(),
    val bytes: Long? = null,
    val files: Int = 0,
    val gap: StorageGap? = null,
    /** Some files inside could not be read, so [bytes] is a lower bound. */
    val partial: Boolean = false,
    /** Used by more than one owner (all workspaces, several packages). */
    val shared: Boolean = false,
    /** Workspace or scope label; null means global. */
    val scope: String? = null,
    val version: String? = null,
    val enabled: Boolean? = null,
    /** Packages, plugins or workspaces that use or keep this item. */
    val owners: List<String> = emptyList(),
    /** The [id] of the item whose size already includes this one. */
    val countedIn: String? = null,
    /** Bytes seen before a timeout; never part of any total. */
    val seenBytes: Long = 0,
) {
    val id get() = "${role.name}:$key"
    val category get() = role.category
    val measured get() = bytes != null
    /** Shown as "Not measured" and left out of the total. */
    val notMeasured get() = bytes == null && gap?.included != true
}

data class StorageGroup(val category: StorageCategory, val items: List<StorageItem>) {
    val bytes get() = items.sumOf { it.bytes ?: 0 }
    val notMeasured get() = items.count { it.notMeasured }
}

data class StorageProgress(val done: Int, val total: Int, val category: StorageCategory?, val current: String = "") {
    val fraction get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

data class StorageReport(val items: List<StorageItem>, val finishedAt: Long = System.currentTimeMillis(), val timedOut: Boolean = false) {
    /** Measured bytes only. Items that could not be measured are listed and excluded. */
    val total get() = items.sumOf { it.bytes ?: 0 }
    val notMeasured get() = items.filter { it.notMeasured }
    val partial get() = items.count { it.partial }
    fun item(id: String) = items.firstOrNull { it.id == id }

    /** Every category that has an item, in display order. Each group lists its largest items first. */
    fun groups(items: List<StorageItem> = this.items): List<StorageGroup> = StorageCategory.entries.mapNotNull { category ->
        items.filter { it.category == category }.takeIf { it.isNotEmpty() }?.let { StorageGroup(category, sortItems(it)) }
    }

    fun largest(limit: Int = 25, items: List<StorageItem> = this.items) =
        items.filter { (it.bytes ?: 0) > 0 }.sortedByDescending { it.bytes }.take(limit)

    companion object {
        /** Biggest first; unmeasured items next so they stay in view; folded-in items last. */
        fun sortItems(items: List<StorageItem>) = items.sortedWith(
            compareBy<StorageItem> { when { it.measured -> 0; it.notMeasured -> 1; else -> 2 } }
                .thenByDescending { it.bytes ?: 0 }.thenBy { it.name.lowercase() }.thenBy { it.path.orEmpty() })

        /** Matches the shown title, the name, the path or the scope, ignoring case. */
        fun matches(item: StorageItem, title: String, query: String): Boolean {
            val q = query.trim()
            if (q.isEmpty()) return true
            return (listOfNotNull(title, item.name, item.path, item.scope, item.version) + item.morePaths).any { it.contains(q, ignoreCase = true) } ||
                item.owners.any { it.contains(q, ignoreCase = true) }
        }
    }
}
