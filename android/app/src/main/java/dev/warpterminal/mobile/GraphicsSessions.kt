package dev.warpterminal.mobile

import android.content.Context
import android.system.Os
import android.system.OsConstants
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.phie.tawc.compositor.NativeBridge
import org.json.JSONArray

internal data class LinuxApp(val id: String, val name: String, val desktopFile: String?)

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
    val activeCount get() = tabs.count { it.error == null }

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

    fun launchApp(app: LinuxApp) {
        // One reservation per desktop id avoids ambiguous app_id matching for single-instance apps.
        tabs.firstOrNull { it.pending && it.title == app.name }?.let { selected(it.id); return }
        val tab = GraphicsTab(newId(), UUID.randomUUID().toString(), app.name, true)
        tabs.add(tab)
        selected(tab.id)
        changed()
        tab.launcher = scope.launch {
            try {
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
                    val process = linux.launchGraphics(app, token)
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
        if (tabs.any { it.windowId == id }) return
        val tab = GraphicsTab(newId(), id, titles.remove(id) ?: "Linux 窗口", false)
        tabs.add(tab)
        selected(tab.id)
        changed()
    }

    fun windowClosed(id: String) {
        titles.remove(id)
        tabs.firstOrNull { it.windowId == id && it.error == null }?.let { remove(it) }
    }

    fun selectWindow(id: String) { tabs.firstOrNull { it.windowId == id }?.let { selected(it.id) } }

    fun titleChanged(id: String, title: String) {
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
