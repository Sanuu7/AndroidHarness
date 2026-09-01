package com.androidharness.app.repomap

import java.util.regex.Pattern

data class SymbolInfo(
    val name: String,
    val kind: String, // "class", "fun", "val", "type", "def", etc.
    val signature: String,
    val line: Int,
)

object RepoSymbolExtractor {

    private val KT_JAVA_DECL = Pattern.compile(
        """^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|private|protected|internal|abstract|final|open|sealed|data|enum|override|suspend|inline|value|const|static)\s+)*((?:data\s+)?class|(?:sealed\s+)?class|(?:enum\s+)?class|interface|object|fun|val|var|typealias)\s+([A-Za-z0-9_]+)(?:<[^>]*>)?(?:\s*\([^)]*\))?(?:\s*:\s*[^{=]+)?"""
    )

    private val PY_DECL = Pattern.compile(
        """^\s*(class|def|async\s+def)\s+([A-Za-z0-9_]+)(?:\((.*?)\))?\s*:"""
    )

    private val JS_TS_DECL = Pattern.compile(
        """^\s*(?:export\s+(?:default\s+)?)?(?:declare\s+)?(?:async\s+)?(class|interface|type|enum|function|const|let|var)\s+([A-Za-z0-9_]+)(?:<[^>]*>)?(?:\s*\([^)]*\))?(?:\s*:\s*[^{=;]+)?"""
    )

    private val SH_FUNC = Pattern.compile(
        """^\s*(?:function\s+)?([A-Za-z0-9_]+)\s*\(\)\s*\{"""
    )

    fun extract(relPath: String, content: String, maxSymbols: Int = 40): List<SymbolInfo> {
        val ext = relPath.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts", "java", "scala", "groovy" -> extractKotlinJava(content, maxSymbols)
            "py", "pyw" -> extractPython(content, maxSymbols)
            "js", "ts", "jsx", "tsx", "mjs", "cjs" -> extractJsTs(content, maxSymbols)
            "sh", "bash", "zsh" -> extractShell(content, maxSymbols)
            else -> emptyList()
        }
    }

    private fun extractKotlinJava(content: String, maxSymbols: Int): List<SymbolInfo> {
        val result = mutableListOf<SymbolInfo>()
        var lineNum = 0
        for (line in content.lineSequence()) {
            lineNum++
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) continue
            if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) continue

            val m = KT_JAVA_DECL.matcher(trimmed)
            if (m.find()) {
                val kind = m.group(1) ?: "decl"
                val name = m.group(2) ?: ""
                var sig = trimmed.substringBefore('{').substringBefore('=').trim()
                if (sig.length > 90) sig = sig.take(87) + "..."
                result += SymbolInfo(name = name, kind = kind, signature = sig, line = lineNum)
                if (result.size >= maxSymbols) break
            }
        }
        return result
    }

    private fun extractPython(content: String, maxSymbols: Int): List<SymbolInfo> {
        val result = mutableListOf<SymbolInfo>()
        var lineNum = 0
        for (line in content.lineSequence()) {
            lineNum++
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val m = PY_DECL.matcher(trimmed)
            if (m.find()) {
                val kind = m.group(1) ?: "def"
                val name = m.group(2) ?: ""
                var sig = trimmed.removeSuffix(":").trim()
                if (sig.length > 90) sig = sig.take(87) + "..."
                result += SymbolInfo(name = name, kind = kind, signature = sig, line = lineNum)
                if (result.size >= maxSymbols) break
            }
        }
        return result
    }

    private fun extractJsTs(content: String, maxSymbols: Int): List<SymbolInfo> {
        val result = mutableListOf<SymbolInfo>()
        var lineNum = 0
        for (line in content.lineSequence()) {
            lineNum++
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) continue
            if (trimmed.startsWith("import ")) continue

            val m = JS_TS_DECL.matcher(trimmed)
            if (m.find()) {
                val kind = m.group(1) ?: "decl"
                val name = m.group(2) ?: ""
                var sig = trimmed.substringBefore('{').substringBefore('=').removeSuffix(";").trim()
                if (sig.length > 90) sig = sig.take(87) + "..."
                result += SymbolInfo(name = name, kind = kind, signature = sig, line = lineNum)
                if (result.size >= maxSymbols) break
            }
        }
        return result
    }

    private fun extractShell(content: String, maxSymbols: Int): List<SymbolInfo> {
        val result = mutableListOf<SymbolInfo>()
        var lineNum = 0
        for (line in content.lineSequence()) {
            lineNum++
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val m = SH_FUNC.matcher(trimmed)
            if (m.find()) {
                val name = m.group(1) ?: ""
                result += SymbolInfo(name = name, kind = "function", signature = "$name()", line = lineNum)
                if (result.size >= maxSymbols) break
            }
        }
        return result
    }
}
