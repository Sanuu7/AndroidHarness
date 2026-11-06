package com.androidharness.app.ui.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.content.res.ResourcesCompat
import com.androidharness.app.R
import com.androidharness.app.terminal.CellStyle
import com.androidharness.app.terminal.CursorShape
import com.androidharness.app.terminal.KeyEncoder
import com.androidharness.app.terminal.KeyModifiers
import com.androidharness.app.terminal.MouseMode
import com.androidharness.app.terminal.TermKey
import com.androidharness.app.terminal.TerminalEmulator
import com.androidharness.app.terminal.TerminalRow
import com.androidharness.app.terminal.TerminalSession
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Colours for one terminal theme, as ARGB ints. [ansi] holds the 16 base colours. */
data class TerminalColors(
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val cursorText: Int,
    val ansi: IntArray,
) {
    /** The full 256-colour palette: the 16 theme colours, the 6x6x6 cube, then 24 greys. */
    val palette: IntArray = IntArray(256) { i ->
        when {
            i < 16 -> ansi[i]
            i < 232 -> {
                val n = i - 16
                fun level(v: Int) = if (v == 0) 0 else 55 + v * 40
                argb(level(n / 36), level((n / 6) % 6), level(n % 6))
            }
            else -> (8 + (i - 232) * 10).let { argb(it, it, it) }
        }
    }

    override fun equals(other: Any?): Boolean = other is TerminalColors && background == other.background &&
        foreground == other.foreground && cursor == other.cursor && cursorText == other.cursorText && ansi.contentEquals(other.ansi)

    override fun hashCode(): Int = (background * 31 + foreground) * 31 + ansi.contentHashCode()

    companion object {
        fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/**
 * Draws a [TerminalSession]'s screen and turns touches, the soft keyboard and
 * hardware keys into terminal input. The Compose screen hosts it with
 * AndroidView, because a raw InputConnection is the reliable way to get every
 * key from the soft keyboard without a text field in between.
 */
@SuppressLint("ViewConstructor")
class TerminalCanvasView(context: Context) : View(context) {
    var session: TerminalSession? = null
        private set
    var colors: TerminalColors = TerminalColors(0xFF1C1B1F.toInt(), 0xFFE6E1E5.toInt(), 0xFFD0BCFF.toInt(), 0xFF381E72.toInt(), IntArray(16))
        set(value) {
            if (field == value) return
            field = value
            session?.let { s -> synchronized(s.emulator) { applyQueryColors(s.emulator) } }
            invalidate()
        }

    /** Grid size in cells and pixels, reported whenever the view or the font size changes. */
    var onGridSize: ((rows: Int, cols: Int, widthPx: Int, heightPx: Int) -> Unit)? = null
    /** Modifiers latched on the extra-keys row; cleared through [onModifiersUsed] after one key. */
    var latchedModifiers: () -> KeyModifiers = { KeyModifiers.NONE }
    var onModifiersUsed: () -> Unit = {}
    var onFontSizeChange: ((Float) -> Unit)? = null
    var onPasteRequest: (() -> Unit)? = null
    var onCopyRequest: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val baseTypeface: Typeface = runCatching { ResourcesCompat.getFont(context, R.font.noto_sans_mono_variable) }.getOrNull() ?: Typeface.MONOSPACE
    private val boldTypeface: Typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(baseTypeface, 700, false) else Typeface.create(baseTypeface, Typeface.BOLD)
    private val italicTypeface: Typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(baseTypeface, 400, true) else Typeface.create(baseTypeface, Typeface.ITALIC)
    private val boldItalicTypeface: Typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(baseTypeface, 700, true) else Typeface.create(baseTypeface, Typeface.BOLD_ITALIC)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = baseTypeface }
    private val fillPaint = Paint()

    var fontSizeSp = 13f
        set(value) {
            val clamped = value.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
            if (abs(field - clamped) < 0.01f) return
            field = clamped
            updateMetrics()
            reportGrid()
            invalidate()
        }

    private var cellWidth = 0f
    private var lineHeight = 0
    private var baselineOffset = 0
    private var gridRows = 0
    private var gridCols = 0
    private val padding = (4 * density).toInt()

    /** Rows scrolled back into history; 0 shows the live screen. */
    private var scrollOffset = 0
    private var seenHistoryPushes = 0L
    private var scrollRemainder = 0f

    private val updateListener: () -> Unit = { postInvalidateOnAnimation() }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        updateMetrics()
        contentDescription = context.getString(R.string.terminal_view_description)
    }

    fun attach(next: TerminalSession?) {
        if (session === next) return
        session?.removeListener(updateListener)
        session?.onBell = null
        session = next
        scrollOffset = 0
        if (next != null) {
            next.addListener(updateListener)
            next.onBell = { post { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } }
            synchronized(next.emulator) {
                seenHistoryPushes = next.emulator.historyPushes
                applyQueryColors(next.emulator)
            }
            if (gridRows > 0) reportGrid()
        }
        invalidate()
    }

    private fun applyQueryColors(emulator: TerminalEmulator) {
        emulator.defaultForegroundRgb = colors.foreground and 0xFFFFFF
        emulator.defaultBackgroundRgb = colors.background and 0xFFFFFF
    }

    override fun onDetachedFromWindow() {
        session?.removeListener(updateListener)
        session?.onBell = null
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        session?.let { s -> s.addListener(updateListener); s.onBell = { post { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } } }
    }

    private fun updateMetrics() {
        textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        textPaint.typeface = baseTypeface
        cellWidth = textPaint.measureText("M")
        val fm = textPaint.fontMetricsInt
        lineHeight = (fm.descent - fm.ascent).coerceAtLeast(1)
        baselineOffset = -fm.ascent
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reportGrid()
    }

    private fun reportGrid() {
        if (width <= 0 || height <= 0 || cellWidth <= 0f) return
        val cols = floor((width - 2 * padding) / cellWidth).toInt().coerceAtLeast(4)
        val rows = ((height - 2 * padding) / lineHeight).coerceAtLeast(2)
        gridRows = rows
        gridCols = cols
        onGridSize?.invoke(rows, cols, width, height)
    }

    // ------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val c = colors
        val s = session
        if (s == null) {
            canvas.drawColor(c.background)
            return
        }
        synchronized(s.emulator) {
            val emu = s.emulator
            val reverse = emu.reverseVideo
            canvas.drawColor(if (reverse) c.foreground else c.background)
            // Keep a scrolled-back view on the same text while output keeps arriving.
            if (scrollOffset > 0) scrollOffset += (emu.historyPushes - seenHistoryPushes).toInt()
            seenHistoryPushes = emu.historyPushes
            scrollOffset = scrollOffset.coerceIn(0, emu.historySize)
            val showCursor = scrollOffset == 0 && emu.cursorVisible && s.running
            for (r in 0 until emu.rows) {
                val row = emu.lineAt(r - scrollOffset) ?: continue
                val top = padding + r * lineHeight
                drawRow(canvas, row, top, emu.cols, reverse, if (showCursor && r == emu.cursorRow) emu.cursorCol else -1, emu.cursorShape)
            }
            if (scrollOffset > 0) drawScrollIndicator(canvas, emu)
        }
    }

    private fun color(index: Int, isFg: Boolean, reverse: Boolean): Int {
        val c = colors
        return when {
            index == CellStyle.DEFAULT_FG -> if (reverse) c.background else c.foreground
            index == CellStyle.DEFAULT_BG -> if (reverse) c.foreground else c.background
            CellStyle.isTrueColor(index) -> (0xFF shl 24) or (index and 0xFFFFFF)
            index in 0..255 -> c.palette[index]
            else -> if (isFg) c.foreground else c.background
        }
    }

    private val runChars = CharArray(1024)

    private fun drawRow(canvas: Canvas, row: TerminalRow, top: Int, cols: Int, reverse: Boolean, cursorCol: Int, cursorShape: CursorShape) {
        val width = minOf(cols, row.size)
        val baseline = (top + baselineOffset).toFloat()
        var col = 0
        while (col < width) {
            val style = row.styles[col]
            val attrs = CellStyle.attrs(style)
            var fg = color(CellStyle.fg(style), true, reverse)
            var bg = color(CellStyle.bg(style), false, reverse)
            if (attrs and CellStyle.INVERSE != 0) { val t = fg; fg = bg; bg = t }
            if (attrs and CellStyle.DIM != 0) fg = (fg and 0x00FFFFFF) or (0x99 shl 24)
            // A run shares one style; the cursor cell always gets a run of its own.
            var end = col + 1
            if (col != cursorCol) {
                while (end < width && end != cursorCol && row.styles[end] == style && end - col < runChars.size) end++
            }
            var asciiOnly = true
            for (i in col until end) {
                val code = row.codes[i]
                if (code > 0x7E || code == TerminalEmulator.WIDE_TAIL) { asciiOnly = false; break }
            }
            val left = padding + col * cellWidth
            val right = padding + end * cellWidth
            val defaultBg = if (reverse) colors.foreground else colors.background
            if (bg != defaultBg) {
                fillPaint.color = bg
                canvas.drawRect(left, top.toFloat(), right, (top + lineHeight).toFloat(), fillPaint)
            }
            val isCursor = cursorCol in col until end
            if (isCursor) {
                fillPaint.color = colors.cursor
                val cx = padding + cursorCol * cellWidth
                when {
                    !hasFocus() -> {
                        fillPaint.style = Paint.Style.STROKE
                        fillPaint.strokeWidth = density
                        canvas.drawRect(cx + density / 2, top + density / 2, cx + cellWidth - density / 2, top + lineHeight - density / 2, fillPaint)
                        fillPaint.style = Paint.Style.FILL
                    }
                    cursorShape == CursorShape.BLOCK -> {
                        canvas.drawRect(cx, top.toFloat(), cx + cellWidth, (top + lineHeight).toFloat(), fillPaint)
                        fg = colors.cursorText
                    }
                    cursorShape == CursorShape.UNDERLINE ->
                        canvas.drawRect(cx, top + lineHeight - 2 * density, cx + cellWidth, (top + lineHeight).toFloat(), fillPaint)
                    else -> canvas.drawRect(cx, top.toFloat(), cx + 2 * density, (top + lineHeight).toFloat(), fillPaint)
                }
            }
            if (attrs and CellStyle.INVISIBLE == 0) {
                textPaint.color = fg
                textPaint.typeface = when {
                    attrs and CellStyle.BOLD != 0 && attrs and CellStyle.ITALIC != 0 -> boldItalicTypeface
                    attrs and CellStyle.BOLD != 0 -> boldTypeface
                    attrs and CellStyle.ITALIC != 0 -> italicTypeface
                    else -> baseTypeface
                }
                textPaint.isUnderlineText = attrs and CellStyle.UNDERLINE != 0
                textPaint.isStrikeThruText = attrs and CellStyle.STRIKE != 0
                if (asciiOnly) {
                    var n = 0
                    for (i in col until end) {
                        val code = row.codes[i]
                        runChars[n++] = if (code == 0) ' ' else code.toChar()
                    }
                    var visible = textPaint.isUnderlineText || textPaint.isStrikeThruText
                    for (i in 0 until n) if (runChars[i] != ' ') { visible = true; break }
                    if (visible) {
                        canvas.drawText(runChars, 0, n, left, baseline, textPaint)
                    }
                } else {
                    for (i in col until end) {
                        val code = row.codes[i]
                        if (code == 0 || code == TerminalEmulator.WIDE_TAIL || code == ' '.code) continue
                        val text = String(Character.toChars(code))
                        val cells = if (i + 1 < row.size && row.codes[i + 1] == TerminalEmulator.WIDE_TAIL) 2 else 1
                        val x = padding + i * cellWidth
                        val glyphWidth = textPaint.measureText(text)
                        val room = cells * cellWidth
                        if (glyphWidth > room * 1.05f) {
                            // Fallback glyphs wider than their cells are squeezed so columns stay aligned.
                            canvas.save()
                            canvas.scale(room / glyphWidth, 1f, x, baseline)
                            canvas.drawText(text, x, baseline, textPaint)
                            canvas.restore()
                        } else {
                            canvas.drawText(text, x + (room - glyphWidth) / 2, baseline, textPaint)
                        }
                    }
                }
                textPaint.isUnderlineText = false
                textPaint.isStrikeThruText = false
            }
            col = end
        }
    }

    private fun drawScrollIndicator(canvas: Canvas, emu: TerminalEmulator) {
        val total = emu.historySize + emu.rows
        val trackHeight = height - 2 * padding
        val thumb = (trackHeight * emu.rows.toFloat() / total).coerceAtLeast(24 * density)
        val position = (emu.historySize - scrollOffset).toFloat() / emu.historySize.coerceAtLeast(1)
        val y = padding + (trackHeight - thumb) * position
        fillPaint.color = (colors.cursor and 0x00FFFFFF) or (0xAA shl 24)
        val x = width - 3 * density
        canvas.drawRoundRect(x - 3 * density, y, x, y + thumb, 2 * density, 2 * density, fillPaint)
    }

    // ------------------------------------------------------------ touch

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            showKeyboard()
            sendMouseClick(e)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaling) return true
            scrollRemainder += distanceY
            val steps = (scrollRemainder / lineHeight).toInt()
            if (steps != 0) {
                scrollRemainder -= steps * lineHeight
                scrollBy(steps, e2)
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onPasteRequest?.invoke()
        }
    })

    private var scaling = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { scaling = true; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            fontSizeSp *= detector.scaleFactor
            onFontSizeChange?.invoke(fontSizeSp)
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) { scaling = false }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) scrollRemainder = 0f
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER) && event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) scrollBy(if (v > 0) -3 else 3, event)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    /** Positive [steps] scroll towards newer output. */
    private fun scrollBy(steps: Int, event: MotionEvent) {
        val s = session ?: return
        val emu = s.emulator
        val (alt, mouse, appCursor) = synchronized(emu) { Triple(emu.isAltScreen, emu.mouseMode != MouseMode.NONE, emu.applicationCursorKeys) }
        when {
            mouse -> {
                val (col, row) = cellAt(event)
                val button = if (steps < 0) 64 else 65
                repeat(abs(steps)) { synchronized(emu) { emu.mouseReport(button, col, row, true) }?.let(s::write) }
            }
            alt -> {
                // Full-screen programs without mouse support get arrow keys, like a wheel in most terminals.
                val key = if (steps < 0) TermKey.UP else TermKey.DOWN
                repeat(abs(steps)) { s.write(KeyEncoder.key(key, KeyModifiers.NONE, appCursor)) }
            }
            else -> {
                val max = synchronized(emu) { emu.historySize }
                scrollOffset = (scrollOffset - steps).coerceIn(0, max)
                invalidate()
            }
        }
    }

    private fun cellAt(event: MotionEvent): Pair<Int, Int> {
        val col = ((event.x - padding) / cellWidth).toInt().coerceIn(0, (gridCols - 1).coerceAtLeast(0))
        val row = ((event.y - padding) / lineHeight).toInt().coerceIn(0, (gridRows - 1).coerceAtLeast(0))
        return col to row
    }

    private fun sendMouseClick(event: MotionEvent) {
        val s = session ?: return
        val (col, row) = cellAt(event)
        val emu = s.emulator
        val press = synchronized(emu) { emu.mouseReport(0, col, row, true) } ?: return
        val release = synchronized(emu) { emu.mouseReport(0, col, row, false) }
        s.write(press)
        release?.let(s::write)
    }

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val s = session
        if (s != null && synchronized(s.emulator) { s.emulator.focusReporting }) s.writeText(if (gainFocus) "\u001b[I" else "\u001b[O")
        invalidate()
    }

    // ------------------------------------------------------------ keyboard

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Visible-password text keeps keyboards from autocorrecting, predicting or
        // composing words, so each key reaches the program as soon as it is typed.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                flushEditable()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                flushEditable()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val content = editable
                if (content != null && content.isNotEmpty()) return super.deleteSurroundingText(beforeLength, afterLength)
                // Keyboards that delete through the connection instead of sending a key.
                repeat(beforeLength.coerceAtMost(64)) { sendKey(TermKey.BACKSPACE, KeyModifiers.NONE) }
                repeat(afterLength.coerceAtMost(64)) { sendKey(TermKey.DELETE, KeyModifiers.NONE) }
                return true
            }

            private fun flushEditable() {
                val content = editable ?: return
                val text = content.toString()
                content.clear()
                if (text.isEmpty()) return
                var i = 0
                while (i < text.length) {
                    val cp = text.codePointAt(i)
                    i += Character.charCount(cp)
                    when (cp) {
                        '\n'.code -> sendKey(TermKey.ENTER, KeyModifiers.NONE)
                        else -> sendChar(cp, KeyModifiers.NONE)
                    }
                }
            }
        }
    }

    private fun sendKey(key: TermKey, base: KeyModifiers) {
        val s = session ?: return
        val latched = latchedModifiers()
        val mods = KeyModifiers(base.ctrl || latched.ctrl, base.alt || latched.alt, base.shift || latched.shift)
        val appCursor = synchronized(s.emulator) { s.emulator.applicationCursorKeys }
        s.write(KeyEncoder.key(key, mods, appCursor))
        if (latched.any) onModifiersUsed()
        jumpToBottom()
    }

    private fun sendChar(codePoint: Int, base: KeyModifiers) {
        val s = session ?: return
        val latched = latchedModifiers()
        val mods = KeyModifiers(base.ctrl || latched.ctrl, base.alt || latched.alt, false)
        s.write(KeyEncoder.char(codePoint, mods))
        if (latched.any) onModifiersUsed()
        jumpToBottom()
    }

    private fun jumpToBottom() {
        if (scrollOffset != 0) {
            scrollOffset = 0
            invalidate()
        }
    }

    /** Sends a key from the extra-keys row. */
    fun pressKey(key: TermKey) = sendKey(key, KeyModifiers.NONE)

    /** Sends a character from the extra-keys row, honouring latched Ctrl and Alt. */
    fun pressChar(codePoint: Int) = sendChar(codePoint, KeyModifiers.NONE)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (session == null) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER -> return super.onKeyDown(keyCode, event)
        }
        if (event.isCtrlPressed && event.isShiftPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_V -> { onPasteRequest?.invoke(); return true }
                KeyEvent.KEYCODE_C -> { onCopyRequest?.invoke(); return true }
            }
        }
        val mods = KeyModifiers(event.isCtrlPressed, event.isAltPressed, event.isShiftPressed)
        val key = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> TermKey.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> TermKey.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> TermKey.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> TermKey.RIGHT
            KeyEvent.KEYCODE_MOVE_HOME -> TermKey.HOME
            KeyEvent.KEYCODE_MOVE_END -> TermKey.END
            KeyEvent.KEYCODE_PAGE_UP -> TermKey.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> TermKey.PAGE_DOWN
            KeyEvent.KEYCODE_INSERT -> TermKey.INSERT
            KeyEvent.KEYCODE_FORWARD_DEL -> TermKey.DELETE
            KeyEvent.KEYCODE_DEL -> TermKey.BACKSPACE
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> TermKey.ENTER
            KeyEvent.KEYCODE_TAB -> TermKey.TAB
            KeyEvent.KEYCODE_ESCAPE -> TermKey.ESCAPE
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> TermKey.entries[TermKey.F1.ordinal + (keyCode - KeyEvent.KEYCODE_F1)]
            else -> null
        }
        if (key != null) {
            sendKey(key, mods)
            return true
        }
        // Ctrl and Alt are applied by the encoder, so ask for the plain character.
        val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv()
        val unicode = event.getUnicodeChar(meta)
        if (unicode == 0 || unicode and KeyCharacterMap.COMBINING_ACCENT != 0) return super.onKeyDown(keyCode, event)
        sendChar(unicode, mods)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (session == null) return super.onKeyUp(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> super.onKeyUp(keyCode, event)
            else -> true
        }
    }

    companion object {
        const val MIN_FONT_SP = 7f
        const val MAX_FONT_SP = 30f
    }
}
