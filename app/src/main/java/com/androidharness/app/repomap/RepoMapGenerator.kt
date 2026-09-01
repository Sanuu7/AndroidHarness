package com.androidharness.app.repomap

data class FileEntry(
    val relPath: String,
    val symbols: List<SymbolInfo>,
)

object RepoMapGenerator {

    /**
     * Formats file entries into a compact indented repository outline.
     * Enforces [maxChars] budget progressively:
     * - Tier 1: Full signatures (all extracted symbols).
     * - Tier 2: Major types / classes / top-level functions only.
     * - Tier 3: Directory and file paths only.
     */
    fun generate(entries: List<FileEntry>, maxChars: Int = 10_000): String {
        if (entries.isEmpty()) return ""

        val sorted = entries.sortedBy { it.relPath }

        // Try Tier 1: Full signatures
        val tier1 = buildOutline(sorted, includeAllSymbols = true)
        if (tier1.length <= maxChars) return tier1

        // Try Tier 2: Classes + top-level items only
        val tier2 = buildOutline(sorted, includeAllSymbols = false)
        if (tier2.length <= maxChars) return tier2

        // Try Tier 3: Compact path listing
        val tier3 = buildPathListing(sorted)
        if (tier3.length <= maxChars) return tier3

        // Hard truncation if extreme number of files
        return tier3.take(maxChars - 20) + "\n... (more files)"
    }

    private fun buildOutline(entries: List<FileEntry>, includeAllSymbols: Boolean): String {
        val sb = StringBuilder()
        var lastDir = ""

        for (entry in entries) {
            val dir = entry.relPath.substringBeforeLast('/', "")
            val fileName = entry.relPath.substringAfterLast('/')

            if (dir != lastDir) {
                if (sb.isNotEmpty()) sb.append('\n')
                if (dir.isNotEmpty()) {
                    sb.append(dir).append("/\n")
                }
                lastDir = dir
            }

            val prefix = if (dir.isNotEmpty()) "  " else ""
            sb.append(prefix).append(fileName).append('\n')

            val symbols = if (includeAllSymbols) {
                entry.symbols
            } else {
                entry.symbols.filter {
                    it.kind.contains("class") || it.kind.contains("interface") ||
                        it.kind == "def" || it.kind == "function" || it.kind == "type"
                }.take(10)
            }

            for (sym in symbols) {
                val symPrefix = if (dir.isNotEmpty()) "    " else "  "
                sb.append(symPrefix).append("│ ").append(sym.signature).append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    private fun buildPathListing(entries: List<FileEntry>): String {
        val sb = StringBuilder()
        var lastDir = ""

        for (entry in entries) {
            val dir = entry.relPath.substringBeforeLast('/', "")
            val fileName = entry.relPath.substringAfterLast('/')

            if (dir != lastDir) {
                if (dir.isNotEmpty()) {
                    sb.append(dir).append("/\n")
                }
                lastDir = dir
            }
            val prefix = if (dir.isNotEmpty()) "  " else ""
            sb.append(prefix).append(fileName).append('\n')
        }
        return sb.toString().trimEnd()
    }
}
