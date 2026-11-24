package dev.warpterminal.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.graphics.withClip
import com.termux.terminal.KeyHandler
import com.termux.terminal.TextStyle
import com.termux.view.TerminalRenderer

internal class TerminalGrid(context: Context, private val tab: TerminalTab) : View(context) {
    private val renderer = renderer(context)
    private var topRow = 0
    private var scrollDistance = 0f
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            showKeyboard()
            return performClick()
        }
        override fun onScroll(first: MotionEvent?, second: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!tab.emulator.isAlternateBufferActive) {
                scrollDistance += dy
                val rows = (scrollDistance / renderer.fontLineSpacing).toInt()
                if (rows != 0) {
                    scrollDistance -= rows * renderer.fontLineSpacing
                    topRow = (topRow + rows).coerceIn(-tab.emulator.screen.activeTranscriptRows, 0)
                    invalidate()
                }
            }
            return true
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = "Linux 终端网格"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onCheckIsTextEditor() = true

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
    }

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return object : BaseInputConnection(this, true) {
            private val composing = SpannableStringBuilder().apply { Selection.setSelection(this, 0) }

            override fun getEditable(): Editable = composing

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                sendText(text?.toString() ?: "")
                clearComposition()
                return true
            }

            override fun finishComposingText(): Boolean {
                sendText(composing.toString())
                clearComposition()
                return super.finishComposingText()
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (composing.isNotEmpty()) return super.deleteSurroundingText(beforeLength, afterLength)
                repeat(beforeLength.coerceIn(0, 32)) { tab.key(KeyEvent.KEYCODE_DEL) }
                repeat(afterLength.coerceIn(0, 32)) { tab.key(KeyEvent.KEYCODE_FORWARD_DEL) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean = dispatchKeyEvent(event)

            private fun clearComposition() {
                removeComposingSpans(composing)
                composing.clear()
                Selection.setSelection(composing, 0)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        var mode = 0
        if (event.isCtrlPressed) mode = mode or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed) mode = mode or KeyHandler.KEYMOD_ALT
        if (event.isShiftPressed) mode = mode or KeyHandler.KEYMOD_SHIFT
        if (tab.key(keyCode, mode)) return true
        val character = event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv())
        if (character <= 0 || !Character.isValidCodePoint(character)) return super.onKeyDown(keyCode, event)
        if (event.isAltPressed) tab.input(byteArrayOf(27))
        if (event.isCtrlPressed) tab.control()
        sendText(String(Character.toChars(character)))
        return true
    }

    private fun sendText(text: String) {
        topRow = 0
        if (text.isNotEmpty()) tab.text(text.replace('\n', '\r'))
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        resize(tab, renderer, width, height)
        topRow = 0
    }

    override fun onDraw(canvas: Canvas) {
        val emulator = tab.emulator
        if (emulator.isAlternateBufferActive) topRow = 0
        topRow = topRow.coerceIn(-emulator.screen.activeTranscriptRows, 0)
        canvas.withClip(0, 0, width, height) {
            drawColor(emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
            renderer.render(emulator, this, topRow, -1, -1, -1, -1)
        }
    }

    companion object {
        fun renderer(context: Context) = TerminalRenderer(
            (14 * context.resources.displayMetrics.scaledDensity).toInt(), Typeface.MONOSPACE,
        )

        fun resize(tab: TerminalTab, renderer: TerminalRenderer, width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val cellWidth = renderer.fontWidth.coerceAtLeast(1f)
            val cellHeight = renderer.fontLineSpacing.coerceAtLeast(1)
            tab.resize((width / cellWidth).toInt().coerceIn(4, 500), (height / cellHeight).coerceIn(4, 250),
                cellWidth.toInt(), cellHeight)
        }
    }
}
