package com.androidharness.app.ui.files

/**
 * Shared lightweight tokenizer for both the code viewer highlighter and the
 * sora-editor language adapter. Produces per-line token spans so consumers
 * only map [TokenType] to their own color/style system.
 */
enum class TokenType {
    KEYWORD,
    KEYWORD_CONTROL,
    TYPE_NAME,
    FUNCTION_NAME,
    STRING,
    NUMBER,
    COMMENT,
    ANNOTATION,
    OPERATOR,
    PLAIN,
}

data class Token(
    /** Column where the token starts (inclusive). */
    val start: Int,
    /** Column just past the token's last character. */
    val end: Int,
    val type: TokenType,
)

object CodeTokenizer {

    data class Lang(
        val keywords: Set<String>,
        val controlKeywords: Set<String> = emptySet(),
        val types: Set<String> = emptySet(),
        val lineComment: String,
        val blockCommentStart: String? = null,
        val blockCommentEnd: String? = null,
    )

    private val Kotlin = Lang(
        controlKeywords = setOf("package", "import", "return", "if", "else", "when", "for", "while", "do", "throw", "try", "catch", "finally", "continue", "break"),
        keywords = setOf(
            "class", "interface", "object", "enum", "fun", "val", "var", "data", "sealed", "abstract",
            "open", "override", "private", "protected", "internal", "public", "in", "is", "as", "this",
            "super", "true", "false", "null", "companion", "constructor", "init", "const", "lateinit",
            "by", "suspend", "typealias", "annotation", "inline", "value", "operator", "infix",
        ),
        types = setOf("String", "Int", "Long", "Float", "Double", "Boolean", "Char", "Byte", "Short", "Unit", "Any", "List", "Map", "Set", "Array"),
        lineComment = "//", blockCommentStart = "/*", blockCommentEnd = "*/",
    )

    private val Python = Lang(
        controlKeywords = setOf("import", "from", "as", "return", "if", "elif", "else", "for", "while", "try", "except", "finally", "with", "yield", "raise", "pass", "break", "continue"),
        keywords = setOf(
            "def", "class", "in", "is", "not", "and", "or", "True", "False", "None",
            "lambda", "self", "async", "await", "global", "nonlocal",
        ),
        types = setOf("str", "int", "float", "bool", "list", "dict", "set", "tuple", "bytes", "object"),
        lineComment = "#",
    )

