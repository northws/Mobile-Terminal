package dev.warpterminal.mobile

import android.content.Context
import android.os.Build
import android.system.Os
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

internal enum class GpuPath(val platform: String, val label: String) {
    WAYLAND("wayland", "Wayland"),
    X11("x11", "X11"),
    CHROMIUM("chromium", "Chromium WebGL"),
}

internal enum class ChromiumBackend(
    val display: GpuPath,
    val nativeBgra: Boolean = false,
    val gpuCompositing: Boolean = false,
) {
    WAYLAND(GpuPath.WAYLAND),
    WAYLAND_BGRA(GpuPath.WAYLAND, true),
    XWAYLAND(GpuPath.X11),
    XWAYLAND_BGRA(GpuPath.X11, true),
    XWAYLAND_COMPOSITING_BGRA(GpuPath.X11, nativeBgra = true, gpuCompositing = true),
    XWAYLAND_COMPOSITING(GpuPath.X11, gpuCompositing = true),
}

internal enum class ChromiumRendering { AUTOMATIC, SOFTWARE, WEBGL2, SOFTWARE_COMPOSITING }

internal data class GpuResult(
    val hardware: Boolean,
    val detail: String,
    val chromiumBackend: ChromiumBackend? = null,
    val webGl2Only: Boolean = false,
    val bgraTextureVerified: Boolean = false,
    val softwareCompositor: ChromiumBackend? = null,
)

internal class GraphicsAcceleration(private val context: Context) {
    private val preferences = context.getSharedPreferences("graphics-acceleration", Context.MODE_PRIVATE)
    private val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    private val driver = File("/vendor/lib64/egl/libGLESv2_adreno.so")
    private val systemKey = "$stamp:${Build.FINGERPRINT}:${driver.lastModified()}:${driver.length()}"
    val potentialAdreno = driver.isFile ||
        (Build.VERSION.SDK_INT >= 31 && Build.SOC_MANUFACTURER.equals("Qualcomm", ignoreCase = true))
    var automatic by mutableStateOf(preferences.getBoolean("automatic", true))
        private set
    private var results by mutableStateOf(GpuPath.entries.associateWith { load(it) })
    var running by mutableStateOf<GpuPath?>(null)
        internal set

    private fun load(path: GpuPath): GpuResult? {
        if (preferences.getString("${path.platform}.system", null) != systemKey) return null
        val detail = preferences.getString("${path.platform}.detail", null) ?: return null
        val hardware = preferences.getBoolean("${path.platform}.hardware", false)
        val chromiumBackend = if (path == GpuPath.CHROMIUM) {
            ChromiumBackend.entries.firstOrNull { it.name == preferences.getString("chromium.backend", null) }
        } else null
        if (path == GpuPath.CHROMIUM && hardware && chromiumBackend == null) return null
        return GpuResult(hardware, detail, chromiumBackend,
            path == GpuPath.CHROMIUM && preferences.getBoolean("chromium.webgl2-only", false),
            preferences.getBoolean("${path.platform}.bgra-texture", false),
            if (path == GpuPath.CHROMIUM) ChromiumBackend.entries.firstOrNull {
                it.name == preferences.getString("chromium.software-compositor", null)
            } else null)
    }

    fun result(path: GpuPath) = results[path]

    fun setAutomaticMode(value: Boolean) {
        automatic = value
        preferences.edit().putBoolean("automatic", value).apply()
    }

    fun checkChromiumVersion(binary: File) {
        val key = chromiumKey(binary)
        if (preferences.getString("chromium.binary", null) == key) return
        results = results + (GpuPath.CHROMIUM to null)
        preferences.edit().putString("chromium.binary", key)
            .remove("chromium.hardware").remove("chromium.detail").remove("chromium.backend")
            .remove("chromium.webgl2-only").remove("chromium.software-compositor").apply()
    }

    fun record(path: GpuPath, result: GpuResult) {
        results = results + (path to result)
        preferences.edit().putString("${path.platform}.system", systemKey)
            .putString("${path.platform}.detail", result.detail)
            .putBoolean("${path.platform}.hardware", result.hardware)
            .putBoolean("${path.platform}.bgra-texture", result.bgraTextureVerified)
            .apply {
                if (path == GpuPath.CHROMIUM) {
                    putString("chromium.backend", result.chromiumBackend?.name)
                    putString("chromium.software-compositor", result.softwareCompositor?.name)
                    putBoolean("chromium.webgl2-only", result.webGl2Only)
                }
            }.apply()
    }

    fun backend(path: GpuPath): GpuPath? =
        path.takeIf { automatic && result(path)?.hardware == true }

    fun canProbeChromium(candidate: ChromiumBackend): Boolean =
        backend(candidate.display) != null &&
            (!candidate.nativeBgra || result(candidate.display)?.bgraTextureVerified == true)

    fun chromiumBackend(binary: File, rendering: ChromiumRendering = ChromiumRendering.AUTOMATIC): ChromiumBackend? {
        if (rendering == ChromiumRendering.SOFTWARE || backend(GpuPath.CHROMIUM) == null ||
            preferences.getString("chromium.binary", null) != chromiumKey(binary)) return null
        val result = result(GpuPath.CHROMIUM) ?: return null
        if (result.webGl2Only && rendering != ChromiumRendering.WEBGL2) return null
        val selected = result.chromiumBackend?.takeIf(::canProbeChromium)
        val softwareCompositor = result.softwareCompositor?.takeIf(::canProbeChromium)
        return if (rendering == ChromiumRendering.SOFTWARE_COMPOSITING && selected?.gpuCompositing == true) {
            softwareCompositor
        } else selected ?: softwareCompositor
    }

    private fun chromiumKey(binary: File) = "${binary.lastModified()}:${binary.length()}"

    suspend fun prepareAssets(destination: File) {
        val marker = File(destination, ".apk")
        if (marker.takeIf(File::isFile)?.readText() == stamp.toString() &&
            File(destination, "adreno-probe").canExecute() &&
            File(destination, "libEGL.so.1").isFile) return
        val staging = File(destination.parentFile, "${destination.name}.next")
        check(staging.deleteRecursively() && staging.mkdirs()) { "无法准备 GPU 运行目录" }
        try {
            context.assets.open("graphics/hybris.tar").use { extractLinuxTar(it, staging) }
            check(File(staging, "adreno-probe").canExecute()) { "GPU 探针缺失" }
            File(staging, marker.name).writeText(stamp.toString())
            check(destination.deleteRecursively()) { "无法替换 GPU 组件" }
            Os.rename(staging.path, destination.path)
        } finally {
            staging.deleteRecursively()
        }
    }
}
