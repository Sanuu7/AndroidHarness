package com.androidharness.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {
    private val esc = "\u001b"

    private fun emulator(rows: Int = 5, cols: Int = 10, history: Int = 100): Pair<TerminalEmulator, MutableList<String>> {
        val replies = mutableListOf<String>()
        val emu = TerminalEmulator(rows, cols, history)
        emu.onReply = { replies.add(String(it, Charsets.UTF_8)) }
        return emu to replies
    }

    private fun TerminalEmulator.line(row: Int): String = lineAt(row)!!.text()
    private fun TerminalEmulator.styleAt(row: Int, col: Int): Long = lineAt(row)!!.styles[col]

    @Test fun printsTextAndHandlesCarriageReturnAndLineFeed() {
        val (emu, _) = emulator()
        emu.append("hello\r\nworld")
        assertEquals("hello", emu.line(0))
        assertEquals("world", emu.line(1))
        assertEquals(1, emu.cursorRow)
        assertEquals(5, emu.cursorCol)
    }

    @Test fun wrapsAtTheRightMarginOnlyWhenTheNextCharacterArrives() {
        val (emu, _) = emulator(cols = 5)
        emu.append("abcde")
        assertEquals(0, emu.cursorRow)
        assertEquals(4, emu.cursorCol)
        emu.append("f")
        assertEquals("abcde", emu.line(0))
        assertEquals("f", emu.line(1))
        assertTrue(emu.lineAt(0)!!.wrapped)
    }

    @Test fun disablingAutowrapOverwritesTheLastColumn() {
        val (emu, _) = emulator(cols = 5)
        emu.append("$esc[?7labcdefg")
        assertEquals("abcdg", emu.line(0))
        assertEquals(0, emu.cursorRow)
    }

    @Test fun cursorPositioningAndRelativeMoves() {
        val (emu, _) = emulator()
        emu.append("$esc[3;4HX")
        assertEquals("   X", emu.line(2))
        emu.append("$esc[2A$esc[2DY")
        assertEquals("  Y", emu.line(0))
        emu.append("$esc[10B$esc[99CZ")
        assertEquals(4, emu.cursorRow)
        assertEquals("         Z", emu.line(4))
        emu.append("$esc[1G$esc[3dQ")
        assertEquals("Q  X", emu.line(2))
    }

    @Test fun eraseInLineAndDisplay() {
        val (emu, _) = emulator()
        emu.append("aaaaa\r\nbbbbb\r\nccccc")
        emu.append("$esc[2;3H$esc[K")
        assertEquals("bb", emu.line(1))
        emu.append("$esc[1K")
        assertEquals("", emu.line(1))
        emu.append("$esc[J")
        assertEquals("aaaaa", emu.line(0))
        assertEquals("", emu.line(2))
        emu.append("$esc[2J")
        assertEquals("", emu.screenText().trim())
    }

    @Test fun eraseUsesTheCurrentBackgroundColour() {
        val (emu, _) = emulator()
        emu.append("$esc[44m$esc[2K")
        assertEquals(4, CellStyle.bg(emu.styleAt(0, 9)))
    }

    @Test fun basicAndBrightSgrColours() {
        val (emu, _) = emulator()
        emu.append("$esc[1;31mA$esc[0;94;42mB$esc[39;49mC")
        val a = emu.styleAt(0, 0)
        assertEquals(1, CellStyle.fg(a))
        assertTrue(CellStyle.attrs(a) and CellStyle.BOLD != 0)
        val b = emu.styleAt(0, 1)
        assertEquals(12, CellStyle.fg(b))
        assertEquals(2, CellStyle.bg(b))
        assertEquals(0, CellStyle.attrs(b))
        val c = emu.styleAt(0, 2)
        assertEquals(CellStyle.DEFAULT_FG, CellStyle.fg(c))
        assertEquals(CellStyle.DEFAULT_BG, CellStyle.bg(c))
    }

    @Test fun indexedAndTrueColourInBothSyntaxes() {
        val (emu, _) = emulator()
        emu.append("$esc[38;5;208mA$esc[48;2;10;20;30mB$esc[38:2::1:2:3mC$esc[38:5:99mD")
        assertEquals(208, CellStyle.fg(emu.styleAt(0, 0)))
        val b = CellStyle.bg(emu.styleAt(0, 1))
        assertTrue(CellStyle.isTrueColor(b))
        assertEquals(0x0A141E, b and 0xFFFFFF)
        val c = CellStyle.fg(emu.styleAt(0, 2))
        assertEquals(0x010203, c and 0xFFFFFF)
        assertEquals(99, CellStyle.fg(emu.styleAt(0, 3)))
    }

    @Test fun attributesSetAndReset() {
        val (emu, _) = emulator()
        emu.append("$esc[3;4;7;9mA$esc[23;24;27;29mB")
        val a = CellStyle.attrs(emu.styleAt(0, 0))
        assertEquals(CellStyle.ITALIC or CellStyle.UNDERLINE or CellStyle.INVERSE or CellStyle.STRIKE, a)
        assertEquals(0, CellStyle.attrs(emu.styleAt(0, 1)))
    }

    @Test fun alternateScreenKeepsTheMainScreenAndCursor() {
        val (emu, _) = emulator()
        emu.append("prompt$ ")
        emu.append("$esc[?1049h")
        assertTrue(emu.isAltScreen)
        assertEquals("", emu.line(0))
        emu.append("$esc[Hfull screen app")
        emu.append("$esc[?1049l")
        assertFalse(emu.isAltScreen)
        assertEquals("prompt$", emu.line(0))
        assertEquals(8, emu.cursorCol)
    }

    @Test fun scrollRegionScrollsOnlyInsideTheMargins() {
        val (emu, _) = emulator(rows = 5)
        emu.append("top\r\n1\r\n2\r\n3\r\nbottom")
        emu.append("$esc[2;4r")
        // DECSTBM homes the cursor; move to the bottom margin and feed a line.
        emu.append("$esc[4;1H\n")
        assertEquals("top", emu.line(0))
        assertEquals("2", emu.line(1))
        assertEquals("3", emu.line(2))
        assertEquals("", emu.line(3))
        assertEquals("bottom", emu.line(4))
        assertEquals(0, emu.historySize)
    }

    @Test fun reverseIndexAtTheTopMarginScrollsDown() {
        val (emu, _) = emulator(rows = 4)
        emu.append("a\r\nb\r\nc\r\nd")
        emu.append("$esc[H${esc}M")
        assertEquals("", emu.line(0))
        assertEquals("a", emu.line(1))
        assertEquals("c", emu.line(3))
    }

    @Test fun insertAndDeleteLinesAndCharacters() {
        val (emu, _) = emulator(rows = 4)
        emu.append("a\r\nb\r\nc\r\nd")
        emu.append("$esc[2;1H$esc[L")
        assertEquals(listOf("a", "", "b", "c"), (0..3).map { emu.line(it) })
        emu.append("$esc[2M")
        assertEquals(listOf("a", "c", "", ""), (0..3).map { emu.line(it) })
        emu.append("$esc[1;1Habcdef$esc[1;2H$esc[2P")
        assertEquals("adef", emu.line(0))
        emu.append("$esc[2@")
        assertEquals("a  def", emu.line(0))
        emu.append("$esc[3X")
        assertEquals("a   ef", emu.line(0))
    }

    @Test fun linesScrolledOffTheTopGoToHistory() {
        val (emu, _) = emulator(rows = 3)
        emu.append("1\r\n2\r\n3\r\n4\r\n5")
        assertEquals(2, emu.historySize)
        assertEquals("1", emu.lineAt(-2)!!.text())
        assertEquals("2", emu.lineAt(-1)!!.text())
        assertEquals("3", emu.line(0))
        emu.append("$esc[3J")
        assertEquals(0, emu.historySize)
    }

    @Test fun historyIsCapped() {
        val (emu, _) = emulator(rows = 2, history = 3)
        repeat(10) { emu.append("line $it\r\n") }
        assertEquals(3, emu.historySize)
    }

    @Test fun answersCursorPositionAndDeviceAttributes() {
        val (emu, replies) = emulator()
        emu.append("$esc[3;5H$esc[6n$esc[c$esc[>c$esc[5n")
        assertEquals(listOf("$esc[3;5R", "$esc[?62;22c", "$esc[>41;320;0c", "$esc[0n"), replies)
    }

    @Test fun answersBackgroundColourQuery() {
        val (emu, replies) = emulator()
        emu.defaultBackgroundRgb = 0x102030
        emu.append("$esc]11;?\u0007")
        assertEquals("$esc]11;rgb:1010/2020/3030$esc\\", replies.single())
    }

    @Test fun reportsWindowSizeInCells() {
        val (emu, replies) = emulator(rows = 24, cols = 80)
        emu.append("$esc[18t")
        assertEquals("$esc[8;24;80t", replies.single())
    }

    @Test fun decodesUtf8AcrossChunkBoundaries() {
        val (emu, _) = emulator()
        val bytes = "héllo €".toByteArray(Charsets.UTF_8)
        bytes.forEach { emu.append(byteArrayOf(it), 0, 1) }
        assertEquals("héllo €", emu.line(0))
    }

    @Test fun wideCharactersTakeTwoCells() {
        val (emu, _) = emulator()
        emu.append("a中b")
        assertEquals(4, emu.cursorCol)
        assertEquals(TerminalEmulator.WIDE_TAIL, emu.lineAt(0)!!.codes[2])
        assertEquals("a中b", emu.line(0))
        // Overwriting the right half clears the whole wide character.
        emu.append("$esc[1;3HX")
        assertEquals("a Xb", emu.line(0))
    }

    @Test fun wideCharacterAtTheLastColumnWrapsFirst() {
        val (emu, _) = emulator(cols = 5)
        emu.append("abcd中")
        assertEquals("abcd", emu.line(0))
        assertEquals("中", emu.line(1))
    }

    @Test fun combiningMarksAreNotGivenACell() {
        val (emu, _) = emulator()
        emu.append("éx")
        assertEquals(2, emu.cursorCol)
    }

    @Test fun decLineDrawingCharset() {
        val (emu, _) = emulator()
        emu.append("$esc(0lqk$esc(Bq")
        assertEquals("┌─┐q", emu.line(0))
    }

    @Test fun shiftOutUsesTheG1Charset() {
        val (emu, _) = emulator()
        emu.append("$esc)0\u000Eqx\u000Fq")
        assertEquals("─│q", emu.line(0))
    }

    @Test fun tabsStopEveryEightColumns() {
        val (emu, _) = emulator(cols = 20)
        emu.append("a\tb\tc")
        assertEquals("a       b       c", emu.line(0))
        emu.append("$esc[Z")
        assertEquals(16, emu.cursorCol)
        emu.append("$esc[2Z")
        assertEquals(0, emu.cursorCol)
    }

    @Test fun saveAndRestoreCursorKeepsStyle() {
        val (emu, _) = emulator()
        emu.append("$esc[2;3H$esc[31m${esc}7$esc[H$esc[0mX${esc}8Y")
        assertEquals("  Y", emu.line(1))
        assertEquals(1, CellStyle.fg(emu.styleAt(1, 2)))
    }

    @Test fun privateModesAreTracked() {
        val (emu, _) = emulator()
        emu.append("$esc[?1h$esc[?2004h$esc[?25l$esc[?1000h$esc[?1006h")
        assertTrue(emu.applicationCursorKeys)
        assertTrue(emu.bracketedPaste)
        assertFalse(emu.cursorVisible)
        assertEquals(MouseMode.CLICK, emu.mouseMode)
        assertTrue(emu.mouseSgr)
        emu.append("$esc[?1l$esc[?2004l$esc[?25h$esc[?1000l")
        assertFalse(emu.applicationCursorKeys)
        assertFalse(emu.bracketedPaste)
        assertTrue(emu.cursorVisible)
        assertEquals(MouseMode.NONE, emu.mouseMode)
    }

    @Test fun reportsDecPrivateModeState() {
        val (emu, replies) = emulator()
        emu.append("$esc[?2004h$esc[?2004\$p$esc[?1\$p$esc[?9999\$p")
        assertEquals(listOf("$esc[?2004;1\$y", "$esc[?1;2\$y", "$esc[?9999;0\$y"), replies)
    }

    @Test fun cursorShapeFromDecscusr() {
        val (emu, _) = emulator()
        emu.append("$esc[6 q")
        assertEquals(CursorShape.BAR, emu.cursorShape)
        emu.append("$esc[4 q")
        assertEquals(CursorShape.UNDERLINE, emu.cursorShape)
        emu.append("$esc[2 q")
        assertEquals(CursorShape.BLOCK, emu.cursorShape)
    }

    @Test fun oscSetsTitleAndWorkingDirectory() {
        val (emu, _) = emulator()
        emu.append("$esc]0;vim notes.txt\u0007")
        assertEquals("vim notes.txt", emu.title)
        emu.append("$esc]7;file://localhost/data/my%20dir$esc\\")
        assertEquals("/data/my dir", emu.workingDirectory)
        assertEquals("", emu.line(0))
    }

    @Test fun unknownSequencesAndStringsAreSwallowed() {
        val (emu, _) = emulator()
        emu.append("${esc}P1\$r0m$esc\\$esc[?1;2;3zA$esc]52;c;aGVsbG8=\u0007B")
        assertEquals("AB", emu.line(0))
    }

    @Test fun repeatsTheLastCharacter() {
        val (emu, _) = emulator()
        emu.append("x$esc[4b")
        assertEquals("xxxxx", emu.line(0))
    }

    @Test fun resizeNarrowerTruncatesAndKeepsTheCursorInside() {
        val (emu, _) = emulator(rows = 3, cols = 10)
        emu.append("0123456789")
        emu.resize(3, 4)
        assertEquals("0123", emu.line(0))
        assertEquals(3, emu.cursorCol)
        assertEquals(4, emu.cols)
    }

    @Test fun resizeShorterMovesRowsToHistoryAndBack() {
        val (emu, _) = emulator(rows = 5, cols = 10)
        emu.append("a\r\nb\r\nc\r\nd\r\ne")
        emu.resize(3, 10)
        assertEquals(listOf("c", "d", "e"), (0..2).map { emu.line(it) })
        assertEquals(2, emu.cursorRow)
        assertEquals(2, emu.historySize)
        emu.resize(5, 10)
        assertEquals(listOf("a", "b", "c", "d", "e"), (0..4).map { emu.line(it) })
        assertEquals(4, emu.cursorRow)
        assertEquals(0, emu.historySize)
    }

    @Test fun resizeShorterDropsBlankRowsBelowTheCursorFirst() {
        val (emu, _) = emulator(rows = 5, cols = 10)
        emu.append("a\r\nb")
        emu.resize(3, 10)
        assertEquals(listOf("a", "b", ""), (0..2).map { emu.line(it) })
        assertEquals(0, emu.historySize)
        assertEquals(1, emu.cursorRow)
    }

    @Test fun resizeWhileOnTheAlternateScreenRestoresTheMainCursor() {
        val (emu, _) = emulator(rows = 5, cols = 10)
        emu.append("a\r\nb\r\nc\r\nd\r\n$ ")
        emu.append("$esc[?1049h")
        emu.resize(3, 10)
        emu.append("$esc[?1049l")
        assertFalse(emu.isAltScreen)
        assertTrue(emu.cursorRow in 0..2)
        assertEquals("$", emu.line(emu.cursorRow))
    }

    @Test fun originModeMakesPositionsRelativeToTheScrollRegion() {
        val (emu, replies) = emulator(rows = 6)
        emu.append("$esc[3;5r$esc[?6h$esc[1;1HX$esc[6n")
        assertEquals("X", emu.line(2))
        assertEquals("$esc[1;2R", replies.single())
        emu.append("$esc[99;1HY")
        assertEquals("Y", emu.line(4))
    }

    @Test fun backspaceAfterPendingWrapStaysOnTheLine() {
        val (emu, _) = emulator(cols = 5)
        emu.append("abcde\bX")
        assertEquals("abcXe", emu.line(0))
        assertEquals(0, emu.cursorRow)
    }

    @Test fun fullResetClearsTheScreenAndModes() {
        val (emu, _) = emulator()
        emu.append("text$esc[?1h$esc[31m${esc}c")
        assertEquals("", emu.screenText().trim())
        assertFalse(emu.applicationCursorKeys)
        assertEquals(0, emu.cursorCol)
        emu.append("Z")
        assertEquals(CellStyle.DEFAULT_FG, CellStyle.fg(emu.styleAt(0, 0)))
    }

    @Test fun mouseReportsInSgrAndLegacyEncodings() {
        val (emu, _) = emulator(rows = 24, cols = 80)
        assertNull(emu.mouseReport(0, 1, 1, true))
        emu.append("$esc[?1000h$esc[?1006h")
        assertEquals("$esc[<0;3;4M", String(emu.mouseReport(0, 2, 3, true)!!))
        assertEquals("$esc[<0;3;4m", String(emu.mouseReport(0, 2, 3, false)!!))
        emu.append("$esc[?1006l")
        val legacy = emu.mouseReport(0, 2, 3, true)!!
        assertEquals(listOf(27, '['.code, 'M'.code, 32, 35, 36), legacy.map { it.toInt() and 0xFF })
    }

    @Test fun transcriptJoinsWrappedRows() {
        val (emu, _) = emulator(rows = 3, cols = 4)
        emu.append("abcdefgh\r\nnext")
        assertEquals("abcdefgh\nnext", emu.transcriptText())
    }

    @Test fun controlCharactersInsideCsiStillRun() {
        val (emu, _) = emulator()
        emu.append("ab$esc[\b2CX")
        // The backspace inside the sequence moves left first, then the cursor moves right by two.
        assertEquals("ab X", emu.line(0))
        assertEquals(4, emu.cursorCol)
    }

    @Test fun wcwidthTablesAreSortedAndClassifyCommonCharacters() {
        assertEquals(1, WcWidth.of('a'.code))
        assertEquals(2, WcWidth.of('中'.code))
        assertEquals(2, WcWidth.of(0x1F600))
        assertEquals(0, WcWidth.of(0x0301))
        assertEquals(1, WcWidth.of('─'.code))
        assertEquals(2, WcWidth.of(0xAC00))
    }
}
