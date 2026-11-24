package dev.warpterminal.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.StatFs
import android.system.Os
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class LinuxEnvironment(private val context: Context) {
    private val base = File(context.filesDir, "linux")
    private val rootfs = File(base, "rootfs")
    val home = File(base, "home")
    private val runnerDir = File(base, "runner")
    private val candidate = File(base, "installing")
    private val staged = File(candidate, ".warp-staged")
    private val archive = File(base, "debian-${BuildConfig.DEBIAN_SHA256}.tar.gz.partial")
    private val marker = File(rootfs, ".warp-ready")
    private val runner = File(runnerDir, "warp-android-backend")
    private val tawcroot = File(context.applicationInfo.nativeLibraryDir, "libtawcroot.so")
    internal val desktopRoot get() = rootfs
    internal val graphicsShare = File(context.applicationInfo.dataDir, "share")

    fun installed(): Boolean = marker.isFile && File(rootfs, "usr/bin/bash").isFile

    @Synchronized
    fun prepareRunner() {
        if (!Build.SUPPORTED_ABIS.contains("arm64-v8a") || !tawcroot.canExecute()) {
            throw IOException("需要包含 tawcroot 的 ARM64 Android 安装包")
        }
        checkDirectory(base)
        checkDirectory(home)
        checkDirectory(runnerDir)
        val version = File(runnerDir, "apk-version")
        val updatedAt = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val signature = "${BuildConfig.VERSION_CODE}:$updatedAt"
        if (version.takeIf { it.isFile }?.readText() == signature && runner.canExecute()) return
        val temp = File(runnerDir, "backend.next")
        context.assets.open("generated/warp-android-backend").use { source ->
            temp.outputStream().use { target -> source.copyTo(target) }
        }
        Os.chmod(temp.path, 448) // 0700
        Os.rename(temp.path, runner.path)
        version.writeText(signature)
    }

    suspend fun install(progress: (String, Float?) -> Unit) = withContext(Dispatchers.IO) {
        prepareRunner()
        if (installed()) return@withContext
        if (StatFs(base.path).availableBytes < 600L * 1024 * 1024) {
            throw IOException("至少需要 600 MB 可用空间")
        }
        if (staged.takeIf { it.isFile }?.readText() != BuildConfig.DEBIAN_SHA256 ||
            !File(candidate, "usr/bin/bash").isFile
        ) {
            download(progress)
            progress("正在校验 Debian 镜像", null)
            verifyArchive()
            progress("正在解压 Linux 环境", null)
            removeTree(candidate)
            checkDirectory(candidate)
            extractDebianArchive(archive, candidate)
            staged.writeText(BuildConfig.DEBIAN_SHA256)
        }
        prepareRoot(candidate)
        progress("正在安装终端软件包", null)
        runStep(guest(candidate, "/usr/bin/apt-get", "update"), "更新 Debian 软件包索引")
        runStep(
            guest(candidate, "/usr/bin/apt-get", "-y", "--no-install-recommends",
                "install", "vim", "less", "ca-certificates", "libfontconfig1", "libfreetype6"),
            "安装 Debian 软件包",
        )
        progress("正在检查 Bash 和 Warp 命令块", null)
        verifyBackend(candidate)

        File(candidate, ".warp-ready").writeText(BuildConfig.DEBIAN_SHA256)
        if (rootfs.exists()) removeTree(rootfs)
        Os.rename(candidate.path, rootfs.path)
        archive.delete()
    }

    private suspend fun verifyArchive() {
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().buffered().use { source ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val size = source.read(bytes)
                if (size < 0) break
                digest.update(bytes, 0, size)
            }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != BuildConfig.DEBIAN_SHA256) {
            archive.delete()
            throw IOException("Debian 镜像校验失败，请重试")
        }
    }

    fun spawn(): Process = guest(rootfs, "/opt/warp/warp-android-backend", graphics = true).start()

    suspend fun installGraphics(progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        check(installed())
        prepareRoot(rootfs)
        progress("正在更新 Debian 软件包索引")
        runStep(guest(rootfs, "/usr/bin/apt-get", "update"), "更新 Debian 软件包索引")
        progress("正在安装 Mousepad、xeyes 和中文字体")
        runStep(
            guest(rootfs, "/usr/bin/apt-get", "-y", "--no-install-recommends", "install",
                "libglib2.0-bin", "mousepad", "x11-apps", "fonts-noto-cjk"),
            "安装图形示例软件",
        )
    }

    fun launchGraphics(app: LinuxApp, token: String): Process {
        val command = if (app.desktopFile != null) {
            if (!File(rootfs, "usr/bin/gio").canExecute()) {
                throw IOException("需要 Debian 软件包 libglib2.0-bin，可通过「安装示例应用」补齐。")
            }
            arrayOf("/usr/bin/gio", "launch", app.desktopFile)
        } else {
            arrayOf("/usr/bin/xeyes")
        }
        return guest(rootfs, "/bin/bash", "-c", "cd \"\$HOME\" && exec \"\$@\"", "warp-launch",
            *command, graphics = true, activation = token)
            .redirectOutput(File("/dev/null")).redirectError(File("/dev/null"))
            .start().also { it.outputStream.close() }
    }

    private fun guest(root: File, vararg command: String, graphics: Boolean = false, activation: String? = null): ProcessBuilder {
        val args = mutableListOf(tawcroot.path, "-s", "-r", root.path)
        for (bind in listOf("/dev:/dev", "/proc:/proc", "/sys:/sys", "${home.path}:/home/warp", "${runnerDir.path}:/opt/warp:ro")) {
            args.addAll(listOf("-b", bind))
        }
        val x11 = File(graphicsShare, "xtmp/.X11-unix")
        val display = graphics && x11.isDirectory
        if (display) {
            checkDirectory(File(root, "usr/share/warp-display"))
            checkDirectory(File(root, "tmp/.X11-unix"))
            args.addAll(listOf("-b", "${graphicsShare.path}:/usr/share/warp-display",
                "-b", "${x11.path}:/tmp/.X11-unix"))
        }
        args.addAll(listOf("--", "/usr/bin/env", "-i", "HOME=/home/warp", "USER=warp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games",
            "LANG=C.UTF-8", "TMPDIR=/tmp", "DEBIAN_FRONTEND=noninteractive"))
        if (display) args.addAll(listOf("WAYLAND_DISPLAY=wayland-0", "DISPLAY=:0",
            "XDG_RUNTIME_DIR=/usr/share/warp-display", "LIBGL_ALWAYS_SOFTWARE=1"))
        if (activation != null) args.addAll(listOf("XDG_ACTIVATION_TOKEN=$activation", "DESKTOP_STARTUP_ID=$activation"))
        args.addAll(command)
        return ProcessBuilder(args).directory(File(root, "tmp")).apply {
            environment().clear()
            environment()["TMPDIR"] = File(root, "tmp").path
        }
    }

    private fun prepareRoot(root: File) {
        for (path in listOf("home/warp", "opt/warp", "dev", "proc", "sys", "tmp")) {
            checkDirectory(File(root, path))
        }
        val network = context.getSystemService(ConnectivityManager::class.java)
        val dns = network.getLinkProperties(network.activeNetwork)?.dnsServers.orEmpty()
            .mapNotNull { address -> address.hostAddress?.substringBefore('%') }.ifEmpty { listOf("8.8.8.8") }
        val resolv = File(root, "etc/resolv.conf")
        resolv.delete()
        resolv.writeText(dns.joinToString("\n", postfix = "\n") { "nameserver $it" })
        File(root, "etc/apt/apt.conf.d/90warp").writeText(
            "APT::Sandbox::User \"root\";\nDpkg::Use-Pty \"0\";\nAcquire::Languages \"none\";\n" +
                "Acquire::Retries \"2\";\nAcquire::http::Timeout \"25\";\n",
        )
    }

    private suspend fun download(progress: (String, Float?) -> Unit) {
        var offset = archive.length()
        if (offset == BuildConfig.DEBIAN_BYTES) return
        if (offset > BuildConfig.DEBIAN_BYTES) {
            archive.delete()
            offset = 0
        }
        val connection = (URL(BuildConfig.DEBIAN_URL).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 25_000
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            when (val status = connection.responseCode) {
                200 -> offset = 0
                206 -> if (!connection.getHeaderField("Content-Range").orEmpty().startsWith("bytes $offset-")) {
                    throw IOException("Debian 下载服务器返回了错误的文件区间")
                }
                else -> throw IOException("Debian 下载失败（HTTP $status）")
            }
            java.io.FileOutputStream(archive, offset > 0).use { target ->
                connection.inputStream.use { source ->
                    val bytes = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val size = source.read(bytes)
                        if (size < 0) break
                        offset += size
                        if (offset > BuildConfig.DEBIAN_BYTES) throw IOException("Debian 下载文件大小超出预期")
                        target.write(bytes, 0, size)
                        progress("正在下载 Debian", offset.toFloat() / BuildConfig.DEBIAN_BYTES)
                    }
                }
            }
        } catch (error: IOException) {
            val message = when (error) {
                is UnknownHostException -> "无法连接 Debian 镜像源，请检查网络后重试。"
                is SocketTimeoutException -> "下载 Debian 超时，请重试。"
                is SocketException -> "Debian 下载连接中断，请恢复网络后重试。"
                else -> throw error
            }
            throw IOException(message, error)
        } finally {
            connection.disconnect()
        }
        if (offset != BuildConfig.DEBIAN_BYTES) throw IOException("Debian 下载中断，请重试")
    }

    private fun verifyBackend(root: File) {
        val process = guest(root, "/opt/warp/warp-android-backend")
            .redirectError(File("/dev/null")).start()
        val timeout = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        timeout.schedule({ process.destroyForcibly() }, 15, TimeUnit.SECONDS)
        try {
            DataInputStream(process.inputStream.buffered()).use { reader ->
                val hello = reader.readFrame()?.json()
                if (hello?.optInt("version") != PROTOCOL_VERSION) throw IOException("后端握手失败")
                while (true) {
                    val frame = reader.readFrame() ?: throw IOException("Bash 未返回初始提示符")
                    if (frame.kind == JSON_FRAME && frame.json().optString("type") == "prompt") return
                }
            }
        } finally {
            process.outputStream.close()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            timeout.shutdownNow()
        }
    }

    private fun runStep(builder: ProcessBuilder, label: String) {
        val process = builder.redirectOutput(File("/dev/null")).redirectError(File("/dev/null")).start()
        process.outputStream.close()
        if (!process.waitFor(8, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw IOException("$label 超时")
        }
        if (process.exitValue() != 0) throw IOException("$label 失败（退出码 ${process.exitValue()}）")
    }

    private fun checkDirectory(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建 ${dir.name} 目录")
    }

    private fun removeTree(file: File) {
        if (!java.nio.file.Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        java.nio.file.Files.walk(file.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::delete)
        }
    }
}
