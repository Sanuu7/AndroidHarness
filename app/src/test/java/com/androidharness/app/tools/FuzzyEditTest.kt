package com.androidharness.app.tools

import com.androidharness.app.tools.FuzzyEdit.Level
import com.androidharness.app.tools.FuzzyEdit.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyEditTest {

    private fun ok(r: Result): Result.Ok = r as Result.Ok

    @Test
    fun `exact match uses level EXACT`() {
        val r = FuzzyEdit.replace("val a = 1\nval b = 2\n", "val a = 1", "val a = 42", false)
        val o = ok(r)
        assertEquals(Level.EXACT, o.level)
        assertEquals(1, o.count)
        assertEquals("val a = 42\nval b = 2\n", o.newText)
    }

    @Test
    fun `trailing whitespace drift matches at LINE_ENDINGS`() {
        val file = "fun foo() {   \n    return 1   \n}\n"
        val old = "fun foo() {\n    return 1\n}"
        val r = FuzzyEdit.replace(file, old, "fun foo() {\n    return 2\n}", false)
        val o = ok(r)
        assertEquals(Level.LINE_ENDINGS, o.level)
        assertTrue(o.newText.contains("return 2"))
        assertTrue(o.newText.endsWith("}\n"))
    }

    @Test
    fun `internal whitespace drift is NOT tolerated`() {
        // Spaces inside the line (not leading/trailing) must fail —
        // conservative by design.
        val file = "fun foo()   {\n    return 1\n}\n"
        val old = "fun foo() {\n    return 1\n}"
        assertTrue(FuzzyEdit.replace(file, old, "x", false) is Result.NotFound)
    }

    @Test
    fun `CRLF file matched with LF old_string`() {
        val file = "int main() {\r\n    return 0;\r\n}\r\n"
        val old = "int main() {\n    return 0;\n}"
        val r = FuzzyEdit.replace(file, old, "int main() {\n    return 1;\n}", false)
        val o = ok(r)
        assertEquals(Level.LINE_ENDINGS, o.level)
        assertTrue(o.newText.contains("return 1"))
        // the CRLF after the final matched line is preserved
        assertTrue(o.newText.endsWith("}\r\n"))
    }

    @Test
    fun `indentation drift matches at INDENTATION`() {
        val file = "if (x) {\n        doThing()\n    }\n"
        val old = "if (x) {\n    doThing()\n}"
        val r = FuzzyEdit.replace(file, old, "if (x) {\n    doOther()\n}", false)
        val o = ok(r)
        assertEquals(Level.INDENTATION, o.level)
        assertTrue(o.newText.contains("doOther()"))
    }

    @Test
    fun `ambiguous at stricter level does not fall through`() {
        val file = "x = 1\nx = 1\n"
        val r = FuzzyEdit.replace(file, "x = 1", "x = 2", false)
        assertTrue(r is Result.Ambiguous)
        assertEquals(2, (r as Result.Ambiguous).count)
    }

    @Test
    fun `replace_all applies every occurrence`() {
        val file = "a\nb\na\n"
        val r = FuzzyEdit.replace(file, "a", "z", true)
        val o = ok(r)
        assertEquals(2, o.count)
        assertEquals("z\nb\nz\n", o.newText)
    }

    @Test
    fun `ambiguous only after normalization still fails`() {
        // Both lines equal after trim, ambiguous at INDENTATION.
        val file = "  foo\n\tfoo\n"
        val old = "foo"
        val r = FuzzyEdit.replace(file, old, "bar", false)
        // L0/L1 find nothing ("  foo" != "foo" exact and trailing-trim doesn't
        // help leading spaces), L2 finds two — must report ambiguous, not replace.
        assertTrue(r is Result.Ambiguous)
    }

    @Test
    fun `not found reports re-read hint`() {
        val r = FuzzyEdit.replace("aaa", "zzz", "b", false)
        assertTrue(r is Result.NotFound)
        assertTrue((r as Result.NotFound).detail.contains("Re-read"))
    }

    @Test
    fun `empty old_string is not found`() {
        assertTrue(FuzzyEdit.replace("abc", "", "x", false) is Result.NotFound)
    }

    @Test
    fun `multi-line window does not produce overlapping matches`() {
        val file = "\n\n\n"
        val old = "\n\n" // lines ["", "", ""]
        val r = FuzzyEdit.replace(file, old, "X", true)
        val o = ok(r)
        assertEquals(1, o.count)
    }

    @Test
    fun `lineEquals tolerance levels`() {
        assertTrue(FuzzyEdit.lineEquals("a ", "a", Level.LINE_ENDINGS))
        assertTrue(!FuzzyEdit.lineEquals(" a", "a", Level.LINE_ENDINGS))
        assertTrue(FuzzyEdit.lineEquals(" a", "a", Level.INDENTATION))
        assertTrue(!FuzzyEdit.lineEquals("a", "b", Level.INDENTATION))
    }
}