    private val JsTs = Lang(
        controlKeywords = setOf("import", "export", "from", "default", "return", "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "throw", "try", "catch", "finally"),
        keywords = setOf(
            "function", "const", "let", "var", "class", "new", "this", "super", "true", "false",
            "null", "undefined", "async", "await", "typeof", "instanceof", "interface", "type",
            "extends", "implements", "public", "private", "protected", "readonly", "static", "as",
        ),
        types = setOf("string", "number", "boolean", "any", "void", "never", "unknown", "Promise", "Array", "Record", "Object"),
        lineComment = "//", blockCommentStart = "/*", blockCommentEnd = "*/",
    )

    private val Xml = Lang(keywords = emptySet(), lineComment = "<!--", blockCommentEnd = "-->")

    private val Sh = Lang(
        controlKeywords = setOf("if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "return", "exit", "source"),
        keywords = setOf(
            "in", "function", "echo", "export", "local", "alias", "unset", "set", "shift",
        ),
        lineComment = "#",
    )

    fun langFor(path: String): Lang = when {
        path.endsWith(".kt") || path.endsWith(".kts") -> Kotlin
        path.endsWith(".java") || path.endsWith(".scala") -> Kotlin
        path.endsWith(".py") -> Python
        path.endsWith(".js") || path.endsWith(".ts") || path.endsWith(".jsx") ||
            path.endsWith(".tsx") || path.endsWith(".mjs") -> JsTs
        path.endsWith(".json") || path.endsWith(".xml") || path.endsWith(".html") ||
            path.endsWith(".svg") -> Xml
        path.endsWith(".sh") || path.endsWith(".bash") || path.endsWith(".zsh") -> Sh
        path.endsWith(".yml") || path.endsWith(".yaml") -> Python
        path.endsWith(".gradle") || path.endsWith(".groovy") -> Kotlin
        path.endsWith(".toml") || path.endsWith(".ini") || path.endsWith(".cfg") -> Sh
        path.endsWith(".css") || path.endsWith(".scss") -> Kotlin
        else -> Lang(keywords = setOf("class", "def", "function", "return", "if", "import"), lineComment = "//")
    }

    /**
     * Tokenizes one line against [lang]. Block comments starting on an earlier
     * line are not tracked statefully, each line is tokenized independently,
     * matching the viewer's historical behavior.
     */
    fun tokenizeLine(lang: Lang, line: String): List<Token> {
        if (line.isBlank()) return emptyList()
        val tokens = mutableListOf<Token>()

        // Full-line or trailing block comment handled as one span.
        if (lang.blockCommentStart != null && lang.blockCommentEnd != null) {
            val cs = line.indexOf(lang.blockCommentStart)
            if (cs >= 0) {
                val ce = line.indexOf(lang.blockCommentEnd, cs + lang.blockCommentStart.length)
                if (ce >= 0) {
                    tokenizeCode(lang, line.substring(0, cs), tokens)
                    tokens += Token(cs, ce + lang.blockCommentEnd.length, TokenType.COMMENT)
                    tokenizeCode(lang, line.substring(ce + lang.blockCommentEnd.length), offset = ce + lang.blockCommentEnd.length, out = tokens)
                    return tokens
                }
                return tokens.apply {
                    if (cs > 0) tokenizeCode(lang, line.substring(0, cs), out = this)
                    add(Token(cs, line.length, TokenType.COMMENT))
                }
            }
        }

        val lcPos = if (lang.lineComment.isNotEmpty()) line.indexOf(lang.lineComment) else -1
        if (lcPos >= 0) {
            tokenizeCode(lang, line.substring(0, lcPos), tokens)
            tokens += Token(lcPos, line.length, TokenType.COMMENT)
        } else {
            tokenizeCode(lang, line, tokens)
        }
        return tokens
    }

    private fun tokenizeCode(
        lang: Lang,
        codePart: String,
        out: MutableList<Token>,
        offset: Int = 0,
    ) {
        var i = 0
        while (i < codePart.length) {
            val c = codePart[i]
            when {
                c == '"' || c == '\'' -> {
                    val end = findStringEnd(codePart, i).coerceAtMost(codePart.length)
                    out += Token(offset + i, offset + end, TokenType.STRING)
                    i = end
                }
                c == '@' -> {
                    var end = i
                    while (end < codePart.length && !codePart[end].isWhitespace() &&
                        codePart[end] != '(' && codePart[end] != '.'
                    ) end++
                    if (end == i) end = i + 1
                    out += Token(offset + i, offset + end, TokenType.ANNOTATION)
                    i = end
                }
                c.isDigit() -> {
                    val start = i
                    while (i < codePart.length && (codePart[i].isDigit() ||
                            codePart[i] == '.' || codePart[i] == 'x' || codePart[i] == 'b')
                    ) i++
                    out += Token(offset + start, offset + i, TokenType.NUMBER)
                }
                c.isLetterOrDigit() || c == '_' -> {
                    val start = i
                    while (i < codePart.length && (codePart[i].isLetterOrDigit() || codePart[i] == '_')) i++
                    val word = codePart.substring(start, i)
                    val type = when {
                        word in lang.controlKeywords -> TokenType.KEYWORD_CONTROL
                        word in lang.keywords -> TokenType.KEYWORD
                        word in lang.types -> TokenType.TYPE_NAME
                        // Check if followed by open parenthesis for function call
                        i < codePart.length && codePart[i] == '(' -> TokenType.FUNCTION_NAME
                        else -> TokenType.PLAIN
                    }
                    out += Token(
                        offset + start,
                        offset + i,
                        type,
                    )
                }
                c == '+' || c == '-' || c == '*' || c == '/' || c == '=' || c == '!' ||
                    c == '<' || c == '>' || c == '&' || c == '|' || c == '%' || c == '^' ||
                    c == '?' || c == ':' -> {
                    out += Token(offset + i, offset + i + 1, TokenType.OPERATOR)
                    i++
                }
                else -> i++
            }
        }
    }

    private fun findStringEnd(line: String, start: Int): Int {
        val quote = line[start]
        var i = start + 1
        while (i < line.length) {
            when {
                line[i] == '\\' -> i += 2
                line[i] == quote -> return i + 1
                else -> i++
            }
        }
        return line.length
    }
}
