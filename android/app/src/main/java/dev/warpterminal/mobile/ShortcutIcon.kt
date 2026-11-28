package dev.warpterminal.mobile

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File
import java.io.IOException
import me.phie.tawc.compositor.NativeBridge

/** Resolves Debian application icons for the launcher and pinned shortcuts. Call on an I/O dispatcher. */
internal class ShortcutIcon(rootfs: File, homeDir: File) {
    private val root = rootfs.canonicalFile
    private val home = homeDir.canonicalFile
    private val iconCache = File(root.parentFile, "icon-cache")

    fun load(app: LinuxApp?, windowIcon: String?, maxSize: Int): Bitmap? = try {
        windowIcon?.let { decode(it, maxSize) } ?: app?.desktopFile?.let { path ->
            val (base, relative) = guestPath(path)
            val desktop = File(base, relative).canonicalFile
            if (!desktop.toPath().startsWith(base.toPath()) || !desktop.isFile ||
                desktop.length() > 1024 * 1024) return@let null
            val value = desktop.bufferedReader().useLines { lines ->
                lines.map(String::trim).dropWhile { it != "[Desktop Entry]" }.drop(1)
                    .takeWhile { !it.startsWith('[') }
                    .firstOrNull { it.substringBefore('=').trim() == "Icon" && '=' in it }
                    ?.substringAfter('=')?.trim()?.replace("\\s", " ")
            }?.takeIf { it.isNotBlank() } ?: return@let null
            val (iconRoot, iconName) = guestPath(value)
            val icon = if (value.startsWith('/')) "/$iconName" else value
            if (!value.startsWith('/') && '/' in value) return@let null
            NativeBridge.nativeResolveIcon(iconRoot.path, icon)?.let { decode(it, maxSize) }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun guestPath(path: String): Pair<File, String> {
        for (prefix in listOf(DEBIAN_HOME, LEGACY_DEBIAN_HOME)) {
            if (path.startsWith("$prefix/")) return home to path.removePrefix("$prefix/")
        }
        return root to path.removePrefix("/")
    }

    private fun decode(path: String, maxSize: Int): Bitmap? {
        if (path.isBlank()) return null
        val file = File(path).canonicalFile
        if (listOf(root, home, iconCache).none { file.toPath().startsWith(it.toPath()) } ||
            !file.isFile || file.length() !in 1..8_388_608L) return null
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                val scale = (maxSize.coerceIn(1, 256).toDouble() / maxOf(width, height)).coerceAtMost(1.0)
                decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (_: IOException) {
            null
        }
    }
}
