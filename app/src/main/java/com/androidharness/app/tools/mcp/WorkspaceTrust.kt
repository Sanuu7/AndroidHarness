package com.androidharness.app.tools.mcp

import com.androidharness.app.config.HarnessConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a workspace decides whether its own servers and plugins may run. */
@Serializable
enum class WorkspaceTrustMode {
    /** Ask before anything new runs, and again when an approved server or plugin changes. */
    ASK_CHANGES,
    /** Ask only for servers and plugins never approved before; edits to approved ones run. */
    ASK_NEW,
    /** Run every server and plugin this workspace defines, now and later, without asking. */
    TRUST_ALL,
    /** Never run this workspace's servers or plugins, and never ask. */
    BLOCK_ALL,
}

enum class TrustItemKind { SERVER, PLUGIN, LANGUAGE_SERVER, PLUGIN_BIN }

/** One thing in a workspace that can run code: approved on its own, by [key], for its exact [hash]. */
data class TrustItem(val key: String, val kind: TrustItemKind, val name: String, val hash: String, val detail: String = "")

/**
 * What the user decided for one workspace. [items] maps an approved item key to
 * the hash it was approved at; [declined] does the same for items the user chose
 * not to run, so they stop asking until that item changes.
 */
@Serializable
data class WorkspaceTrustRecord(
    val mode: WorkspaceTrustMode = WorkspaceTrustMode.ASK_CHANGES,
    val items: Map<String, String> = emptyMap(),
    val declined: Map<String, String> = emptyMap(),
    val updatedAt: Long = 0,
)

enum class TrustItemState { APPROVED, NEW, CHANGED, DECLINED, BLOCKED }

/** A pending item together with why it is asking: new or changed. */
data class TrustPrompt(val item: TrustItem, val state: TrustItemState)

object WorkspaceTrust {
    // Defaults stay out of the hash, so a new optional server field never resets approvals.
    private val json = Json

    fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * Everything in this workspace that can run code, each with a hash of exactly
     * what it runs. Instructions, docs and comments are left out on purpose, so
     * editing them never asks for approval again. A plugin's language servers and
     * bin folders count as part of that plugin.
     */
    fun items(servers: List<McpServerConfig>, config: HarnessConfig): List<TrustItem> {
        val out = mutableListOf<TrustItem>()
        servers.filter { it.enabled }.distinctBy { it.name.lowercase() }.forEach { server ->
            val plugin = config.serverSources[server.name]?.plugin
            out += TrustItem("server:${server.name}", TrustItemKind.SERVER, server.name,
                sha256(json.encodeToString(McpServerConfig.serializer(), server)),
                listOfNotNull(plugin?.let { "Plugin $it" },
                    if (server.isRemote) config.redact(server.url.orEmpty()) else config.redact((listOf(server.command) + server.args).joinToString(" ")),
                ).joinToString(" · "))
        }
        val owned = config.plugins.map { it.name }.toSet()
        config.plugins.forEach { plugin ->
            val lsp = config.languageServers.filter { it.plugin == plugin.name }
            val bins = config.pluginBins.filter { plugin.root.isNotEmpty() && it.startsWith(plugin.root) }
            val body = buildString {
                append(plugin.name).append('\n').append(plugin.root).append('\n')
                append(plugin.hooks.sorted().joinToString(",")).append('\n').append(plugin.script).append('\n')
                plugin.commands.forEach { append("${it.event}\u0000${it.type}\u0000${it.matcher}\u0000${it.timeout}\u0000${it.command}\n") }
                lsp.sortedBy { it.name }.forEach { append("lsp ${it.name}\u0000${it.command}\u0000${it.args}\u0000${it.env}\n") }
                bins.sorted().forEach { append("bin $it\n") }
            }
            out += TrustItem("plugin:${plugin.name}", TrustItemKind.PLUGIN, plugin.name, sha256(body),
                listOf(plugin.hooks.sorted().joinToString(", "), lsp.joinToString(", ") { it.name.substringAfter(':') })
                    .filter(String::isNotBlank).joinToString(" · "))
        }
        config.languageServers.filter { it.plugin !in owned }.forEach { lsp ->
            out += TrustItem("lsp:${lsp.name}", TrustItemKind.LANGUAGE_SERVER, lsp.name,
                sha256("${lsp.command}\u0000${lsp.args}\u0000${lsp.env}"), (listOf(lsp.command) + lsp.args).joinToString(" "))
        }
        config.pluginBins.filter { bin -> config.plugins.none { it.root.isNotEmpty() && bin.startsWith(it.root) } }.forEach { bin ->
            out += TrustItem("bin:$bin", TrustItemKind.PLUGIN_BIN, bin.substringAfterLast('/'), sha256(bin), bin)
        }
        return out
    }

