package dev.warpterminal.mobile

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import me.phie.tawc.compositor.NativeBridge
import me.phie.tawc.compositor.TawcInputConnection

internal class GraphicsView(context: Context, val windowId: String) : SurfaceView(context), SurfaceHolder.Callback {
    private var ready = false
    private var input: TawcInputConnection? = null
    private var inputType = EditorInfo.TYPE_CLASS_TEXT
    private var inputOptions = 0
    private var buttons = 0

    init {
        contentDescription = "Linux 图形窗口"
        isFocusableInTouchMode = true
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (ready) {
            NativeBridge.nativeOnActivitySurfaceChanged(windowId, width, height)
        } else {
            ready = true
            NativeBridge.attach(this)
            NativeBridge.nativeRegisterActivitySurface(windowId, holder.surface, width, height)
            updateFocus()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) { releaseSurface() }

    override fun onDetachedFromWindow() {
        releaseSurface()
        super.onDetachedFromWindow()
    }

    private fun releaseSurface() {
        if (ready && NativeBridge.isAttached(this)) {
            hideKeyboard()
            NativeBridge.nativeOnActivityFocusChanged(windowId, false)
            NativeBridge.nativeOnActivitySurfaceDestroyed(windowId)
            NativeBridge.detach(this)
        }
        input?.closeConnection()
        input = null
        ready = false
        buttons = 0
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        updateFocus()
    }

    private fun updateFocus() {
        if (ready && NativeBridge.isAttached(this)) {
            val focused = hasWindowFocus()
            NativeBridge.nativeOnActivityFocusChanged(windowId, focused)
            if (focused) requestFocus()
        }
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(attrs: EditorInfo): InputConnection {
        attrs.inputType = inputType
        attrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE or inputOptions
        return TawcInputConnection(this).also { input = it }
    }

    fun setInputType(type: Int, options: Int) {
        if (type == inputType && options == inputOptions) return
        inputType = type
        inputOptions = options
        if (hasWindowFocus()) NativeBridge.imeOutput.restartInput(this)
    }

    fun updateEditable(text: String, start: Int, end: Int, authoritative: Boolean) {
        if (hasWindowFocus() && input === NativeBridge.activeInputConnection) {
            input?.updateFromCompositor(text, start, end, authoritative)
        }
    }

    fun showKeyboard() {
        if (hasWindowFocus()) {
            requestFocus()
            NativeBridge.imeOutput.showSoftInput(this)
        }
    }

    fun hideKeyboard() { NativeBridge.imeOutput.hideSoftInput(this) }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (ready && event.keyCode != KeyEvent.KEYCODE_BACK && (down || event.action == KeyEvent.ACTION_UP) &&
            NativeBridge.nativeOnHardwareKeyEvent(windowId, event.keyCode, down, event.repeatCount)
        ) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!ready) return false
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return pointer(event)
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) requestFocus()
        val indices = when (action) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL -> 0 until event.pointerCount
            else -> event.actionIndex..event.actionIndex
        }
        for (i in indices) {
            NativeBridge.nativeOnTouchEvent(windowId,
                if (action == MotionEvent.ACTION_CANCEL) MotionEvent.ACTION_UP else action,
                event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
        }
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean =
        if (ready && event.isFromSource(InputDevice.SOURCE_MOUSE)) pointer(event) else super.onHoverEvent(event)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        if (ready && event.isFromSource(InputDevice.SOURCE_MOUSE)) pointer(event) else super.onGenericMotionEvent(event)

    private fun pointer(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) return true
        NativeBridge.nativeOnMouseAttachedChanged(true)
        NativeBridge.nativeOnPointerEvent(windowId, 0, event.x, event.y, 0, false, 0f, 0f, false, false, event.eventTime)
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            NativeBridge.nativeOnPointerEvent(windowId, 2, event.x, event.y, 0, false,
                event.getAxisValue(MotionEvent.AXIS_VSCROLL), event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                false, false, event.eventTime)
            return true
        }
        val next = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> event.buttonState.takeUnless { it == 0 } ?: MotionEvent.BUTTON_PRIMARY
            MotionEvent.ACTION_CANCEL -> 0
            else -> event.buttonState
        }
        for (bit in 0..4) {
            val button = 1 shl bit
            if ((buttons xor next) and button != 0) {
                NativeBridge.nativeOnPointerEvent(windowId, 1, event.x, event.y, button,
                    next and button != 0, 0f, 0f, false, false, event.eventTime)
            }
        }
        buttons = next
        return true
    }

    fun updatePointer(shape: String) {
        val icon = when (shape) {
            "" -> PointerIcon.TYPE_NULL
            "text" -> PointerIcon.TYPE_TEXT
            "pointer" -> PointerIcon.TYPE_HAND
            "crosshair" -> PointerIcon.TYPE_CROSSHAIR
            "wait" -> PointerIcon.TYPE_WAIT
            else -> PointerIcon.TYPE_ARROW
        }
        pointerIcon = PointerIcon.getSystemIcon(context, icon)
    }
}
