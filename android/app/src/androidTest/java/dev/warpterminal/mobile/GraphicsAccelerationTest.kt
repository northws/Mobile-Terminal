package dev.warpterminal.mobile

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GraphicsAccelerationTest {
    @Test fun failedProbeAndChromiumUpgradeFallBackIndependently() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = "graphics-acceleration-test"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences(preferences, mode)
        }
        target.deleteSharedPreferences(preferences)
        val chromium = File.createTempFile("gpu-policy-", ".bin", target.cacheDir)
        try {
            val acceleration = GraphicsAcceleration(context)
            acceleration.checkChromiumVersion(chromium)
            acceleration.record(GpuPath.WAYLAND, GpuResult(true, "Adreno"))
            acceleration.record(GpuPath.X11, GpuResult(false, "EGL timeout"))
            acceleration.record(GpuPath.CHROMIUM, GpuResult(true, "Adreno", ChromiumBackend.WAYLAND))
            assertEquals(GpuPath.WAYLAND, acceleration.backend(GpuPath.WAYLAND))
            assertNull(acceleration.backend(GpuPath.X11))
            assertEquals(ChromiumBackend.WAYLAND, acceleration.chromiumBackend(chromium))

            val restored = GraphicsAcceleration(context)
            assertEquals(ChromiumBackend.WAYLAND, restored.chromiumBackend(chromium))
            assertFalse(restored.canProbeChromium(ChromiumBackend.WAYLAND_BGRA))
            restored.record(GpuPath.WAYLAND, GpuResult(true, "Adreno (TM) 840", bgraTextureVerified = true))
            assertTrue(restored.canProbeChromium(ChromiumBackend.WAYLAND_BGRA))
            assertFalse(restored.canProbeChromium(ChromiumBackend.XWAYLAND_BGRA))
            restored.record(GpuPath.CHROMIUM, GpuResult(true, "Adreno", ChromiumBackend.WAYLAND_BGRA))
            assertEquals(ChromiumBackend.WAYLAND_BGRA, GraphicsAcceleration(context).chromiumBackend(chromium))
            assertNull(restored.chromiumBackend(chromium, ChromiumRendering.SOFTWARE))
            restored.record(GpuPath.WAYLAND, GpuResult(true, "Adreno", bgraTextureVerified = false))
            assertNull(restored.chromiumBackend(chromium))
            restored.record(GpuPath.WAYLAND, GpuResult(false, "EGL timeout"))
            assertNull(restored.chromiumBackend(chromium))
            restored.record(GpuPath.X11, GpuResult(true, "Adreno"))
            restored.record(GpuPath.CHROMIUM, GpuResult(true, "Adreno", ChromiumBackend.XWAYLAND))
            assertEquals(ChromiumBackend.XWAYLAND, GraphicsAcceleration(context).chromiumBackend(chromium))
            restored.record(GpuPath.CHROMIUM, GpuResult(true, "Adreno", ChromiumBackend.XWAYLAND, webGl2Only = true))
            val partial = GraphicsAcceleration(context)
            assertNull(partial.chromiumBackend(chromium))
            assertNull(partial.chromiumBackend(chromium, ChromiumRendering.SOFTWARE))
            assertEquals(ChromiumBackend.XWAYLAND, partial.chromiumBackend(chromium, ChromiumRendering.WEBGL2))
            chromium.writeText("changed")
            assertNull(partial.chromiumBackend(chromium, ChromiumRendering.WEBGL2))
            assertEquals(GpuPath.X11, restored.backend(GpuPath.X11))

            restored.setAutomaticMode(false)
            assertNull(restored.backend(GpuPath.X11))
            assertNull(GraphicsAcceleration(context).backend(GpuPath.X11))
        } finally {
            chromium.delete()
            target.deleteSharedPreferences(preferences)
        }
    }
}
