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
            acceleration.record(GpuPath.CHROMIUM, GpuResult(true, "Adreno"))
            assertEquals(GpuPath.WAYLAND, acceleration.backend(GpuPath.WAYLAND))
            assertNull(acceleration.backend(GpuPath.X11))
            assertTrue(acceleration.chromiumReady(chromium))

            val restored = GraphicsAcceleration(context)
            assertTrue(restored.chromiumReady(chromium))
            chromium.writeText("changed")
            assertFalse(restored.chromiumReady(chromium))
            assertEquals(GpuPath.WAYLAND, restored.backend(GpuPath.WAYLAND))

            restored.setAutomaticMode(false)
            assertNull(restored.backend(GpuPath.WAYLAND))
            assertNull(GraphicsAcceleration(context).backend(GpuPath.WAYLAND))
        } finally {
            chromium.delete()
            target.deleteSharedPreferences(preferences)
        }
    }
}
