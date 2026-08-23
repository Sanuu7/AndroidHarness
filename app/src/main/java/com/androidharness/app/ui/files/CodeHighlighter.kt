package com.androidharness.app.ui.files

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** Lightweight regex tokenizer producing highlighted lines for the code viewer. */
object CodeHighlighter {

    data class Scheme(
        val kw: Color,
        val comment: Color,
        val string: Color,
        val annotation: Color,
        val number: Color,
    )

    private data class Lang(
        val keywords: Set<String>,
        val lineComment: String,
        val blockCommentStart: String? = null,
        val blockCommentEnd: String? = null,
    )

    private val Kotlin = Lang(
        keywords = setOf("package", "import", "class", "interface", "object", "enum", "fun",
            "val", "var", "data", "sealed", "abstract", "open", "override", "private",
            "protected", "internal", "public", "return", "if", "else", "when", "for", "while",
            "do", "in", "is", "as", "this", "super", "true", "false", "null", "throw", "try",
            "catch", "finally", "continue", "break", "companion", "constructor", "init",
            "const", "lateinit", "by", "suspend", "typealias", "annotation",
        ),
        lineComment = "//", blockCommentStart = "/*", blockCommentEnd = "*/",
    )

    private val Python = Lang(
        keywords = setOf("def", "class", "import", "from", "as", "return", "if", "elif",
            "else", "for", "while", "in", "is", "not", "and", "or", "True", "False", "None",
            "try", "except", "finally", "with", "yield", "raise", "pass", "break", "continue",
            "lambda", "self", "async", "await",
        ),
        lineComment = "#",
    )

    private val JsTs = Lang(
        keywords = setOf("import", "export", "default", "function", "const", "let", "var",
            "class", "return", "if", "else", "for", "while", "do", "switch", "case", "break",
            "continue", "new", "this", "super", "true", "false", "null", "undefined", "async",
            "await", "throw", "try", "catch", "finally", "typeof", "instanceof", "interface",
            "type", "extends", "implements", "from",
        ),
        lineComment = "//", blockCommentStart = "/*", blockCommentEnd = "*/",
    )

    private val Xml = Lang(
        keywords = emptySet(),
        lineComment = "<!--", blockCommentEnd = "-->",
    )

    private val Sh = Lang(
        keywords = setOf("if", "then", "else", "elif", "fi", "for", "while", "do", "done",
            "case", "esac", "in", "function", "return", "echo", "exit", "export", "local",
            "source",
        ),
        lineComment = "#",
    )

    private fun langFor(path: String): Lang = when {
        path.endsWith(".kt") || path.endsWith(".kts") -> Kotlin
        path.endsWith(".java") || path.endsWith(".scala") -> Kotlin
        path.endsWith(".py") -> Python
        path.endsWith(".js") || path.endsWith(".ts") || path.endsWith(".jsx") || path.endsWith(".tsx") || path.endsWith(".mjs") -> JsTs
        path.endsWith(".json") || path.endsWith(".xml") || path.endsWith(".html") || path.endsWith(".svg") -> Xml
        path.endsWith(".sh") || path.endsWith(".bash") || path.endsWith(".zsh") -> Sh
        path.endsWith(".yml") || path.endsWith(".yaml") -> Python // # comments + similar keywords
        path.endsWith(".gradle") || path.endsWith(".groovy") -> Kotlin // // comments
        path.endsWith(".toml") || path.endsWith(".ini") || path.endsWith(".cfg") -> Sh
        path.endsWith(".css") || path.endsWith(".scss") -> Kotlin
        path.endsWith(".md") || path.endsWith(".txt") -> Lang(emptySet(), "")
        else -> Lang(keywords = setOf("class", "def", "function", "return", "if", "import"), lineComment = "//")
    }

    /** Highlight all lines of [source] for the given file [path]. */
    fun highlightSync(path: String, source: String, scheme: Scheme): List<AnnotatedString> {
        val lang = langFor(path)

        return source.lines().map { line ->
            buildAnnotatedString {
                if (line.isBlank()) { append(line); return@buildAnnotatedString }

                val commentStart = if (lang.blockCommentStart != null && lang.blockCommentEnd != null) {
                    line.indexOf(lang.blockCommentStart)
                } else -1
                val commentEnd = if (commentStart >= 0) {
                    line.indexOf(lang.blockCommentEnd!!, commentStart + lang.blockCommentStart!!.length)
                } else -1

                if (commentStart >= 0 && commentEnd >= commentStart) {
                    append(line.substring(0, commentStart))
                    withStyle(SpanStyle(color = scheme.comment)) {
                        append(line.substring(commentStart, commentEnd + lang.blockCommentEnd!!.length))
                    }
                    if (commentEnd + lang.blockCommentEnd!!.length < line.length) {
                        append(line.substring(commentEnd + lang.blockCommentEnd!!.length))
                    }
                    return@buildAnnotatedString
                }

                val lcPos = if (lang.lineComment.isNotEmpty()) line.indexOf(lang.lineComment) else -1
                val codePart = if (lcPos >= 0) line.substring(0, lcPos) else line

                var i = 0
                while (i < codePart.length) {
                    val c = codePart[i]
                    when {
                        c == '"' || c == '\'' -> {
                            val end = findStringEnd(codePart, i)
                            withStyle(SpanStyle(color = scheme.string)) { append(codePart.substring(i, end)) }
                            i = end
                        }
                        c == '@' -> {
                            val end = codePart.indexOf(' ', i).let { if (it < 0) codePart.length else it }
                            withStyle(SpanStyle(color = scheme.annotation, fontFamily = FontFamily.Monospace)) {
                                append(codePart.substring(i, end))
                            }
                            i = end
                        }
                        c.isDigit() -> {
                            val start = i
                            while (i < codePart.length && (codePart[i].isDigit() || codePart[i] == '.' || codePart[i] == 'x' || codePart[i] == 'b')) i++
                            withStyle(SpanStyle(color = scheme.number)) { append(codePart.substring(start, i)) }
                        }
                        c.isLetterOrDigit() || c == '_' -> {
                            val start = i
                            while (i < codePart.length && (codePart[i].isLetterOrDigit() || codePart[i] == '_')) i++
                            val word = codePart.substring(start, i)
                            if (lang.keywords.contains(word)) {
                                withStyle(SpanStyle(color = scheme.kw, fontWeight = FontWeight.Bold)) { append(word) }
                            } else {
                                append(word)
                            }
                        }
                        else -> { append(c); i++ }
                    }
                }

                if (lcPos >= 0) {
                    withStyle(SpanStyle(color = scheme.comment, fontFamily = FontFamily.Monospace)) {
                        append(line.substring(lcPos))
                    }
                }
            }
        }
    }

    private fun findStringEnd(line: String, start: Int): Int {
        val quote = line[start]
        var i = start + 1
        while (i < line.length) {
            if (line[i] == '\\') i += 2
            else if (line[i] == quote) return i + 1
            else i++
        }
        return line.length
    }
}
