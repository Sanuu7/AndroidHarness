package com.androidharness.app.ui.files

/**
 * Shared lightweight tokenizer for the code viewer and sora-editor language adapter.
 * Produces per-line token spans matching standard modern IDE syntax themes (VS Code / GitHub).
 */
enum class TokenType {
    KEYWORD_CONTROL, // import, package, return, if, else, when, for, while, try, catch
    KEYWORD,         // fun, val, var, class, interface, const, let, def, function
    TYPE_NAME,       // Types, Classes, Interfaces (String, Int, User, Promise, etc)
    FUNCTION_NAME,   // Function declarations and invocations (myFunc(...))
    HTML_TAG,        // HTML/XML tags (<html, <div, <meta, </title>, <!DOCTYPE)
    ATTRIBUTE_NAME,  // HTML attributes, JSON keys, CSS properties (charset, name, href, "key":)
    STRING,          // String literals ("hello", 'world', `template`)
    NUMBER,          // Numbers (123, 0xFF, 3.14f, 100px)
    COMMENT,         // Line and block comments (//, #, /* */, <!-- -->)
    ANNOTATION,      // Annotations and decorators (@Composable, @Override, @decorator)
    OPERATOR,        // Operators and punctuation (+, -, *, /, =, ==, !=, ->, <, >, :)
    PLAIN,           // Regular text, variables, whitespace
}

data class Token(
    /** Column where the token starts (inclusive). */
    val start: Int,
    /** Column just past the token's last character. */
    val end: Int,
    val type: TokenType,
)

object CodeTokenizer {

    enum class SyntaxMode {
        KOTLIN_JAVA,
        PYTHON,
        JS_TS,
        HTML_XML,
        JSON,
        CSS,
        SHELL,
        GENERIC,
    }

    data class LangConfig(
        val mode: SyntaxMode,
        val keywords: Set<String> = emptySet(),
        val controlKeywords: Set<String> = emptySet(),
        val types: Set<String> = emptySet(),
        val lineComment: String = "//",
        val blockCommentStart: String? = null,
        val blockCommentEnd: String? = null,
    )

    private val KotlinLang = LangConfig(
        mode = SyntaxMode.KOTLIN_JAVA,
        controlKeywords = setOf(
            "package", "import", "return", "if", "else", "when", "for", "while", "do",
            "throw", "try", "catch", "finally", "continue", "break",
        ),
        keywords = setOf(
            "class", "interface", "object", "enum", "fun", "val", "var", "data", "sealed", "abstract",
            "open", "override", "private", "protected", "internal", "public", "in", "is", "as", "this",
            "super", "true", "false", "null", "companion", "constructor", "init", "const", "lateinit",
            "by", "suspend", "typealias", "annotation", "inline", "value", "operator", "infix", "yield",
            "final", "static", "extends", "implements", "native", "synchronized", "transient", "volatile",
        ),
        types = setOf(
            "String", "Int", "Long", "Float", "Double", "Boolean", "Char", "Byte", "Short", "Unit", "Any",
            "List", "Map", "Set", "Array", "Sequence", "Pair", "Triple", "Result", "Throwable", "Exception",
        ),
        lineComment = "//", blockCommentStart = "/*", blockCommentEnd = "*/",
    )

    private val PythonLang = LangConfig(
        mode = SyntaxMode.PYTHON,
        controlKeywords = setOf(
            "import", "from", "as", "return", "if", "elif", "else", "for", "while", "try", "except",
            "finally", "with", "yield", "raise", "pass", "break", "continue",
        ),
        keywords = setOf(
            "def", "class", "in", "is", "not", "and", "or", "True", "False", "None",
            "lambda", "self", "async", "await", "global", "nonlocal", "del", "assert",
        ),
        types = setOf("str", "int", "float", "bool", "list", "dict", "set", "tuple", "bytes", "object", "type", "Optional", "Union", "Any"),
        lineComment = "#",
    )

    private val JsTsLang = LangConfig(
        mode = SyntaxMode.JS_TS,
        controlKeywords = setOf(
            "import", "export", "from", "default", "return", "if", "else", "for", "while", "do",
            "switch", "case", "break", "continue", "throw", "try", "catch", "finally",
        ),
        keywords = setOf(
            "function", "const", "let", "var", "class", "new", "this", "super", "true", "false",
            "null", "undefined", "async", "await", "typeof", "instanceof", "interface", "type",
            "extends", "implements", "public", "private", "protected", "readonly", "static", "as",
            "enum", "namespace", "declare", "abstract", "debugger", "in", "of", "void", "delete",
        ),
        types = setOf("string", "number", "boolean", "any", "void", "never", "unknown", "Promise", "Array", "Record", "Object", "Function", "Symbol", "BigInt"),
        lineComment = "//", blockCommentStart = "/*", blockCommentEnd = "*/",
    )

