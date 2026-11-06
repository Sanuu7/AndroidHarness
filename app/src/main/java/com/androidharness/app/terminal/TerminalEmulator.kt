package com.androidharness.app.terminal

/** One screen line: code points and packed styles, one entry per column. */
class TerminalRow(cols: Int, style: Long = CellStyle.NORMAL) {
    var codes = IntArray(cols)
        private set
    var styles = LongArray(cols) { style }
        private set

    /** True when the text continues on the next row because it reached the right margin. */
    var wrapped = false

    val size: Int get() = codes.size

    fun clear(from: Int, to: Int, style: Long) {
        val end = to.coerceAtMost(codes.size)
        for (i in from.coerceAtLeast(0) until end) {
            codes[i] = 0
            styles[i] = style
        }
    }

    fun resize(cols: Int, style: Long) {
        if (cols == codes.size) return
        val old = codes
        codes = old.copyOf(cols)
        val oldStyles = styles
        styles = LongArray(cols) { if (it < oldStyles.size) oldStyles[it] else style }
        if (cols < old.size && cols > 0 && old[cols] == TerminalEmulator.WIDE_TAIL) codes[cols - 1] = 0
    }

    fun isBlank(): Boolean = codes.all { it == 0 || it == ' '.code }

    fun text(): String {
        val sb = StringBuilder()
        var end = codes.size
        while (end > 0 && (codes[end - 1] == 0 || codes[end - 1] == ' '.code)) end--
        for (i in 0 until end) {
            val c = codes[i]
            when {
                c == TerminalEmulator.WIDE_TAIL -> Unit
                c == 0 -> sb.append(' ')
                else -> sb.appendCodePoint(c)
            }
        }
        return sb.toString()
    }
}

enum class CursorShape { BLOCK, UNDERLINE, BAR }
enum class MouseMode { NONE, CLICK, DRAG, ANY }

/**
 * An xterm-compatible terminal screen model. Feed it the bytes a program
 * writes with [append]; it keeps the visible grid, scrollback, cursor and
 * modes. Answers to terminal queries (cursor position, device attributes)
 * go out through [onReply].
 *
 * Not thread-safe: the owner serialises [append], [resize] and reads.
 */
