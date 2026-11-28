package dev.warpterminal.mobile

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import me.phie.tawc.compositor.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class GraphicsTouchTest {
    @Test fun fingerGesturesReachLocalWaylandPage() = exerciseGestures("wayland")
    @Test fun fingerGesturesReachLocalX11Page() = exerciseGestures("x11")

    private fun exerciseGestures(platform: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val linux = LinuxEnvironment(context)
        assumeTrue(linux.installed() && linux.chromiumBinary.canExecute() &&
            File(linux.desktopRoot, "usr/bin/gio").canExecute())
        val dir = Files.createTempDirectory(File(linux.desktopRoot, "tmp").toPath(), "mt-gesture-").toFile()
        val path = "/tmp/" + dir.name
        val id = dir.name
        File(dir, "test.html").writeText("""
            <!doctype html><meta charset=utf-8><style>body{width:500vw;height:500vh;touch-action:none}</style>
            <script>
            let clicks=0, menus=0, drags=0;
            function report(){document.title='mt-gesture '+[Math.round(scrollX),Math.round(scrollY),clicks,menus,drags].join(',')}
            onpointermove=e=>{if(e.buttons&1){drags++;report()}};
            onscroll=report; onload=report; onclick=()=>{clicks++;report()};
            oncontextmenu=e=>{e.preventDefault();menus++;report()};
            </script>
        """.trimIndent())
        File(dir, "$id.desktop").writeText("""
            [Desktop Entry]
            Type=Application
            Name=Gesture test
            Exec=/usr/bin/chromium --class=$id --ozone-platform=$platform --disable-gpu --no-sandbox --disable-background-networking --user-data-dir=$path/profile --no-first-run file://$path/test.html
        """.trimIndent())
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                context.startForegroundService(Intent(context, TerminalService::class.java)
                    .setAction(TerminalService.ADD_GRAPHICS).putExtra(TerminalService.APP_ID, id)
                    .putExtra(TerminalService.APP_NAME, "Gesture test")
                    .putExtra(TerminalService.APP_FILE, "$path/$id.desktop")
                    .putExtra(TerminalService.CHROMIUM_RENDERING, ChromiumRendering.SOFTWARE.name))
                fun awaitPage(match: (List<Int>) -> Boolean): List<Int> {
                    val deadline = SystemClock.elapsedRealtime() + 25_000
                    var title = ""
                    while (SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity {
                            title = NativeBridge.sessions?.tabs?.firstOrNull { tab -> "mt-gesture " in tab.title }?.title.orEmpty()
                        }
                        val values = Regex("""mt-gesture (\d+),(\d+),(\d+),(\d+),(\d+)""").find(title)
                            ?.groupValues?.drop(1)?.map(String::toInt)
                        if (values != null && match(values)) return values
                        Thread.sleep(80)
                    }
                    error("Timed out waiting for guest gesture counters. Last title: $title")
                }
                var down = 0L
                fun finger(action: Int, vararg positions: Pair<Float, Float>) {
                    scenario.onActivity { activity ->
                        val view = requireNotNull(findSurface(activity.window.decorView))
                        val time = SystemClock.uptimeMillis()
                        if (action == MotionEvent.ACTION_DOWN) down = time
                        val props = Array(positions.size) { i -> MotionEvent.PointerProperties().apply {
                            this.id = i; toolType = MotionEvent.TOOL_TYPE_FINGER
                        } }
                        val coords = Array(positions.size) { i -> MotionEvent.PointerCoords().apply {
                            x = positions[i].first * view.width; y = positions[i].second * view.height
                            pressure = 1f; size = .1f
                        } }
                        val e = MotionEvent.obtain(down, time, action, positions.size, props, coords, 0, 0,
                            1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                        try { assertTrue(view.dispatchTouchEvent(e)) } finally { e.recycle() }
                    }
                }
                val first = .50f to .65f
                val second = .60f to .65f
                val secondDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                val secondUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                try {
                    awaitPage { it == listOf(0, 0, 0, 0, 0) }
                    // Wait for the first Android surface layout after the title arrives.
                    scenario.onActivity { check(findSurface(it.window.decorView)?.isLaidOut == true) }
                    finger(MotionEvent.ACTION_DOWN, first)
                    finger(secondDown, first, second)
                    for (step in 1..5) {
                        val d = step * .035f
                        finger(MotionEvent.ACTION_MOVE, first.first-d to first.second-d, second.first-d to second.second-d)
                        Thread.sleep(20)
                    }
                    finger(secondUp, .325f to .475f, .425f to .475f)
                    finger(MotionEvent.ACTION_UP, .325f to .475f)
                    val scrolled = awaitPage { it[0] > 0 && it[1] > 0 }
                    assertEquals(listOf(0, 0, 0), scrolled.drop(2))
                    finger(MotionEvent.ACTION_DOWN, first)
                    finger(secondDown, first, second)
                    finger(secondUp, first, second)
                    finger(MotionEvent.ACTION_UP, first)
                    awaitPage { it.drop(2) == listOf(0, 1, 0) }
                    finger(MotionEvent.ACTION_DOWN, first)
                    finger(MotionEvent.ACTION_UP, first)
                    awaitPage { it.drop(2) == listOf(1, 1, 0) }
                    finger(MotionEvent.ACTION_DOWN, first)
                    Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 150L)
                    finger(MotionEvent.ACTION_UP, first)
                    awaitPage { it.drop(2) == listOf(1, 2, 0) }
                    finger(MotionEvent.ACTION_DOWN, first)
                    finger(MotionEvent.ACTION_MOVE, .4f to .6f)
                    finger(MotionEvent.ACTION_MOVE, .35f to .6f)
                    finger(MotionEvent.ACTION_UP, .35f to .6f)
                    val final = awaitPage { it[4] > 0 }
                    android.util.Log.i("GestureTest", "$platform scroll=${scrolled.take(2)} dragMoves=${final[4]}")
                } finally {
                    scenario.onActivity {
                        NativeBridge.sessions?.let { sessions ->
                            sessions.tabs.firstOrNull { t -> t.desktopId == id || t.title.startsWith("mt-gesture ") }
                                ?.let(sessions::close)
                        }
                    }
                }
            }
        } finally {
            Files.walk(dir.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun findSurface(view: View): GraphicsView? {
        if (view is GraphicsView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findSurface(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}
