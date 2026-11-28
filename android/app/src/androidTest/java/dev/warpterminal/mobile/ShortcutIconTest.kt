package dev.warpterminal.mobile

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShortcutIconTest {
    @Test fun resolvesDesktopIconForPinAndFallsBackWhenUnavailable() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val fixture = Files.createTempDirectory(cache.toPath(), "shortcut-icon-").toFile()
        try {
            val root = File(fixture, "rootfs").apply { mkdir() }
            val home = File(fixture, "home").apply { mkdir() }
            val icon = File(root, "usr/share/icons/hicolor/128x128/apps/test-browser.png")
            icon.parentFile!!.mkdirs()
            val image = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
            icon.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val desktop = File(home, ".local/share/applications/browser.desktop")
            desktop.parentFile!!.mkdirs()
            val app = LinuxApp("browser", "Browser", "$DEBIAN_HOME/.local/share/applications/browser.desktop")
            val icons = ShortcutIcon(root, home)
            fun entry(value: String) = desktop.writeText("[Desktop Entry]\nIcon=$value\n[Desktop Action New]\nIcon=wrong\n")
            entry("test-browser")
            val resolved = checkNotNull(icons.load(app, null, 48))
            assertEquals(48, resolved.width)
            assertEquals(24, resolved.height)
            assertEquals(Color.CYAN, resolved.getPixel(24, 12))

            icon.copyTo(File(home, "我的 图标.png"))
            entry("/home/warp/我的 图标.png")
            assertEquals(Color.CYAN, icons.load(app, "/nonexistent/icon.png", 48)?.getPixel(12, 12))
            entry("missing")
            assertNull(icons.load(app, null, 48))
            assertEquals(Color.CYAN, icons.load(null, icon.path, 48)?.getPixel(12, 12))
            val outside = icon.copyTo(File(fixture, "outside.png"))
            assertNull(icons.load(null, outside.path, 48))
            icon.writeText("invalid")
            assertNull(icons.load(null, icon.path, 48))
        } finally {
            fixture.deleteRecursively()
        }
    }
}
