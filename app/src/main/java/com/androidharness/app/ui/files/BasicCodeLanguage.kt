package com.androidharness.app.ui.files

import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * Keyword-based language for the editor built on [CodeTokenizer]. Token types
 * map onto [EditorColorScheme] slots so the host app theme drives the colors
 * (the editor view overrides those slots with Material colors at setup).
 *
 * Highlighting only, no completion, the right scope for a harness file
 * editor without shipping TextMate grammars.
 */
class BasicCodeLanguage(path: String) : EmptyLanguage() {

    private val lang = CodeTokenizer.langFor(path)

    override fun getAnalyzeManager(): AnalyzeManager = Analyzer()

    private inner class Analyzer : SimpleAnalyzeManager<Unit>() {

        override fun analyze(text: StringBuilder, delegate: Delegate<Unit>): Styles {
            val builder = MappedSpans.Builder()
            var lineStart = 0
            var lineIdx = 0
            var lastStyle = -1L

            var i = 0
            val n = text.length
            while (i <= n) {
                if (i == n || text[i] == '\n') {
                    val line = text.substring(lineStart, i)
                    for (t in CodeTokenizer.tokenizeLine(lang, line)) {
                        val slot = when (t.type) {
                            TokenType.KEYWORD -> slot(KEYWORD, bold = true)
                            TokenType.STRING -> slot(STRING)
                            TokenType.NUMBER -> slot(NUMBER)
                            TokenType.COMMENT -> slot(COMMENT)
                            TokenType.ANNOTATION -> slot(ANNOTATION)
                            TokenType.PLAIN -> continue
                        }
                        if (slot != lastStyle) {
                            builder.addIfNeeded(lineIdx, t.start, slot)
                            lastStyle = slot
                        }
                    }
                    lineIdx++
                    lineStart = i + 1
                    if (i == n) break
                }
                i++
            }
            builder.determine((lineIdx - 1).coerceAtLeast(0))
            return Styles(builder.build())
        }

        private fun slot(id: Int, bold: Boolean = false): Long =
            TextStyle.makeStyle(id, 0, bold, false, false)
    }

    private companion object {
        const val KEYWORD = EditorColorScheme.KEYWORD
        const val COMMENT = EditorColorScheme.COMMENT
        const val STRING = EditorColorScheme.LITERAL
        const val NUMBER = EditorColorScheme.LITERAL
        const val ANNOTATION = EditorColorScheme.ANNOTATION
    }
}
