package dev.warpterminal.mobile

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/** Delays the first touch until a tap, drag or two-finger gesture owns it. */
internal class GraphicsTouch(
    private val view: View,
    private val touch: (action: Int, id: Int, x: Float, y: Float, time: Long) -> Unit,
    private val point: (x: Float, y: Float, time: Long) -> Unit,
    private val axis: (vertical: Float, horizontal: Float, stop: Boolean, time: Long) -> Unit,
    private val contextClick: (x: Float, y: Float, time: Long) -> Unit,
) {
    private enum class Mode { IDLE, TAP, DRAG, PAIR, SCROLL, CONSUMED }
    private var mode = Mode.IDLE
    val active get() = mode != Mode.IDLE
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    // The compositor takes Android detents and forwards 15 logical pixels per detent.
    private val detent = 15f * view.resources.displayMetrics.density
    private var first = 0
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var centerX = 0f
    private var centerY = 0f
    private var span = 0f
    private var pairTap = false
    private val longPress = Runnable {
        if (mode == Mode.TAP) {
            mode = Mode.CONSUMED
            contextClick(downX, downY, SystemClock.uptimeMillis())
        }
    }

    fun cancel(time: Long = SystemClock.uptimeMillis()) {
        view.removeCallbacks(longPress)
        if (mode == Mode.SCROLL) axis(0f, 0f, true, time)
        if (mode == Mode.DRAG) touch(MotionEvent.ACTION_UP, first, downX, downY, time)
        mode = Mode.IDLE
    }

    fun handle(e: MotionEvent) {
        val time = e.eventTime
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel(time)
                first = e.getPointerId(0)
                downTime = time
                downX = e.x
                downY = e.y
                mode = Mode.TAP
                view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                view.removeCallbacks(longPress)
                if (mode == Mode.TAP && e.pointerCount == 2) {
                    centerX = (e.x + e.getX(1)) / 2
                    centerY = (e.y + e.getY(1)) / 2
                    span = hypot(e.x - e.getX(1), e.y - e.getY(1))
                    pairTap = true
                    mode = Mode.PAIR
                } else if (mode == Mode.PAIR || mode == Mode.SCROLL) {
                    cancel(time)
                    mode = Mode.CONSUMED
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.PAIR || mode == Mode.SCROLL) {
                    if (e.pointerCount != 2) return
                    val x = (e.x + e.getX(1)) / 2
                    val y = (e.y + e.getY(1)) / 2
                    val dx = x - centerX
                    val dy = y - centerY
                    if (kotlin.math.abs(hypot(e.x - e.getX(1), e.y - e.getY(1)) - span) > slop) pairTap = false
                    if (mode == Mode.PAIR && hypot(dx, dy) <= slop) return
                    if (mode == Mode.PAIR) point(centerX, centerY, time)
                    mode = Mode.SCROLL
                    // Follow the fingers. The JNI bridge flips the vertical axis.
                    axis(dy / detent, -dx / detent, false, time)
                    centerX = x
                    centerY = y
                } else if (mode == Mode.TAP || mode == Mode.DRAG) {
                    val index = e.findPointerIndex(first)
                    if (index < 0) return
                    val x = e.getX(index)
                    val y = e.getY(index)
                    if (mode == Mode.TAP) {
                        if (hypot(x - downX, y - downY) <= slop) return
                        view.removeCallbacks(longPress)
                        touch(MotionEvent.ACTION_DOWN, first, downX, downY, downTime)
                        mode = Mode.DRAG
                    }
                    touch(MotionEvent.ACTION_MOVE, first, x, y, time)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                view.removeCallbacks(longPress)
                when (mode) {
                    Mode.TAP -> {
                        touch(MotionEvent.ACTION_DOWN, first, downX, downY, downTime)
                        touch(MotionEvent.ACTION_UP, first, e.x, e.y, time)
                    }
                    Mode.DRAG -> {
                        if (e.getPointerId(e.actionIndex) != first) return
                        touch(MotionEvent.ACTION_UP, first, e.getX(e.actionIndex), e.getY(e.actionIndex), time)
                    }
                    Mode.PAIR -> if (pairTap && time - downTime <= ViewConfiguration.getDoubleTapTimeout()) {
                        contextClick(centerX, centerY, time)
                    }
                    Mode.SCROLL -> axis(0f, 0f, true, time)
                    else -> Unit
                }
                mode = if (e.actionMasked == MotionEvent.ACTION_UP) Mode.IDLE else Mode.CONSUMED
            }
            MotionEvent.ACTION_CANCEL -> cancel(time)
        }
    }
}
