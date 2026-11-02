package com.androidharness.app.ui.settings

import java.text.Normalizer

/**
 * Text folding and fuzzy matching for settings search.
 *
 * The search has to work in every language the app ships, so both the query
 * and the labels are folded the same way first: accents and other combining
 * marks go (é, ё, Arabic harakat), Arabic and Persian letter variants become
 * one letter, full-width forms and native digits become plain ones, and
 * katakana is read as hiragana. Scripts written without spaces still match
 * because every term is also tried as a plain substring.
 *
 * Typo tolerance only runs when nothing matches exactly, so a precise query
 * never drowns in near misses.
 */
internal object SettingsSearch {

    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val out = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            val type = Character.getType(ch)
            if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() || ch == 'ـ') continue
            when {
                ch in 'ァ'..'ヶ' -> out.append(ch - 0x60)
                ch == 'ß' -> out.append("ss")
                ch == 'ı' -> out.append('i')
                ch == 'ي' || ch == 'ى' -> out.append('ی')
                ch == 'ك' -> out.append('ک')
                ch == 'ة' -> out.append('ه')
                ch in '٠'..'٩' -> out.append('0' + (ch - '٠'))
                ch in '۰'..'۹' -> out.append('0' + (ch - '۰'))
                Character.isLetterOrDigit(ch) || type == Character.COMBINING_SPACING_MARK.toInt() -> out.append(ch.lowercaseChar())
                else -> out.append(' ')
            }
        }
        return out.toString().trim().replace(SPACES, " ")
    }

    fun terms(query: String): List<String> = fold(query).split(' ').filter { it.isNotEmpty() }

    /** One searchable label, folded once when the index is built. */
    class Field(text: String, val weight: Double) {
        val folded = fold(text)
        val words = folded.split(' ').filter { it.isNotEmpty() }
        val compact = folded.replace(" ", "")
    }

    /** How well [term] matches [field], from 0 (not at all) to 1 (the whole label). */
    fun quality(term: String, field: Field, fuzzy: Boolean): Double {
        if (field.folded.isEmpty()) return 0.0
        return when {
            field.folded == term -> 1.0
            // A label that starts with the term beats one that mentions it later.
            field.words.first() == term -> 0.97
            field.words.any { it == term } -> 0.95
            field.words.any { it.startsWith(term) } -> 0.85
            term in field.folded -> 0.7
            term.length >= 4 && term in field.compact -> 0.6
            fuzzy -> typoQuality(term, field)
            else -> 0.0
        }
    }

    private fun typoQuality(term: String, field: Field): Double {
        if (term.length < 4) return 0.0
        val allowed = if (term.length >= 8) 2 else 1
        var best = 0.0
        for (word in field.words) {
            if (word.length + allowed < term.length) continue
            val whole = if (kotlin.math.abs(word.length - term.length) <= allowed) distance(term, word) else Int.MAX_VALUE
            if (whole <= allowed) best = maxOf(best, 0.5 - 0.1 * whole)
            // Still typing: "notifcat" against "notifications".
            if (word.length > term.length && distance(term, word.substring(0, term.length)) <= allowed) best = maxOf(best, 0.35)
        }
        return best
    }

    /** Optimal string alignment distance: insertions, deletions, substitutions and swapped neighbours. */
    internal fun distance(a: String, b: String): Int {
        val rows = a.length + 1
        val cols = b.length + 1
        val d = Array(rows) { IntArray(cols) }
        for (i in 0 until rows) d[i][0] = i
        for (j in 0 until cols) d[0][j] = j
        for (i in 1 until rows) for (j in 1 until cols) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var value = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) value = minOf(value, d[i - 2][j - 2] + 1)
            d[i][j] = value
        }
        return d[a.length][b.length]
    }

    private val SPACES = Regex(" +")
}