class TerminalEmulator(
    rows: Int,
    cols: Int,
    private val maxHistory: Int = 2_000,
) {
    var onReply: (ByteArray) -> Unit = {}
    var onBell: () -> Unit = {}

    var rows = rows.coerceAtLeast(1)
        private set
    var cols = cols.coerceAtLeast(1)
        private set

    private class Saved(
        val row: Int, val col: Int, val style: Long, val pendingWrap: Boolean,
        val originMode: Boolean, val autoWrap: Boolean, val charsets: CharArray, val gl: Int,
    )

    private inner class Screen(val keepsHistory: Boolean) {
        val lines = ArrayList<TerminalRow>()
        var saved: Saved? = null
        init { repeat(this@TerminalEmulator.rows) { lines.add(TerminalRow(this@TerminalEmulator.cols)) } }
    }

    private val history = ArrayDeque<TerminalRow>()
    private val main = Screen(keepsHistory = true)
    private val alt = Screen(keepsHistory = false)
    private var screen = main

    val isAltScreen: Boolean get() = screen === alt
    val historySize: Int get() = if (isAltScreen) 0 else history.size

    /** Grows by one for every row pushed into scrollback, so a scrolled-back view can stay put. */
    var historyPushes = 0L
        private set

    /** Bumped on every visible change; renderers compare it to skip identical frames. */
    var revision = 0L
        private set

    var cursorRow = 0
        private set
    var cursorCol = 0
        private set
    private var pendingWrap = false
    private var style = CellStyle.NORMAL

    var cursorVisible = true
        private set
    var cursorShape = CursorShape.BLOCK
        private set
    var cursorBlink = false
        private set
    var applicationCursorKeys = false
        private set
    var applicationKeypad = false
        private set
    var bracketedPaste = false
        private set
    var reverseVideo = false
        private set
    var mouseMode = MouseMode.NONE
        private set
    var mouseSgr = false
        private set
    var focusReporting = false
        private set
    private var insertMode = false
    private var autoWrap = true
    private var originMode = false
    private var newLineMode = false

    var title = ""
        private set
    /** Path from the last OSC 7 report, when the shell sends one. */
    var workingDirectory: String? = null
        private set

    /** Colours answered to OSC 10 and 11 queries, as 0xRRGGBB. */
    var defaultForegroundRgb = 0xE6E1E5
    var defaultBackgroundRgb = 0x1C1B1F

    private var top = 0
    private var bottom = this.rows - 1
    private var tabStops = defaultTabs(this.cols)
    private var charsets = charArrayOf('B', 'B', 'B', 'B')
    private var gl = 0
    private var singleShift = -1
    private var lastChar = ' '.code

    // Parser state.
    private var state = GROUND
    private val params = StringBuilder()
    private var privateMarker = 0
    private val intermediates = StringBuilder()
    private val oscText = StringBuilder()
    private var utf8Remaining = 0
    private var utf8Code = 0
    private var utf8Min = 0

    // ---------------------------------------------------------------- reading

    /** Row [index] for rendering: 0 until [rows] is the screen, negative values reach into scrollback. */
    fun lineAt(index: Int): TerminalRow? = when {
        index >= 0 -> screen.lines.getOrNull(index)
        isAltScreen -> null
        else -> history.getOrNull(history.size + index)
    }

    /** Visible screen as text, one line per row, trailing blanks trimmed. */
    fun screenText(): String = screen.lines.joinToString("\n") { it.text() }

    /** Scrollback and screen as text, with soft-wrapped rows joined back together. */
    fun transcriptText(): String {
        val rowsToJoin = (if (isAltScreen) emptyList() else history.toList()) + screen.lines
        val sb = StringBuilder()
        rowsToJoin.forEachIndexed { i, row ->
            sb.append(row.text())
            if (!row.wrapped && i != rowsToJoin.lastIndex) sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    // ---------------------------------------------------------------- input

    fun append(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        append(bytes, 0, bytes.size)
    }

    fun append(bytes: ByteArray, offset: Int, length: Int) {
        for (i in offset until offset + length) {
            val b = bytes[i].toInt() and 0xFF
            if (utf8Remaining > 0) {
                if (b and 0xC0 == 0x80) {
                    utf8Code = (utf8Code shl 6) or (b and 0x3F)
                    if (--utf8Remaining == 0) {
                        val cp = utf8Code
                        process(if (cp < utf8Min || cp > 0x10FFFF || cp in 0xD800..0xDFFF) 0xFFFD else cp)
                    }
                    continue
                }
                utf8Remaining = 0
                process(0xFFFD)
            }
            when {
                b < 0x80 -> process(b)
                b and 0xE0 == 0xC0 -> { utf8Remaining = 1; utf8Code = b and 0x1F; utf8Min = 0x80 }
                b and 0xF0 == 0xE0 -> { utf8Remaining = 2; utf8Code = b and 0x0F; utf8Min = 0x800 }
                b and 0xF8 == 0xF0 -> { utf8Remaining = 3; utf8Code = b and 0x07; utf8Min = 0x10000 }
                else -> process(0xFFFD)
            }
        }
        revision++
    }

    private fun process(cp: Int) {
        when (state) {
            GROUND -> when {
                cp < 0x20 -> control(cp)
                cp == 0x7F || cp in 0x80..0x9F -> Unit
                else -> print(cp)
            }
            ESCAPE -> when {
                cp == 0x18 || cp == 0x1A -> state = GROUND
                cp < 0x20 -> control(cp)
                cp == '['.code -> { params.setLength(0); intermediates.setLength(0); privateMarker = 0; state = CSI }
                cp == ']'.code -> { oscText.setLength(0); state = OSC }
                cp == 'P'.code || cp == 'X'.code || cp == '^'.code || cp == '_'.code -> state = STRING
                cp in 0x20..0x2F -> { intermediates.append(cp.toChar()); state = ESCAPE_INTERMEDIATE }
                else -> { state = GROUND; escDispatch(cp) }
            }
            ESCAPE_INTERMEDIATE -> when {
                cp == 0x18 || cp == 0x1A -> state = GROUND
                cp < 0x20 -> control(cp)
                cp in 0x20..0x2F -> intermediates.append(cp.toChar())
                else -> { state = GROUND; escDispatch(cp) }
            }
            CSI -> when {
                cp == 0x18 || cp == 0x1A -> state = GROUND
                cp < 0x20 -> control(cp)
                cp in '0'.code..';'.code -> if (params.length < 256) params.append(cp.toChar())
                cp in '<'.code..'?'.code -> if (params.isEmpty() && privateMarker == 0) privateMarker = cp else state = CSI_IGNORE
                cp in 0x20..0x2F -> intermediates.append(cp.toChar())
                cp in 0x40..0x7E -> { state = GROUND; csiDispatch(cp) }
                else -> Unit
            }
            CSI_IGNORE -> when {
                cp == 0x1B -> state = ESCAPE
                cp in 0x40..0x7E -> state = GROUND
            }
            OSC -> when (cp) {
                0x07 -> { state = GROUND; oscDispatch() }
                0x1B -> state = OSC_ESCAPE
                0x18, 0x1A -> state = GROUND
                else -> if (cp >= 0x20 && oscText.length < 4096) oscText.appendCodePoint(cp)
            }
            OSC_ESCAPE -> {
                oscDispatch()
                state = ESCAPE
                if (cp != '\\'.code) process(cp) else state = GROUND
            }
            STRING -> if (cp == 0x1B) state = STRING_ESCAPE else if (cp == 0x18 || cp == 0x1A) state = GROUND
            STRING_ESCAPE -> if (cp == '\\'.code) state = GROUND else { state = ESCAPE; process(cp) }
        }
    }

    private fun control(cp: Int) {
        when (cp) {
            0x07 -> onBell()
            0x08 -> { pendingWrap = false; if (cursorCol > 0) cursorCol-- }
            0x09 -> tabForward(1)
            0x0A, 0x0B, 0x0C -> { pendingWrap = false; index(); if (newLineMode) cursorCol = 0 }
            0x0D -> { pendingWrap = false; cursorCol = 0 }
            0x0E -> gl = 1
            0x0F -> gl = 0
            0x1B -> { intermediates.setLength(0); state = ESCAPE }
            0x18, 0x1A -> state = GROUND
        }
    }

    // ---------------------------------------------------------------- printing

    private fun currentRow(): TerminalRow = screen.lines[cursorRow]

    private fun eraseStyle(): Long = CellStyle.encode(CellStyle.DEFAULT_FG, CellStyle.bg(style), 0)

    private fun print(codePoint: Int) {
        var cp = codePoint
        val set = if (singleShift >= 0) charsets[singleShift].also { singleShift = -1 } else charsets[gl]
        if (set == '0' && cp in 0x5F..0x7E) cp = DEC_GRAPHICS[cp - 0x5F]
        var width = WcWidth.of(cp)
        if (width == 0) return
        if (width == 2 && cols < 2) width = 1
        if (pendingWrap) {
            pendingWrap = false
            if (autoWrap) wrapToNextLine()
        }
        if (width == 2 && cursorCol == cols - 1) {
            if (!autoWrap) return
            currentRow().clear(cursorCol, cols, eraseStyle())
            wrapToNextLine()
        }
        val row = currentRow()
        if (insertMode) insertCells(row, cursorCol, width)
        clearWideAt(row, cursorCol)
        if (width == 2) clearWideAt(row, cursorCol + 1)
        row.codes[cursorCol] = cp
        row.styles[cursorCol] = style
        if (width == 2) {
            row.codes[cursorCol + 1] = WIDE_TAIL
            row.styles[cursorCol + 1] = style
        }
        lastChar = cp
        val next = cursorCol + width
        if (next >= cols) {
            cursorCol = cols - 1
            pendingWrap = autoWrap
        } else {
            cursorCol = next
        }
    }

    private fun wrapToNextLine() {
        currentRow().wrapped = true
        cursorCol = 0
        index()
    }

    private fun clearWideAt(row: TerminalRow, x: Int) {
        if (x < 0 || x >= row.size) return
        if (row.codes[x] == WIDE_TAIL && x > 0) row.codes[x - 1] = 0
        if (x + 1 < row.size && row.codes[x + 1] == WIDE_TAIL) row.codes[x + 1] = 0
    }

    private fun insertCells(row: TerminalRow, at: Int, count: Int) {
        val n = count.coerceAtMost(cols - at)
        if (n <= 0) return
        clearWideAt(row, at)
        for (i in cols - 1 downTo at + n) {
            row.codes[i] = row.codes[i - n]
            row.styles[i] = row.styles[i - n]
        }
        row.clear(at, at + n, eraseStyle())
        if (row.codes[cols - 1] != WIDE_TAIL && cols >= 2 && WcWidth.of(row.codes[cols - 1]) == 2) row.codes[cols - 1] = 0
    }

    private fun deleteCells(row: TerminalRow, at: Int, count: Int) {
        val n = count.coerceAtMost(cols - at)
        if (n <= 0) return
        clearWideAt(row, at)
        clearWideAt(row, at + n - 1)
        for (i in at until cols - n) {
            row.codes[i] = row.codes[i + n]
            row.styles[i] = row.styles[i + n]
        }
        row.clear(cols - n, cols, eraseStyle())
        if (row.codes[at] == WIDE_TAIL) row.codes[at] = 0
    }

    // ---------------------------------------------------------------- scrolling

    private fun blankRow(): TerminalRow = TerminalRow(cols, eraseStyle())

    private fun index() {
        if (cursorRow == bottom) scrollUp(1) else if (cursorRow < rows - 1) cursorRow++
    }

    private fun reverseIndex() {
        if (cursorRow == top) scrollDown(1) else if (cursorRow > 0) cursorRow--
    }

    private fun scrollUp(count: Int) {
        val n = count.coerceIn(0, bottom - top + 1)
        repeat(n) {
            val removed = screen.lines.removeAt(top)
            if (screen.keepsHistory && top == 0) pushHistory(removed)
            screen.lines.add(bottom, blankRow())
        }
    }

    private fun scrollDown(count: Int) {
        val n = count.coerceIn(0, bottom - top + 1)
        repeat(n) {
            screen.lines.removeAt(bottom)
            screen.lines.add(top, blankRow())
        }
    }

    private fun pushHistory(row: TerminalRow) {
        if (maxHistory <= 0) return
        history.addLast(row)
        historyPushes++
        while (history.size > maxHistory) history.removeFirst()
    }

    // ---------------------------------------------------------------- cursor

    private fun setCursor(row: Int, col: Int) {
        pendingWrap = false
        if (originMode) {
            cursorRow = (top + row).coerceIn(top, bottom)
        } else {
            cursorRow = row.coerceIn(0, rows - 1)
        }
        cursorCol = col.coerceIn(0, cols - 1)
    }

    private fun tabForward(count: Int) {
        pendingWrap = false
        repeat(count) {
            var c = cursorCol + 1
            while (c < cols && !tabStops[c]) c++
            cursorCol = c.coerceAtMost(cols - 1)
        }
    }

    private fun tabBackward(count: Int) {
        pendingWrap = false
        repeat(count) {
            var c = cursorCol - 1
            while (c > 0 && !tabStops[c]) c--
            cursorCol = c.coerceAtLeast(0)
        }
    }

    private fun saveCursor() {
        screen.saved = Saved(cursorRow, cursorCol, style, pendingWrap, originMode, autoWrap, charsets.copyOf(), gl)
    }

    private fun restoreCursor() {
        val s = screen.saved
        if (s == null) {
            cursorRow = 0; cursorCol = 0; style = CellStyle.NORMAL; pendingWrap = false
            originMode = false; charsets = charArrayOf('B', 'B', 'B', 'B'); gl = 0
            return
        }
        cursorRow = s.row.coerceIn(0, rows - 1)
        cursorCol = s.col.coerceIn(0, cols - 1)
        style = s.style
        pendingWrap = s.pendingWrap && cursorCol == cols - 1
        originMode = s.originMode
        autoWrap = s.autoWrap
        charsets = s.charsets.copyOf()
        gl = s.gl
    }

    private fun clearScreen(target: Screen) {
        val erase = eraseStyle()
        target.lines.forEach { it.clear(0, it.size, erase); it.wrapped = false }
    }

    private fun setAltScreen(on: Boolean) {
        val target = if (on) alt else main
        if (screen === target) return
        screen = target
        // The inactive screen may predate a resize.
        if (screen.lines.size != rows || screen.lines.any { it.size != cols }) fitScreen(screen, cursorRow)
    }

    // ---------------------------------------------------------------- sequences

    private fun escDispatch(cp: Int) {
        val inter = intermediates.toString()
        intermediates.setLength(0)
        if (inter.isNotEmpty()) {
            when (inter[0]) {
                '(' -> charsets[0] = cp.toChar()
                ')', '-' -> charsets[1] = cp.toChar()
                '*', '.' -> charsets[2] = cp.toChar()
                '+', '/' -> charsets[3] = cp.toChar()
                '#' -> if (cp == '8'.code) {
                    screen.lines.forEach { row -> for (i in 0 until row.size) { row.codes[i] = 'E'.code; row.styles[i] = CellStyle.NORMAL } }
                    setCursor(0, 0)
                }
            }
            return
        }
        when (cp.toChar()) {
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> { pendingWrap = false; index() }
            'E' -> { pendingWrap = false; cursorCol = 0; index() }
            'H' -> tabStops[cursorCol] = true
            'M' -> { pendingWrap = false; reverseIndex() }
            'c' -> fullReset()
            '=' -> applicationKeypad = true
            '>' -> applicationKeypad = false
            'N' -> singleShift = 2
            'O' -> singleShift = 3
            'n' -> gl = 2
            'o' -> gl = 3
        }
    }

    private fun parseParams(): List<IntArray> {
        if (params.isEmpty()) return emptyList()
        return params.split(';').map { part ->
            part.split(':').map { it.toIntOrNull()?.coerceAtMost(65_535) ?: -1 }.toIntArray()
        }
    }

    private fun csiDispatch(finalByte: Int) {
        val groups = parseParams()
        fun arg(i: Int, default: Int): Int {
            val v = groups.getOrNull(i)?.firstOrNull() ?: -1
            return if (v <= 0) default else v
        }
        fun raw(i: Int): Int = groups.getOrNull(i)?.firstOrNull()?.takeIf { it >= 0 } ?: 0
        val inter = intermediates.toString()
        val marker = privateMarker
        if (inter.isNotEmpty()) {
            when {
                inter == " " && finalByte == 'q'.code -> setCursorStyle(raw(0))
                inter == "!" && finalByte == 'p'.code -> softReset()
                inter == "$" && finalByte == 'p'.code -> reportMode(raw(0), marker == '?'.code)
            }
            return
        }
        when (finalByte.toChar()) {
            '@' -> if (marker == 0) insertCells(currentRow(), cursorCol, arg(0, 1))
            'A' -> cursorUp(arg(0, 1))
            'B', 'e' -> cursorDown(arg(0, 1))
            'C', 'a' -> { pendingWrap = false; cursorCol = (cursorCol + arg(0, 1)).coerceAtMost(cols - 1) }
            'D' -> { pendingWrap = false; cursorCol = (cursorCol - arg(0, 1)).coerceAtLeast(0) }
            'E' -> { cursorDown(arg(0, 1)); cursorCol = 0 }
            'F' -> { cursorUp(arg(0, 1)); cursorCol = 0 }
            'G', '`' -> { pendingWrap = false; cursorCol = (arg(0, 1) - 1).coerceIn(0, cols - 1) }
            'H', 'f' -> setCursor(arg(0, 1) - 1, arg(1, 1) - 1)
            'I' -> tabForward(arg(0, 1))
            'J' -> eraseInDisplay(raw(0))
            'K' -> eraseInLine(raw(0))
            'L' -> insertLines(arg(0, 1))
            'M' -> deleteLines(arg(0, 1))
            'P' -> deleteCells(currentRow(), cursorCol, arg(0, 1))
            'S' -> if (marker == 0) scrollUp(arg(0, 1))
            'T' -> if (marker == 0 && groups.size <= 1) scrollDown(arg(0, 1))
            'X' -> {
                pendingWrap = false
                val row = currentRow()
                val end = (cursorCol + arg(0, 1)).coerceAtMost(cols)
                clearWideAt(row, cursorCol)
                clearWideAt(row, end - 1)
                row.clear(cursorCol, end, eraseStyle())
            }
            'Z' -> tabBackward(arg(0, 1))
            'b' -> {
                val n = arg(0, 1).coerceAtMost(rows * cols)
                val ch = lastChar
                repeat(n) { print(ch) }
            }
            'c' -> when (marker) {
                0 -> if (raw(0) == 0) reply("\u001b[?62;22c")
                '>'.code -> reply("\u001b[>41;320;0c")
            }
            'd' -> { pendingWrap = false; setCursor(arg(0, 1) - 1, cursorCol) }
            'g' -> when (raw(0)) {
                0 -> tabStops[cursorCol] = false
                3 -> tabStops.fill(false)
            }
            'h' -> groups.forEach { setMode(it.firstOrNull() ?: 0, marker == '?'.code, true) }
            'l' -> groups.forEach { setMode(it.firstOrNull() ?: 0, marker == '?'.code, false) }
            'm' -> if (marker == 0) selectGraphicRendition(groups)
            'n' -> if (marker == 0 || marker == '?'.code) when (raw(0)) {
                5 -> reply("\u001b[0n")
                6 -> {
                    val r = if (originMode) cursorRow - top + 1 else cursorRow + 1
                    reply("\u001b[${if (marker != 0) "?" else ""}$r;${cursorCol + 1}R")
                }
            }
            'r' -> if (marker == 0) {
                val t = arg(0, 1) - 1
                val b = (if (raw(1) <= 0) rows else raw(1)).coerceAtMost(rows) - 1
                if (t < b) {
                    top = t
                    bottom = b
                    setCursor(0, 0)
                }
            }
            's' -> if (marker == 0) saveCursor()
            'u' -> if (marker == 0) restoreCursor()
            't' -> if (raw(0) == 18) reply("\u001b[8;$rows;${cols}t")
        }
    }

    private fun cursorUp(n: Int) {
        pendingWrap = false
        val limit = if (cursorRow >= top) top else 0
        cursorRow = (cursorRow - n).coerceAtLeast(limit)
    }

    private fun cursorDown(n: Int) {
        pendingWrap = false
        val limit = if (cursorRow <= bottom) bottom else rows - 1
        cursorRow = (cursorRow + n).coerceAtMost(limit)
    }

    private fun eraseInLine(mode: Int) {
        pendingWrap = false
        val row = currentRow()
        val erase = eraseStyle()
        when (mode) {
            0 -> { clearWideAt(row, cursorCol); row.clear(cursorCol, cols, erase); row.wrapped = false }
            1 -> { clearWideAt(row, cursorCol); row.clear(0, cursorCol + 1, erase) }
            2 -> { row.clear(0, cols, erase); row.wrapped = false }
        }
    }

    private fun eraseInDisplay(mode: Int) {
        pendingWrap = false
        val erase = eraseStyle()
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (r in cursorRow + 1 until rows) screen.lines[r].apply { clear(0, size, erase); wrapped = false }
            }
            1 -> {
                eraseInLine(1)
                for (r in 0 until cursorRow) screen.lines[r].apply { clear(0, size, erase); wrapped = false }
            }
            2 -> clearScreen(screen)
            3 -> if (screen.keepsHistory) { history.clear() }
        }
    }

    private fun insertLines(n: Int) {
        if (cursorRow !in top..bottom) return
        pendingWrap = false
        repeat(n.coerceAtMost(bottom - cursorRow + 1)) {
            screen.lines.removeAt(bottom)
            screen.lines.add(cursorRow, blankRow())
        }
        cursorCol = 0
    }

    private fun deleteLines(n: Int) {
        if (cursorRow !in top..bottom) return
        pendingWrap = false
        repeat(n.coerceAtMost(bottom - cursorRow + 1)) {
            screen.lines.removeAt(cursorRow)
            screen.lines.add(bottom, blankRow())
        }
        cursorCol = 0
    }

    private fun setCursorStyle(n: Int) {
        cursorShape = when (n) {
            3, 4 -> CursorShape.UNDERLINE
            5, 6 -> CursorShape.BAR
            else -> CursorShape.BLOCK
        }
        cursorBlink = n == 0 || n == 1 || n == 3 || n == 5
    }

    private fun setMode(mode: Int, private: Boolean, on: Boolean) {
        if (!private) {
            when (mode) {
                4 -> insertMode = on
                20 -> newLineMode = on
            }
            return
        }
        when (mode) {
            1 -> applicationCursorKeys = on
            5 -> reverseVideo = on
            6 -> { originMode = on; setCursor(0, 0) }
            7 -> autoWrap = on
            12 -> cursorBlink = on
            25 -> cursorVisible = on
            9, 1000 -> mouseMode = if (on) MouseMode.CLICK else MouseMode.NONE
            1002 -> mouseMode = if (on) MouseMode.DRAG else MouseMode.NONE
            1003 -> mouseMode = if (on) MouseMode.ANY else MouseMode.NONE
            1004 -> focusReporting = on
            1006 -> mouseSgr = on
            2004 -> bracketedPaste = on
            47 -> setAltScreen(on)
            1047 -> {
                if (!on && isAltScreen) clearScreen(alt)
                setAltScreen(on)
            }
            1048 -> if (on) saveCursor() else restoreCursor()
            1049 -> if (on) {
                if (!isAltScreen) {
                    saveCursor()
                    setAltScreen(true)
                    clearScreen(alt)
                }
            } else if (isAltScreen) {
                setAltScreen(false)
                restoreCursor()
            }
        }
    }

    private fun reportMode(mode: Int, private: Boolean) {
        val value: Boolean? = if (private) when (mode) {
            1 -> applicationCursorKeys
            5 -> reverseVideo
            6 -> originMode
            7 -> autoWrap
            12 -> cursorBlink
            25 -> cursorVisible
            1000 -> mouseMode == MouseMode.CLICK
            1002 -> mouseMode == MouseMode.DRAG
            1003 -> mouseMode == MouseMode.ANY
            1004 -> focusReporting
            1006 -> mouseSgr
            1049, 1047, 47 -> isAltScreen
            2004 -> bracketedPaste
            else -> null
        } else when (mode) {
            4 -> insertMode
            20 -> newLineMode
            else -> null
        }
        val state = when (value) { null -> 0; true -> 1; false -> 2 }
        reply("\u001b[${if (private) "?" else ""}$mode;$state\$y")
    }

    private fun selectGraphicRendition(groups: List<IntArray>) {
        if (groups.isEmpty()) { style = CellStyle.NORMAL; return }
        var fg = CellStyle.fg(style)
        var bg = CellStyle.bg(style)
        var attrs = CellStyle.attrs(style)
        var i = 0
        fun extended(group: IntArray, isFg: Boolean) {
            // Colon form keeps everything in one group: 38:5:n, 38:2:r:g:b or 38:2:cs:r:g:b.
            val color: Int? = if (group.size > 1) {
                when (group[1]) {
                    5 -> group.getOrNull(2)?.takeIf { it in 0..255 }
                    2 -> {
                        val rgb = if (group.size >= 6) group.copyOfRange(3, 6) else group.copyOfRange(2, minOf(5, group.size))
                        if (rgb.size == 3) CellStyle.rgb(rgb[0].coerceAtLeast(0), rgb[1].coerceAtLeast(0), rgb[2].coerceAtLeast(0)) else null
                    }
                    else -> null
                }
            } else {
                when (groups.getOrNull(i + 1)?.firstOrNull()) {
                    5 -> groups.getOrNull(i + 2)?.firstOrNull()?.takeIf { it in 0..255 }.also { i += 2 }
                    2 -> {
                        val r = groups.getOrNull(i + 2)?.firstOrNull() ?: 0
                        val g = groups.getOrNull(i + 3)?.firstOrNull() ?: 0
                        val b = groups.getOrNull(i + 4)?.firstOrNull() ?: 0
                        i += 4
                        CellStyle.rgb(r.coerceAtLeast(0), g.coerceAtLeast(0), b.coerceAtLeast(0))
                    }
                    else -> null
                }
            }
            if (color != null) { if (isFg) fg = color else bg = color }
        }
        while (i < groups.size) {
            val group = groups[i]
            when (val p = group.first().coerceAtLeast(0)) {
                0 -> { fg = CellStyle.DEFAULT_FG; bg = CellStyle.DEFAULT_BG; attrs = 0 }
                1 -> attrs = attrs or CellStyle.BOLD
                2 -> attrs = attrs or CellStyle.DIM
                3 -> attrs = attrs or CellStyle.ITALIC
                4 -> attrs = if (group.size > 1 && group[1] == 0) attrs and CellStyle.UNDERLINE.inv() else attrs or CellStyle.UNDERLINE
                5, 6 -> attrs = attrs or CellStyle.BLINK
                7 -> attrs = attrs or CellStyle.INVERSE
                8 -> attrs = attrs or CellStyle.INVISIBLE
                9 -> attrs = attrs or CellStyle.STRIKE
                21 -> attrs = attrs or CellStyle.UNDERLINE
                22 -> attrs = attrs and (CellStyle.BOLD or CellStyle.DIM).inv()
                23 -> attrs = attrs and CellStyle.ITALIC.inv()
                24 -> attrs = attrs and CellStyle.UNDERLINE.inv()
                25 -> attrs = attrs and CellStyle.BLINK.inv()
                27 -> attrs = attrs and CellStyle.INVERSE.inv()
                28 -> attrs = attrs and CellStyle.INVISIBLE.inv()
                29 -> attrs = attrs and CellStyle.STRIKE.inv()
                in 30..37 -> fg = p - 30
                38 -> extended(group, isFg = true)
                39 -> fg = CellStyle.DEFAULT_FG
                in 40..47 -> bg = p - 40
                48 -> extended(group, isFg = false)
                49 -> bg = CellStyle.DEFAULT_BG
                58 -> { val saveFg = fg; val saveBg = bg; extended(group, isFg = true); fg = saveFg; bg = saveBg }
                in 90..97 -> fg = p - 90 + 8
                in 100..107 -> bg = p - 100 + 8
            }
            i++
        }
        style = CellStyle.encode(fg, bg, attrs)
    }

    private fun oscDispatch() {
        val text = oscText.toString()
        val sep = text.indexOf(';')
        val code = (if (sep < 0) text else text.substring(0, sep)).toIntOrNull() ?: return
        val value = if (sep < 0) "" else text.substring(sep + 1)
        when (code) {
            0, 2 -> title = value.take(256)
            7 -> workingDirectory = value.removePrefix("file://").let { rest ->
                val slash = rest.indexOf('/')
                if (slash >= 0) percentDecode(rest.substring(slash)) else null
            }
            10, 11 -> if (value == "?") {
                val rgb = if (code == 10) defaultForegroundRgb else defaultBackgroundRgb
                fun ch(shift: Int) = "%02x".format((rgb shr shift) and 0xFF).repeat(2)
                reply("\u001b]$code;rgb:${ch(16)}/${ch(8)}/${ch(0)}\u001b\\")
            }
        }
    }

    private fun reply(text: String) = onReply(text.toByteArray(Charsets.UTF_8))

    private fun softReset() {
        cursorVisible = true
        originMode = false
        autoWrap = true
        insertMode = false
        applicationCursorKeys = false
        applicationKeypad = false
        top = 0
        bottom = rows - 1
        style = CellStyle.NORMAL
        charsets = charArrayOf('B', 'B', 'B', 'B')
        gl = 0
        pendingWrap = false
        screen.saved = null
    }

    private fun fullReset() {
        softReset()
        setAltScreen(false)
        main.saved = null
        alt.saved = null
        clearScreen(main)
        clearScreen(alt)
        cursorRow = 0
        cursorCol = 0
        reverseVideo = false
        bracketedPaste = false
        mouseMode = MouseMode.NONE
        mouseSgr = false
        focusReporting = false
        newLineMode = false
        cursorShape = CursorShape.BLOCK
        cursorBlink = false
        tabStops = defaultTabs(cols)
        title = ""
    }

    // ---------------------------------------------------------------- resizing

    /**
     * Changes the grid size. Rows that no longer fit move into scrollback
     * (blank rows under the cursor go first), and growing pulls them back, so
     * rotating there and back keeps the shell's output in place.
     */
    fun resize(newRows: Int, newCols: Int) {
        val r = newRows.coerceAtLeast(1)
        val c = newCols.coerceAtLeast(1)
        if (r == rows && c == cols) return
        rows = r
        cols = c
        cursorRow = fitScreen(screen, cursorRow)
        val other = if (screen === main) alt else main
        val otherCursor = fitScreen(other, other.saved?.row ?: lastUsedRow(other))
        other.saved?.let { s -> other.saved = Saved(otherCursor, s.col.coerceAtMost(c - 1), s.style, false, s.originMode, s.autoWrap, s.charsets, s.gl) }
        cursorCol = cursorCol.coerceIn(0, c - 1)
        pendingWrap = false
        top = 0
        bottom = r - 1
        tabStops = defaultTabs(c)
        revision++
    }

    private fun lastUsedRow(target: Screen): Int {
        for (i in target.lines.indices.reversed()) if (!target.lines[i].isBlank()) return i
        return 0
    }

    /** Fits [target] to the current size and returns where [cursor] ends up. */
    private fun fitScreen(target: Screen, cursor: Int): Int {
        val erase = CellStyle.NORMAL
        target.lines.forEach { it.resize(cols, erase) }
        var cr = cursor
        var excess = target.lines.size - rows
        if (excess > 0) {
            var i = target.lines.size - 1
            while (excess > 0 && i > cr && target.lines[i].isBlank()) {
                target.lines.removeAt(i)
                i--
                excess--
            }
            while (excess > 0) {
                val removed = target.lines.removeAt(0)
                if (target.keepsHistory) pushHistory(removed)
                cr--
                excess--
            }
        } else if (excess < 0) {
            var extra = -excess
            if (target.keepsHistory) {
                while (extra > 0 && history.isNotEmpty() && target === screen) {
                    val row = history.removeLast()
                    row.resize(cols, erase)
                    target.lines.add(0, row)
                    cr++
                    extra--
                }
            }
            repeat(extra) { target.lines.add(TerminalRow(cols)) }
        }
        return cr.coerceIn(0, rows - 1)
    }

    // ---------------------------------------------------------------- mouse

    /**
     * Mouse report for a click or wheel step at a cell, or null when the
     * program has not asked for mouse input. [button] is 0..2 for the
     * buttons and 64/65 for wheel up/down.
     */
    fun mouseReport(button: Int, col: Int, row: Int, pressed: Boolean): ByteArray? {
        if (mouseMode == MouseMode.NONE) return null
        val x = col.coerceIn(0, cols - 1) + 1
        val y = row.coerceIn(0, rows - 1) + 1
        if (mouseSgr) return "\u001b[<$button;$x;$y${if (pressed) 'M' else 'm'}".toByteArray()
        if (x > 223 || y > 223) return null
        val b = if (pressed || button >= 64) button else 3
        return byteArrayOf(0x1B, '['.code.toByte(), 'M'.code.toByte(), (32 + b).toByte(), (32 + x).toByte(), (32 + y).toByte())
    }

    companion object {
        /** Marks the right half of a double-width character. */
        const val WIDE_TAIL = -1

        private const val GROUND = 0
        private const val ESCAPE = 1
        private const val ESCAPE_INTERMEDIATE = 2
        private const val CSI = 3
        private const val CSI_IGNORE = 4
        private const val OSC = 5
        private const val OSC_ESCAPE = 6
        private const val STRING = 7
        private const val STRING_ESCAPE = 8

        private fun percentDecode(text: String): String {
            if ('%' !in text) return text
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val hex = if (c == '%' && i + 2 < text.length) text.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (hex != null) { out.write(hex); i += 3 } else { out.write(c.toString().toByteArray()); i++ }
            }
            return out.toString(Charsets.UTF_8.name())
        }

        private fun defaultTabs(cols: Int) = BooleanArray(cols) { it > 0 && it % 8 == 0 }

        /** DEC special graphics for 0x5F..0x7E, used for line drawing. */
        private val DEC_GRAPHICS = intArrayOf(
            0x00A0, 0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0x00B0,
            0x00B1, 0x2424, 0x240B, 0x2518, 0x2510, 0x250C, 0x2514, 0x253C,
            0x23BA, 0x23BB, 0x2500, 0x23BC, 0x23BD, 0x251C, 0x2524, 0x2534,
            0x252C, 0x2502, 0x2264, 0x2265, 0x03C0, 0x2260, 0x00A3, 0x00B7,
        )
    }
}
