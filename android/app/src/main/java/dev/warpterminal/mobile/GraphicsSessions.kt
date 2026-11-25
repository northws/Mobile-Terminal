package dev.warpterminal.mobile

import android.content.Context
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.phie.tawc.compositor.NativeBridge
import org.json.JSONArray

internal data class LinuxApp(val id: String, val name: String, val desktopFile: String?) {
    val isDebianChromium get() = desktopFile == "/usr/share/applications/chromium.desktop"
}

internal class GraphicsTab(val id: Int, val windowId: String, name: String, waiting: Boolean) {
    var title by mutableStateOf(name)
    var pending by mutableStateOf(waiting)
    var error by mutableStateOf<String?>(null)
    var launcher: Job? = null
    var timeout: Job? = null
}

internal class GraphicsSessions(
    private val context: Context,
    private val linux: LinuxEnvironment,
    private val scope: CoroutineScope,
    private val newId: () -> Int,
    private val selected: (Int) -> Unit,
    private val changed: () -> Unit,
) {
    val tabs = mutableStateListOf<GraphicsTab>()
    var installStatus by mutableStateOf<String?>(null)
        private set
    var installError by mutableStateOf<String?>(null)
        private set
    var installRevision by mutableStateOf(0)
        private set
    private val preparation = Mutex()
    private val titles = mutableMapOf<String, String>()
    private var prepared = false
    private var compositorStarted = false
    val acceleration get() = linux.acceleration
    private var gpuCheck: Job? = null
    private var checkChromium = false
    private var probe: ProbeWindow? = null
    val activeCount get() = tabs.count { it.error == null }

    private class ProbeWindow(val id: String) {
        var firstFrame = false
        var title = ""
        var webGlPixels = false
        var visibleColors = 0

        fun webGlFinished(gpuCompositing: Boolean) = firstFrame &&
            (title.contains("WebGL FAIL ") ||
                (webGlPixels && title.isNotEmpty() && (!gpuCompositing || visibleColors == 3)))
    }

    suspend fun prepare() = preparation.withLock {
        if (prepared) return@withLock
        withContext(Dispatchers.IO) {
            val share = linux.graphicsShare
            val runtime = File(share, "xtmp")
            val xwayland = File(context.filesDir, "xwayland")
            val rules = File(xwayland, "share/xkeyboard-config-2")
            val version = File(xwayland, "version")
            val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
            if (version.takeIf(File::isFile)?.readText() != stamp || !File(rules, "rules/evdev").isFile) {
                if (xwayland.exists() && !xwayland.deleteRecursively()) throw IOException("无法更新图形运行资源")
                directory(xwayland)
                context.assets.open("graphics/xkb.tar").use { extractLinuxTar(it, xwayland) }
                check(File(rules, "rules/evdev").isFile) { "图形键盘映射缺失" }
                val link = File(xwayland, "share/X11/xkb")
                directory(link.parentFile!!)
                Os.symlink("../xkeyboard-config-2", link.path)
                val bin = File(xwayland, "bin")
                directory(bin)
                linkExecutable(bin, "Xwayland", "libxwayland.so")
                linkExecutable(bin, "xkbcomp", "libxkbcomp.so")
                version.writeText(stamp)
            }
            directory(File(runtime, ".X11-unix"))
            Os.chmod(share.path, OsConstants.S_IRWXU)
            val environments = mapOf(
                "TAWC_APP_DATA_DIR" to context.applicationInfo.dataDir,
                "TAWC_APP_FILES_DIR" to context.filesDir.path,
                "TAWC_APP_SHARE_DIR" to share.path,
                "TAWC_DISTROS_DIR" to File(context.filesDir, "linux").path,
                "TAWC_XWAYLAND_DIR" to xwayland.path,
                "TAWC_XWAYLAND_RUNTIME_DIR" to runtime.path,
                "TAWC_XKB_CONFIG_ROOT" to rules.path,
                "TAWC_XWAYLAND_ENABLED" to "1",
                "TAWC_NATIVE_LIB_DIR" to context.applicationInfo.nativeLibraryDir,
            )
            environments.forEach { (key, value) -> Os.setenv(key, value, true) }
            startActivation(share, runtime)
        }
        NativeBridge.sessions = this
        prepared = true
    }

    fun activate() {
        if (!prepared) return
        val metrics = context.resources.displayMetrics
        if (NativeBridge.nativeStartCompositor(metrics.density, metrics.widthPixels, metrics.heightPixels, true, 2)) {
            compositorStarted = true
            NativeBridge.nativeSetTintBuffersByType(false)
        }
    }

    fun compositorStopped() {
        if (NativeBridge.nativeIsCompositorRunning()) return
        compositorStarted = false
        tabs.filter { it.error == null }.forEach { failed(it, "图形服务已结束，请重新打开程序。") }
    }

    suspend fun apps(): List<LinuxApp> = withContext(Dispatchers.IO) {
        val root = linux.desktopRoot.canonicalFile.path
        val home = linux.home.canonicalFile.path
        val apps = JSONArray(NativeBridge.nativeScanWarpApplications(root, home) ?: "[]")
        buildList {
            for (index in 0 until apps.length()) {
                val entry = apps.getJSONObject(index)
                val path = entry.getString("path")
                val desktopFile = when {
                    path.startsWith("$home/") -> "/home/warp" + path.removePrefix(home)
                    path.startsWith("$root/") -> path.removePrefix(root)
                    else -> continue
                }
                add(LinuxApp(entry.getString("id"), entry.getString("name"), desktopFile))
            }
            if (File(root, "usr/bin/xeyes").canExecute() && none { it.id.equals("xeyes", ignoreCase = true) }) {
                add(LinuxApp("xeyes", "xeyes", null))
            }
        }
    }

    fun installExamples() {
        if (installStatus != null) return
        installError = null
        installStatus = "正在准备图形示例"
        scope.launch {
            try {
                linux.installGraphics { status -> scope.launch { installStatus = status } }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                installError = error.localizedMessage ?: "图形示例安装失败"
            } finally {
                installStatus = null
                installRevision++
                changed()
            }
        }
    }

    fun setAutomaticGraphics(enabled: Boolean) {
        acceleration.setAutomaticMode(enabled)
        if (enabled) detectGraphics(includeChromium = true) else gpuCheck?.cancel()
    }

    fun detectGraphics(includeChromium: Boolean, retry: Boolean = false): Job? {
        if (!acceleration.automatic || !acceleration.potentialAdreno) return null
        gpuCheck?.takeIf { it.isActive }?.let {
            checkChromium = checkChromium || includeChromium
            return it
        }
        val previous = gpuCheck
        checkChromium = includeChromium
        return scope.launch {
            previous?.join()
            if (!acceleration.automatic) return@launch
            acceleration.checkChromiumVersion(linux.chromiumBinary)
            for (path in GpuPath.entries) {
                if (path == GpuPath.CHROMIUM && (!checkChromium || !linux.chromiumBinary.canExecute())) continue
                if (!retry && acceleration.result(path) != null) continue
                acceleration.running = path
                try {
                    prepare()
                    withContext(Dispatchers.IO) {
                        acceleration.prepareAssets(linux.hybris)
                        linux.prepareGpuLinker()
                    }
                    activate()
                    val result = if (path == GpuPath.CHROMIUM) checkChromiumGpu() else checkGpu(path)
                    Log.i("WarpGraphics", "${path.label}: ${result.detail}")
                    acceleration.record(path, result)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w("WarpGraphics", "${path.label} probe failed", error)
                    acceleration.record(path, GpuResult(false, "无法准备 GPU 组件"))
                } catch (_: LinkageError) {
                    acceleration.record(path, GpuResult(false, "图形组件加载失败"))
                } finally {
                    acceleration.running = null
                }
            }
        }.also { gpuCheck = it }
    }

    private suspend fun checkChromiumGpu(): GpuResult {
        val failures = mutableListOf<String>()
        var webGl2: GpuResult? = null
        var software: GpuResult? = null
        for (backend in ChromiumBackend.entries.filterNot { it.gpuCompositing }) {
            if (!acceleration.canProbeChromium(backend)) continue
            val result = checkGpu(GpuPath.CHROMIUM, backend)
            val display = if (backend.display == GpuPath.X11) "XWayland" else "Wayland"
            if (result.hardware) {
                val detected = result.copy(detail = "$display · ${result.detail}", chromiumBackend = backend)
                if (!detected.webGl2Only) {
                    software = detected.copy(detail = "$display · 软件合成 · ${result.detail}")
                    break
                }
                if (!backend.nativeBgra && webGl2 == null) webGl2 = detected
            } else failures.add("$display${if (backend.nativeBgra) " BGRA" else ""} · ${result.detail}")
        }
        for (backend in ChromiumBackend.entries.filter { it.gpuCompositing }) {
            if (!acceleration.canProbeChromium(backend)) continue
            val result = checkGpu(GpuPath.CHROMIUM, backend)
            if (result.hardware && !result.webGl2Only) {
                return result.copy(detail = "XWayland · 硬件合成 · ${result.detail}",
                    chromiumBackend = backend, softwareCompositor = software?.chromiumBackend)
            }
            Log.i("WarpGraphics", "Chromium XWayland GPU compositing: ${result.detail}")
        }
        return software ?: webGl2 ?:
            GpuResult(false, failures.joinToString("；").ifEmpty { "需要通过 Wayland 或 X11 EGL 检测" })
    }

    private suspend fun checkGpu(path: GpuPath, chromiumBackend: ChromiumBackend? = null): GpuResult {
        val chromium = chromiumBackend != null
        val gpuCompositing = chromiumBackend?.gpuCompositing == true
        val id = UUID.randomUUID().toString()
        val window = ProbeWindow(id)
        val appId = if (chromium) "mobile-terminal-webgl-probe" else "mobile-terminal-egl-probe"
        var directory: File? = null
        var process: Process? = null
        // The browser toolbar and compatibility banner take part of the surface on high-density screens.
        val reader = ImageReader.newInstance(if (chromium) 1280 else 768, if (chromium) 960 else 512,
            PixelFormat.RGBA_8888, 3)
        probe = window
        try {
            val token = withContext(Dispatchers.IO) { NativeBridge.nativeReserveLaunchHost(id, appId, -1) }
                ?: return GpuResult(false, "合成器未响应")
            reader.setOnImageAvailableListener({ receiver ->
                receiver.acquireLatestImage()?.use { frame ->
                    if (chromium && (!window.webGlPixels || (gpuCompositing && window.visibleColors != 3))) {
                        val colors = canvasColors(frame, gpuCompositing)
                        if (colors != 0) {
                            window.webGlPixels = true
                            window.visibleColors = window.visibleColors or colors
                        }
                    }
                }
            },
                Handler(Looper.getMainLooper()))
            NativeBridge.nativeRegisterActivitySurface(id, reader.surface, reader.width, reader.height)
            NativeBridge.nativeOnActivityFocusChanged(id, true)
            val before = withContext(Dispatchers.IO) { hardwareImports() }
            val workspace = withContext(Dispatchers.IO) { linux.probeDirectory().also { directory = it } }
            val output = File(workspace, "probe.log")
            withContext(Dispatchers.IO) {
                process = linux.probeGraphics(path, token, workspace, output, chromiumBackend)
            }
            val guest = checkNotNull(process)
            val completed = withTimeoutOrNull(if (chromium) 18000L else 10000L) {
                while (guest.isAlive && !(chromium && window.webGlFinished(gpuCompositing))) delay(60)
                true
            } ?: false
            if (chromium) {
                val imports = if (gpuCompositing) withContext(Dispatchers.IO) { hardwareImports() } else null
                val failure = withContext(Dispatchers.IO) {
                    output.useLines { lines ->
                        lines.take(300).mapNotNull { line ->
                            when {
                                "EGL_NOT_INITIALIZED" in line -> "EGL 初始化失败"
                                "GL_INVALID_FRAMEBUFFER_OPERATION" in line -> "WebGL 帧缓冲不完整"
                                "Exiting GPU process due to errors" in line -> "GPU 进程初始化失败"
                                else -> null
                            }
                        }.firstOrNull()
                    }
                }
                val webGl2Only = window.title.contains("Mobile Terminal WebGL WEBGL2 ")
                val prefix = if (webGl2Only) "Mobile Terminal WebGL WEBGL2 " else "Mobile Terminal WebGL OK "
                val renderer = window.title.substringAfter(prefix, "").substringBeforeLast(" - Chromium").take(160)
                val detail = if (webGl2Only) "WebGL 2 可用，WebGL 1 失败 · $renderer" else "WebGL 1 和 2 可用 · $renderer"
                return when {
                    gpuCompositing && (!completed || window.visibleColors != 3 || before == null ||
                        imports == null || imports <= before) ->
                        GpuResult(false, failure ?: "硬件合成未显示两帧 WebGL 画面")
                    guest.isAlive && window.firstFrame && window.webGlPixels && renderer.contains("Adreno", ignoreCase = true) ->
                        GpuResult(true, detail, webGl2Only = webGl2Only)
                    window.title.contains("WebGL FAIL context lost") -> GpuResult(false, "WebGL 上下文丢失")
                    renderer.isNotEmpty() && !guest.isAlive -> GpuResult(false, "Chromium 探测进程提前退出")
                    renderer.isNotEmpty() && !window.firstFrame -> GpuResult(false, "未收到 Chromium 窗口首帧")
                    renderer.isNotEmpty() -> GpuResult(false, "图形标签未显示 WebGL 画面")
                    failure != null -> GpuResult(false, failure)
                    !completed -> GpuResult(false, "等待 WebGL 结果超时")
                    window.title.contains("WebGL FAIL webgl unavailable") -> GpuResult(false, "无法建立 WebGL 上下文")
                    window.title.contains("WebGL FAIL") -> GpuResult(false, "WebGL 未通过 Adreno 绘制或像素读回")
                    else -> GpuResult(false, "Chromium GPU 进程或图形窗口未就绪")
                }
            }
            if (!completed) return GpuResult(false, "EGL 绘制超时")
            val counters = withContext(Dispatchers.IO) { hardwareImports() }
            val report = withContext(Dispatchers.IO) {
                output.bufferedReader().use { source -> CharArray(32768).let { buffer ->
                    val size = source.read(buffer).coerceAtLeast(0)
                    String(buffer, 0, size)
                } }
            }
            val renderer = report.lineSequence().firstOrNull { it.startsWith("GL_RENDERER=") }
                ?.substringAfter('=')?.take(100).orEmpty()
            val vendor = report.lineSequence().firstOrNull { it.startsWith("GL_VENDOR=") }.orEmpty()
            Log.i("WarpGraphics", "${path.label} EGL exit=${guest.exitValue()}, firstFrame=${window.firstFrame}, " +
                "renderer=$renderer, AHB imports=$before → $counters")
            return when {
                guest.exitValue() != 0 -> GpuResult(false, when (guest.exitValue()) {
                    11 -> "连接显示服务失败"
                    12, 13 -> "初始化 EGL 或选择配置失败"
                    14 -> "创建 EGL 绘制表面失败"
                    15, 16 -> "像素读回或提交画面失败"
                    else -> "EGL 探针退出（${guest.exitValue()}）"
                })
                !vendor.contains("Qualcomm", true) || !renderer.contains("Adreno", true) ->
                    GpuResult(false, "EGL 客户端未报告 Qualcomm Adreno")
                !window.firstFrame || !report.lineSequence().any { it == "FRAMES=48" } ||
                    before == null || counters == null || counters <= before ->
                    GpuResult(false, "合成器未导入 GPU 画面")
                else -> GpuResult(true, renderer, bgraTextureVerified = report.lineSequence().any { it == "BGRA8888=1" })
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                process?.let { guest ->
                    if (guest.isAlive) guest.destroyForcibly()
                    guest.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                }
                directory?.deleteRecursively()
            }
            NativeBridge.nativeOnActivityFocusChanged(id, false)
            NativeBridge.nativeOnActivitySurfaceDestroyed(id)
            NativeBridge.nativeReleaseLaunchHost(id)
            NativeBridge.restoreFocusedView()
            reader.setOnImageAvailableListener(null, null)
            reader.close()
            probe = null
        }
    }

    private fun hardwareImports(): Long? = NativeBridge.nativeQueryState()?.split(' ')
        ?.firstOrNull { it.startsWith("wlegl_import_texture_total=") }?.substringAfter('=')?.toLongOrNull()

    private fun canvasColors(frame: Image, checkAnimation: Boolean): Int {
        val plane = frame.planes.firstOrNull() ?: return 0
        val bytes = plane.buffer
        var redSamples = 0
        var blueSamples = 0
        var greenSamples = 0
        for (y in 0 until frame.height step 8) {
            for (x in 0 until frame.width step 8) {
                val offset = y * plane.rowStride + x * plane.pixelStride
                if (offset + 2 >= bytes.limit()) break
                val red = bytes.get(offset).toInt() and 255
                val green = bytes.get(offset + 1).toInt() and 255
                val blue = bytes.get(offset + 2).toInt() and 255
                if (isCanvasChannel(red, green, blue) && ++redSamples >= 64 && !checkAnimation) return 1
                if (checkAnimation) {
                    if (isCanvasChannel(blue, red, green)) blueSamples++
                    if (isCanvasChannel(green, red, blue)) greenSamples++
                }
            }
        }
        if (redSamples < 64) return 0
        return (if (blueSamples >= 64) 1 else 0) or (if (greenSamples >= 64) 2 else 0)
    }

    private fun isCanvasChannel(primary: Int, second: Int, third: Int) =
        primary > 165 && maxOf(second, third) * 5 < primary * 2

    fun firstFrame(id: String) {
        probe?.takeIf { it.id == id }?.firstFrame = true
    }

    fun launchApp(app: LinuxApp, chromiumCompatibility: Boolean, rendering: ChromiumRendering) {
        // One reservation per desktop id avoids ambiguous app_id matching for single-instance apps.
        tabs.firstOrNull { it.pending && it.title == app.name }?.let { selected(it.id); return }
        val tab = GraphicsTab(newId(), UUID.randomUUID().toString(), app.name, true)
        tabs.add(tab)
        selected(tab.id)
        changed()
        tab.launcher = scope.launch {
            try {
                if (rendering != ChromiumRendering.SOFTWARE) {
                    detectGraphics(includeChromium = chromiumCompatibility)?.join()
                }
                prepare()
                activate()
                val token = withContext(Dispatchers.IO) {
                    NativeBridge.nativeReserveLaunchHost(tab.windowId, app.id, -1)
                } ?: throw IOException("图形服务未响应")
                tab.timeout = scope.launch {
                    delay(25_000)
                    if (tab.pending) failed(tab, "未收到图形窗口，请检查应用是否已安装及其显示支持。")
                }
                val result = withContext(Dispatchers.IO) {
                    val process = linux.launchGraphics(app, token, chromiumCompatibility, rendering)
                    try {
                        runInterruptible { process.waitFor() }
                    } finally {
                        if (process.isAlive) process.destroy()
                    }
                }
                NativeBridge.nativeUpdateLaunch(tab.windowId, 0, true)
                if (result != 0 && tab.pending) failed(tab, "图形程序启动失败（退出码 $result）")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (tab.pending) failed(tab, error.localizedMessage ?: "图形程序启动失败")
            } catch (_: LinkageError) {
                failed(tab, "图形运行组件加载失败")
            }
        }
    }

    fun windowOpened(id: String) {
        if (probe?.id == id) return
        if (tabs.any { it.windowId == id }) return
        val tab = GraphicsTab(newId(), id, titles.remove(id) ?: "Linux 窗口", false)
        tabs.add(tab)
        selected(tab.id)
        changed()
    }

    fun windowClosed(id: String) {
        if (probe?.id == id) return
        titles.remove(id)
        tabs.firstOrNull { it.windowId == id && it.error == null }?.let { remove(it) }
    }

    fun selectWindow(id: String) { tabs.firstOrNull { it.windowId == id }?.let { selected(it.id) } }

    fun titleChanged(id: String, title: String) {
        probe?.takeIf { it.id == id }?.let {
            if ("Mobile Terminal WebGL OK " in title || "Mobile Terminal WebGL FAIL " in title ||
                "Mobile Terminal WebGL WEBGL2 " in title) {
                it.title = title
            }
            return
        }
        if (title.isBlank()) return
        val tab = tabs.firstOrNull { it.windowId == id }
        if (tab == null) titles[id] = title else tab.title = title
    }

    fun launchMatched(id: String) {
        tabs.firstOrNull { it.windowId == id && it.error == null }?.let {
            it.pending = false
            it.timeout?.cancel()
            titles.remove(id)?.let { title -> it.title = title }
        }
    }

    fun close(tab: GraphicsTab) {
        if (!tab.pending && tab.error == null) NativeBridge.nativeRequestWindowClose(tab.windowId)
        else remove(tab)
    }

    private fun failed(tab: GraphicsTab, message: String) {
        releasePending(tab)
        tab.error = message
        tab.pending = false
        changed()
    }

    private fun remove(tab: GraphicsTab) {
        releasePending(tab)
        tabs.remove(tab)
        changed()
    }

    private fun releasePending(tab: GraphicsTab) {
        tab.timeout?.cancel()
        if (tab.pending) {
            tab.launcher?.cancel()
            if (prepared) NativeBridge.nativeReleaseLaunchHost(tab.windowId)
        }
    }

    fun dispose() {
        if (prepared && NativeBridge.sessions === this) {
            NativeBridge.sessions = null
            if (compositorStarted) {
                // JNI waits for compositor teardown. Do not block the Activity thread.
                Thread({ NativeBridge.nativeStopCompositor() }, "warp-graphics-stop").start()
            }
        }
    }

    private fun directory(file: File) {
        if (!file.isDirectory && !file.mkdirs()) throw IOException("无法创建图形运行目录 ${file.name}")
    }

    private fun linkExecutable(directory: File, name: String, library: String) {
        val source = File(context.applicationInfo.nativeLibraryDir, library)
        if (!source.canExecute()) throw IOException("APK 缺少可执行的 $name")
        Os.symlink(source.path, File(directory, name).path)
    }

    private companion object {
        private var activationReady = false

        @Synchronized
        fun startActivation(share: File, runtime: File) {
            if (activationReady) return
            // Android can kill the app before Smithay removes its display files.
            // The holder belongs to this app process, even when its Service is recreated.
            for (path in listOf(".X0-lock", ".X11-unix/X0")) {
                Files.deleteIfExists(File(runtime, path).toPath())
            }
            NativeBridge.nativeStartActivation(true)
            check(OsConstants.S_ISSOCK(Os.lstat(File(share, "wayland-0").path).st_mode))
            activationReady = true
        }
    }
}
