@file:Suppress("UNUSED_PARAMETER")

package me.phie.tawc.compositor

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PointerIcon
import android.view.Surface
import dev.warpterminal.mobile.GraphicsSessions
import dev.warpterminal.mobile.GraphicsView

// The package and callback signatures are the JNI ABI of the locked TAWC compositor.
internal object NativeBridge {
    init { System.loadLibrary("compositor") }

    private val main = Handler(Looper.getMainLooper())
    private val views = mutableMapOf<String, GraphicsView>()
    var sessions: GraphicsSessions? = null
    var activeInputConnection: TawcInputConnection? = null
    val imeOutput: ImeOutput = RealImeOutput

    external fun nativeStartActivation(xwayland: Boolean)
    external fun nativeStartCompositor(scale: Float, width: Int, height: Int, xwayland: Boolean, pointer: Int): Boolean
    external fun nativeStopCompositor()
    external fun nativeIsCompositorRunning(): Boolean
    external fun nativeQueryState(): String?
    external fun nativeSetTintBuffersByType(tint: Boolean)
    external fun nativeScanWarpApplications(root: String, home: String): String?
    external fun nativeReserveLaunchHost(id: String, desktopId: String, pointer: Int): String?
    external fun nativeUpdateLaunch(id: String, sid: Int, exited: Boolean)
    external fun nativeReleaseLaunchHost(id: String)
    external fun nativeRegisterActivitySurface(id: String, surface: Surface, width: Int, height: Int)
    external fun nativeOnActivitySurfaceChanged(id: String, width: Int, height: Int)
    external fun nativeOnActivitySurfaceDestroyed(id: String)
    external fun nativeOnActivityFocusChanged(id: String, focused: Boolean)
    external fun nativeRequestWindowClose(id: String)
    external fun nativeOnTouchEvent(id: String, action: Int, pointerId: Int, x: Float, y: Float, time: Long)
    external fun nativeOnPointerEvent(id: String, kind: Int, x: Float, y: Float, button: Int, pressed: Boolean,
                                      vscroll: Float, hscroll: Float, fromTouchpad: Boolean, stop: Boolean, time: Long)
    external fun nativeOnMouseAttachedChanged(attached: Boolean)
    external fun nativeOnHardwareKeyEvent(id: String, code: Int, pressed: Boolean, repeats: Int): Boolean
    external fun nativeCommitText(text: String, deleteBefore: Int, deleteAfter: Int)
    external fun nativeSetComposingText(text: String, deleteBefore: Int, deleteAfter: Int)
    external fun nativeFinishComposingText()
    external fun nativeSendKeyEvent(code: Int)
    external fun nativeSendKeyState(code: Int, pressed: Boolean)

    fun attach(view: GraphicsView) { views[view.windowId] = view }
    fun detach(view: GraphicsView) { views.remove(view.windowId, view) }
    fun isAttached(view: GraphicsView) = views[view.windowId] === view
    fun restoreFocusedView() {
        views.values.firstOrNull { it.hasWindowFocus() }?.let {
            nativeOnActivityFocusChanged(it.windowId, true)
        }
    }
    private fun dispatch(block: GraphicsSessions.() -> Unit) = main.post { sessions?.block() }
    private fun view(id: String, block: GraphicsView.() -> Unit) = main.post { views[id]?.block() }

    @JvmStatic fun onActivationRequested() { dispatch { activate() } }
    @JvmStatic fun onCompositorStopped() { dispatch { compositorStopped() } }
    @JvmStatic fun spawnActivity(id: String) { dispatch { windowOpened(id) } }
    @JvmStatic fun activateActivity(id: String) { dispatch { selectWindow(id) } }
    @JvmStatic fun finishActivity(id: String) { dispatch { windowClosed(id) } }
    @JvmStatic fun onLaunchMatched(id: String) { dispatch { launchMatched(id) } }
    @JvmStatic fun onLaunchShown(id: String) { dispatch { firstFrame(id) } }
    @JvmStatic fun onToplevelCountChanged(count: Int) = Unit
    @JvmStatic fun setActivityFullscreen(id: String, fullscreen: Boolean) = Unit
    @JvmStatic fun updateWindowMetadata(id: String, title: String, appId: String,
                                        desktopId: String, desktopName: String, icon: String) {
        dispatch { titleChanged(id, title.ifBlank { desktopName.ifBlank { appId } }) }
    }
    @JvmStatic fun onShowKeyboard(id: String) { view(id) { showKeyboard() } }
    @JvmStatic fun onHideKeyboard(id: String) { view(id) { hideKeyboard() } }
    @JvmStatic fun onContentTypeChanged(id: String, type: Int, options: Int) {
        view(id) { setInputType(type, options) }
    }
    @JvmStatic fun onUpdateEditableText(id: String, text: String, start: Int, end: Int, authoritative: Boolean) {
        view(id) { updateEditable(text, start, end, authoritative) }
    }
    @JvmStatic fun setPointerIcon(id: String, shape: String) {
        view(id) { updatePointer(shape) }
    }
    @JvmStatic fun setPointerIconBitmap(id: String, pixels: IntArray, width: Int, height: Int, x: Int, y: Int) {
        view(id) {
            if (width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong() &&
                x in 0 until width && y in 0 until height
            ) pointerIcon = PointerIcon.create(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888),
                x.toFloat(), y.toFloat())
        }
    }

    // Linux selections are kept within the compositor.
    @JvmStatic fun fetchClipboardText(): String? = null
    @JvmStatic fun onSetAndroidClipboardText(text: String) = Unit
    @JvmStatic fun focusedTerminalTty(): Int = -1
}