    private val HtmlXmlLang = LangConfig(
        mode = SyntaxMode.HTML_XML,
        lineComment = "<!--", blockCommentStart = "<!--", blockCommentEnd = "-->",
    )

    private val JsonLang = LangConfig(
        mode = SyntaxMode.JSON,
        keywords = setOf("true", "false", "null"),
        lineComment = "",
    )

    private val CssLang = LangConfig(
        mode = SyntaxMode.CSS,
        lineComment = "/*", blockCommentStart = "/*", blockCommentEnd = "*/",
    )

    private val ShellLang = LangConfig(
        mode = SyntaxMode.SHELL,
        controlKeywords = setOf("if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "return", "exit", "source"),
        keywords = setOf("in", "function", "echo", "export", "local", "alias", "unset", "set", "shift", "sudo", "cd", "mkdir", "rm", "cp", "mv"),
        lineComment = "#",
    )

    fun langFor(path: String): LangConfig = when {
        path.endsWith(".kt") || path.endsWith(".kts") || path.endsWith(".java") || path.endsWith(".scala") || path.endsWith(".gradle") || path.endsWith(".groovy") -> KotlinLang
        path.endsWith(".py") || path.endsWith(".pyw") || path.endsWith(".yml") || path.endsWith(".yaml") -> PythonLang
        path.endsWith(".js") || path.endsWith(".ts") || path.endsWith(".jsx") || path.endsWith(".tsx") || path.endsWith(".mjs") || path.endsWith(".cjs") -> JsTsLang
        path.endsWith(".html") || path.endsWith(".htm") || path.endsWith(".xml") || path.endsWith(".svg") || path.endsWith(".vue") || path.endsWith(".svelte") -> HtmlXmlLang
        path.endsWith(".json") || path.endsWith(".json5") || path.endsWith(".jsonc") -> JsonLang
        path.endsWith(".css") || path.endsWith(".scss") || path.endsWith(".sass") || path.endsWith(".less") -> CssLang
        path.endsWith(".sh") || path.endsWith(".bash") || path.endsWith(".zsh") || path.endsWith(".toml") || path.endsWith(".ini") || path.endsWith(".cfg") || path.endsWith(".env") -> ShellLang
        else -> KotlinLang
    }

    /** Tokenizes a single line based on syntax mode. */
    fun tokenizeLine(lang: LangConfig, line: String): List<Token> {
        if (line.isBlank()) return emptyList()
        return when (lang.mode) {
            SyntaxMode.HTML_XML -> tokenizeHtmlXml(lang, line)
            SyntaxMode.JSON -> tokenizeJson(lang, line)
            SyntaxMode.CSS -> tokenizeCss(lang, line)
            else -> tokenizeStandardCode(lang, line)
        }
    }

    // ---------------------------------------------------------------------------
    // HTML / XML Tokenizer
    // ---------------------------------------------------------------------------
    private fun tokenizeHtmlXml(lang: LangConfig, line: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val n = line.length

        // Handle full or partial HTML comments <!-- ... -->
        val commentStart = line.indexOf("<!--")
        if (commentStart >= 0) {
            val commentEnd = line.indexOf("-->", commentStart + 4)
            if (commentEnd >= 0) {
                if (commentStart > 0) {
                    tokens += tokenizeHtmlXml(lang, line.substring(0, commentStart))
                }
                tokens += Token(commentStart, commentEnd + 3, TokenType.COMMENT)
                if (commentEnd + 3 < n) {
                    val tailTokens = tokenizeHtmlXml(lang, line.substring(commentEnd + 3))
                    tailTokens.forEach { tokens += it.copy(start = it.start + commentEnd + 3, end = it.end + commentEnd + 3) }
                }
                return tokens
            } else {
                if (commentStart > 0) {
                    tokens += tokenizeHtmlXml(lang, line.substring(0, commentStart))
                }
                tokens += Token(commentStart, n, TokenType.COMMENT)
                return tokens
            }
        }

        while (i < n) {
            if (line[i] == '<') {
                val tagStart = i
                i++
                // Handle <!DOCTYPE ...> or closing tags </tag>
                val isClosing = i < n && line[i] == '/'
                if (isClosing) i++
                val isDocType = i < n && line[i] == '!'
                if (isDocType) {
                    while (i < n && !line[i].isWhitespace() && line[i] != '>') i++
                    tokens += Token(tagStart, i, TokenType.HTML_TAG)
                } else {
                    val nameStart = i
                    while (i < n && (line[i].isLetterOrDigit() || line[i] == '-' || line[i] == '_' || line[i] == ':')) i++
                    tokens += Token(tagStart, i, TokenType.HTML_TAG)
                }

                // Scan tag body until '>' or '/>'
                while (i < n && line[i] != '>') {
                    val c = line[i]
                    when {
                        c == '"' || c == '\'' -> {
                            val strStart = i
                            val quote = c
                            i++
                            while (i < n && line[i] != quote) {
                                if (line[i] == '\\' && i + 1 < n) i += 2 else i++
                            }
                            if (i < n) i++ // include closing quote
                            tokens += Token(strStart, i, TokenType.STRING)
                        }
                        c == '=' || c == '/' -> {
                            tokens += Token(i, i + 1, TokenType.OPERATOR)
                            i++
                        }
                        c.isLetterOrDigit() || c == '-' || c == '_' || c == ':' || c == '@' || c == '#' -> {
                            val attrStart = i
                            while (i < n && (line[i].isLetterOrDigit() || line[i] == '-' || line[i] == '_' || line[i] == ':' || line[i] == '.' || line[i] == '@' || line[i] == '#')) i++
                            tokens += Token(attrStart, i, TokenType.ATTRIBUTE_NAME)
                        }
                        else -> i++
                    }
                }

                if (i < n && line[i] == '>') {
                    tokens += Token(i, i + 1, TokenType.HTML_TAG)
                    i++
                }
            } else {
                // Outside tag: plain text until next '<'
                val textStart = i
                while (i < n && line[i] != '<') i++
                tokens += Token(textStart, i, TokenType.PLAIN)
            }
        }
        return tokens
    }

    // ---------------------------------------------------------------------------
    // JSON Tokenizer
    // ---------------------------------------------------------------------------
    private fun tokenizeJson(lang: LangConfig, line: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val n = line.length
        while (i < n) {
            val c = line[i]
            when {
                c == '"' || c == '\'' -> {
                    val start = i
                    val quote = c
                    i++
                    while (i < n && line[i] != quote) {
                        if (line[i] == '\\' && i + 1 < n) i += 2 else i++
                    }
                    if (i < n) i++
                    // If followed by ':' (allowing whitespace), this is a JSON Key (ATTRIBUTE_NAME)
                    var lookAhead = i
                    while (lookAhead < n && line[lookAhead].isWhitespace()) lookAhead++
                    val isKey = lookAhead < n && line[lookAhead] == ':'
                    tokens += Token(start, i, if (isKey) TokenType.ATTRIBUTE_NAME else TokenType.STRING)
                }
                c == ':' || c == ',' || c == '{' || c == '}' || c == '[' || c == ']' -> {
                    tokens += Token(i, i + 1, TokenType.OPERATOR)
                    i++
                }
                c.isDigit() || (c == '-' && i + 1 < n && line[i + 1].isDigit()) -> {
                    val start = i
                    i++
                    while (i < n && (line[i].isDigit() || line[i] == '.' || line[i] == 'e' || line[i] == 'E' || line[i] == '+' || line[i] == '-')) i++
                    tokens += Token(start, i, TokenType.NUMBER)
                }
                c.isLetter() -> {
                    val start = i
                    while (i < n && line[i].isLetter()) i++
                    val word = line.substring(start, i)
                    val type = if (word in lang.keywords) TokenType.KEYWORD else TokenType.PLAIN
                    tokens += Token(start, i, type)
                }
                else -> i++
            }
        }
        return tokens
    }

    // ---------------------------------------------------------------------------
    // CSS Tokenizer
    // ---------------------------------------------------------------------------
    private fun tokenizeCss(lang: LangConfig, line: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val n = line.length
        while (i < n) {
            val c = line[i]
            when {
                c == '/' && i + 1 < n && line[i + 1] == '*' -> {
                    val start = i
                    val end = line.indexOf("*/", start + 2)
                    if (end >= 0) {
                        tokens += Token(start, end + 2, TokenType.COMMENT)
                        i = end + 2
                    } else {
                        tokens += Token(start, n, TokenType.COMMENT)
                        i = n
                    }
                }
                c == '"' || c == '\'' -> {
                    val start = i
                    val quote = c
                    i++
                    while (i < n && line[i] != quote) {
                        if (line[i] == '\\' && i + 1 < n) i += 2 else i++
                    }
                    if (i < n) i++
                    tokens += Token(start, i, TokenType.STRING)
                }
                c == '{' || c == '}' || c == ':' || c == ';' || c == ',' -> {
                    tokens += Token(i, i + 1, TokenType.OPERATOR)
                    i++
                }
                c == '#' -> {
                    val start = i
                    i++
                    while (i < n && (line[i].isLetterOrDigit() || line[i] == '-')) i++
                    tokens += Token(start, i, TokenType.NUMBER) // Hex colors or ID
                }
                c.isDigit() -> {
                    val start = i
                    while (i < n && (line[i].isLetterOrDigit() || line[i] == '.' || line[i] == '%')) i++
                    tokens += Token(start, i, TokenType.NUMBER)
                }
                c.isLetter() || c == '-' || c == '.' -> {
                    val start = i
                    while (i < n && (line[i].isLetterOrDigit() || line[i] == '-' || line[i] == '_')) i++
                    var look = i
                    while (look < n && line[look].isWhitespace()) look++
                    val isProp = look < n && line[look] == ':'
                    tokens += Token(start, i, if (isProp) TokenType.ATTRIBUTE_NAME else TokenType.KEYWORD)
                }
                else -> i++
            }
        }
        return tokens
    }

    // ---------------------------------------------------------------------------
    // Standard Code Tokenizer (Kotlin, Java, Python, JS, TS, Shell, etc.)
    // ---------------------------------------------------------------------------
    private fun tokenizeStandardCode(lang: LangConfig, line: String): List<Token> {
        val tokens = mutableListOf<Token>()

        // Block comment
        if (lang.blockCommentStart != null && lang.blockCommentEnd != null) {
            val cs = line.indexOf(lang.blockCommentStart)
            if (cs >= 0) {
                val ce = line.indexOf(lang.blockCommentEnd, cs + lang.blockCommentStart.length)
                if (ce >= 0) {
                    tokenizeCodeChars(lang, line.substring(0, cs), tokens, offset = 0)
                    tokens += Token(cs, ce + lang.blockCommentEnd.length, TokenType.COMMENT)
                    tokenizeCodeChars(lang, line.substring(ce + lang.blockCommentEnd.length), tokens, offset = ce + lang.blockCommentEnd.length)
                    return tokens
                }
                tokenizeCodeChars(lang, line.substring(0, cs), tokens, offset = 0)
                tokens += Token(cs, line.length, TokenType.COMMENT)
                return tokens
            }
        }

        // Line comment
        val lcPos = if (lang.lineComment.isNotEmpty()) line.indexOf(lang.lineComment) else -1
        if (lcPos >= 0) {
            tokenizeCodeChars(lang, line.substring(0, lcPos), tokens, offset = 0)
            tokens += Token(lcPos, line.length, TokenType.COMMENT)
        } else {
            tokenizeCodeChars(lang, line, tokens, offset = 0)
        }
        return tokens
    }

    private fun tokenizeCodeChars(
        lang: LangConfig,
        codePart: String,
        out: MutableList<Token>,
        offset: Int,
    ) {
        var i = 0
        val n = codePart.length
        while (i < n) {
            val c = codePart[i]
            when {
                c == '"' || c == '\'' || c == '`' -> {
                    val start = i
                    val quote = c
                    i++
                    while (i < n && codePart[i] != quote) {
                        if (codePart[i] == '\\' && i + 1 < n) i += 2 else i++
                    }
                    if (i < n) i++
                    out += Token(offset + start, offset + i, TokenType.STRING)
                }
                c == '@' -> {
                    val start = i
                    i++
                    while (i < n && (codePart[i].isLetterOrDigit() || codePart[i] == '_' || codePart[i] == '.')) i++
                    out += Token(offset + start, offset + i, TokenType.ANNOTATION)
                }
                c.isDigit() -> {
                    val start = i
                    while (i < n && (codePart[i].isLetterOrDigit() || codePart[i] == '.' || codePart[i] == '_')) i++
                    out += Token(offset + start, offset + i, TokenType.NUMBER)
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    val start = i
                    while (i < n && (codePart[i].isLetterOrDigit() || codePart[i] == '_' || codePart[i] == '$')) i++
                    val word = codePart.substring(start, i)
                    var lookAhead = i
                    while (lookAhead < n && codePart[lookAhead].isWhitespace()) lookAhead++
                    val isInvocation = lookAhead < n && codePart[lookAhead] == '('

                    val type = when {
                        word in lang.controlKeywords -> TokenType.KEYWORD_CONTROL
                        word in lang.keywords -> TokenType.KEYWORD
                        word in lang.types -> TokenType.TYPE_NAME
                        isInvocation -> TokenType.FUNCTION_NAME
                        word.isNotEmpty() && word[0].isUpperCase() && !word.all { it.isUpperCase() || it == '_' } -> TokenType.TYPE_NAME
                        else -> TokenType.PLAIN
                    }
                    out += Token(offset + start, offset + i, type)
                }
                c == '+' || c == '-' || c == '*' || c == '/' || c == '=' || c == '!' ||
                    c == '<' || c == '>' || c == '&' || c == '|' || c == '%' || c == '^' ||
                    c == '?' || c == ':' || c == '.' || c == ',' || c == ';' || c == '(' ||
                    c == ')' || c == '{' || c == '}' || c == '[' || c == ']' -> {
                    out += Token(offset + i, offset + i + 1, TokenType.OPERATOR)
                    i++
                }
                else -> i++
            }
        }
    }
}