    fun state(item: TrustItem, record: WorkspaceTrustRecord): TrustItemState = when (record.mode) {
        WorkspaceTrustMode.TRUST_ALL -> TrustItemState.APPROVED
        WorkspaceTrustMode.BLOCK_ALL -> TrustItemState.BLOCKED
        WorkspaceTrustMode.ASK_NEW -> when {
            item.key in record.items -> TrustItemState.APPROVED
            item.key in record.declined -> TrustItemState.DECLINED
            else -> TrustItemState.NEW
        }
        WorkspaceTrustMode.ASK_CHANGES -> when {
            record.items[item.key] == item.hash -> TrustItemState.APPROVED
            record.declined[item.key] == item.hash -> TrustItemState.DECLINED
            item.key in record.items || item.key in record.declined -> TrustItemState.CHANGED
            else -> TrustItemState.NEW
        }
    }

    fun approved(item: TrustItem, record: WorkspaceTrustRecord) = state(item, record) == TrustItemState.APPROVED

    /** Items waiting for an answer. Blocking everything never asks. */
    fun pending(items: List<TrustItem>, record: WorkspaceTrustRecord): List<TrustItem> =
        if (record.mode == WorkspaceTrustMode.BLOCK_ALL) emptyList()
        else items.filter { state(it, record) == TrustItemState.NEW || state(it, record) == TrustItemState.CHANGED }

    /** Changes to this fingerprint mean the list a dialog showed is out of date. */
    fun fingerprint(items: List<TrustItem>): String =
        sha256(items.sortedBy { it.key }.joinToString("\n") { "${it.key}\u0000${it.hash}" })

    fun approve(record: WorkspaceTrustRecord, items: List<TrustItem>, now: Long): WorkspaceTrustRecord {
        val approved = items.associate { it.key to it.hash }
        return record.copy(items = record.items + approved, declined = record.declined - approved.keys, updatedAt = now)
    }

    /** Remembers these items as not allowed, so they stop asking until they change. */
    fun decline(record: WorkspaceTrustRecord, items: List<TrustItem>, now: Long): WorkspaceTrustRecord {
        val declined = items.associate { it.key to it.hash }
        return record.copy(items = record.items - declined.keys, declined = record.declined + declined, updatedAt = now)
    }

    /** Forgets every answer about these items, so they ask again. */
    fun forget(record: WorkspaceTrustRecord, keys: Set<String>, now: Long): WorkspaceTrustRecord =
        record.copy(items = record.items - keys, declined = record.declined - keys, updatedAt = now)

    /**
     * The configuration a run may use: only approved servers, plugins, language
     * servers and bin folders. Instructions, skills and permissions stay as they are.
     */
    fun restrict(config: HarnessConfig, approvedKeys: Set<String>): HarnessConfig {
        val plugins = config.plugins.filter { "plugin:${it.name}" in approvedKeys }
        val pluginNames = plugins.map { it.name }.toSet()
        val allPluginNames = config.plugins.map { it.name }.toSet()
        return config.copy(
            servers = config.servers.filter { "server:${it.name}" in approvedKeys },
            plugins = plugins,
            languageServers = config.languageServers.filter {
                if (it.plugin in allPluginNames) it.plugin in pluginNames else "lsp:${it.name}" in approvedKeys
            },
            pluginBins = config.pluginBins.filter { bin ->
                val owner = config.plugins.firstOrNull { it.root.isNotEmpty() && bin.startsWith(it.root) }
                if (owner != null) owner.name in pluginNames else "bin:$bin" in approvedKeys
            },
        )
    }
}
