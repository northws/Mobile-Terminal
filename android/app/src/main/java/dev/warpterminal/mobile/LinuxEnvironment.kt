package dev.warpterminal.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val DEBIAN_USER = "Debian"
internal const val DEBIAN_HOME = "/home/Debian"
private const val LEGACY_DEBIAN_HOME = "/home/warp"

internal class LinuxEnvironment(private val context: Context) {
    private val packageInstallation = Mutex()
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
    internal val hybris = File(context.filesDir, "graphics-hybris")
    internal val acceleration = GraphicsAcceleration(context)
    internal val chromiumBinary get() = File(rootfs, "usr/lib/chromium/chromium")

    fun graphicalWorkingDirectory(pid: Int): String? = if (pid <= 0) null else runCatching {
        val path = File(Os.readlink("/proc/$pid/cwd")).toPath()
        val privateHome = home.canonicalFile.toPath()
        val privateRoot = rootfs.canonicalFile.toPath()
        when {
            path.startsWith(privateHome) -> "$DEBIAN_HOME/${privateHome.relativize(path)}".trimEnd('/')
            path.startsWith(privateRoot) -> "/${privateRoot.relativize(path)}"
            else -> null
        }
    }.getOrNull()

    fun installed(): Boolean = marker.isFile && File(rootfs, "usr/bin/bash").isFile

    fun sudoInstalled(): Boolean = File(rootfs, "usr/bin/sudo").canExecute() &&
        File(rootfs, "etc/sudoers.d/90-mobile-terminal").isFile

    fun online(): Boolean {
        val network = context.getSystemService(ConnectivityManager::class.java)
        return network.getNetworkCapabilities(network.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

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
        if (installed()) {
            prepareAccount(rootfs)
            return@withContext
        }
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
                "install", "vim", "less", "sudo", "ca-certificates", "libfontconfig1", "libfreetype6",
                "libwayland-client0", "libwayland-egl1", "libwayland-server0", "libx11-6", "libx11-xcb1"),
            "安装 Debian 软件包",
        )
        progress("正在准备 Debian 用户和 sudo", null)
        prepareAccount(candidate)
        configureSudo(candidate)
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

    fun spawn(directory: String = DEBIAN_HOME): Process = guest(
        rootfs, "/usr/bin/env", "WARP_START_DIRECTORY=$directory",
        "WARP_PATH_APPEND=$DEBIAN_HOME/.local/bin:$DEBIAN_HOME/.opencode/bin",
        "/opt/warp/warp-android-backend", asDebian = true, graphics = true, allowX11Override = true,
    ).start()

    suspend fun installSudo() = withContext(Dispatchers.IO) {
        packageInstallation.withLock {
            check(installed())
            if (sudoInstalled()) return@withLock
            prepareAccount(rootfs)
            if (!File(rootfs, "usr/bin/sudo").canExecute()) {
                if (!online()) throw IOException("请联网后重试安装 sudo")
                prepareRoot(rootfs)
                runStep(guest(rootfs, "/usr/bin/apt-get", "-o", "APT::Update::Error-Mode=any", "update"),
                    "更新 Debian 软件包索引")
                runStep(guest(rootfs, "/usr/bin/apt-get", "-y", "--no-install-recommends", "install", "sudo"),
                    "安装 sudo")
            }
            configureSudo(rootfs)
        }
    }

    suspend fun installGraphics(progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        packageInstallation.withLock {
            check(installed())
            prepareRoot(rootfs)
            progress("正在更新 Debian 软件包索引")
            runStep(guest(rootfs, "/usr/bin/apt-get", "update"), "更新 Debian 软件包索引")
            progress("正在安装 Mousepad、xeyes 和中文字体")
            runStep(
                guest(rootfs, "/usr/bin/apt-get", "-y", "--no-install-recommends", "install",
                    "libglib2.0-bin", "mousepad", "x11-apps", "fonts-noto-cjk", "libwayland-egl1",
                    "libwayland-server0", "libx11-xcb1"),
                "安装图形示例软件",
            )
        }
    }

    fun launchGraphics(app: LinuxApp, token: String, chromiumCompatibility: Boolean,
        rendering: ChromiumRendering,
    ): Process {
        val chromiumBackend = if (chromiumCompatibility) {
            acceleration.chromiumBackend(chromiumBinary, rendering)
        } else null
        val backend = when {
            chromiumCompatibility -> chromiumBackend?.display
            app.desktopFile == null -> acceleration.backend(GpuPath.X11)
            else -> acceleration.backend(GpuPath.WAYLAND)
        }
        val command = when {
            chromiumCompatibility -> {
                check(app.isDebianChromium)
                if (!File(rootfs, "usr/bin/chromium").canExecute()) throw IOException("Debian 中尚未安装 Chromium")
                chromiumCommand(chromiumBackend, "$DEBIAN_HOME/.local/share/mobile-terminal/chromium", "about:blank")
            }
            app.desktopFile != null -> {
                if (!File(rootfs, "usr/bin/gio").canExecute()) {
                    throw IOException("需要 Debian 软件包 libglib2.0-bin，可通过「安装示例应用」补齐。")
                }
                arrayOf("/usr/bin/gio", "launch", app.desktopFile)
            }
            else -> arrayOf("/usr/bin/xeyes")
        }
        return guest(rootfs, "/bin/bash", "-c", "cd \"\$HOME\" && exec \"\$@\"", "warp-launch",
            *command, asDebian = true, graphics = true, activation = token, backend = backend,
            chromiumBackend = chromiumBackend)
            .redirectOutput(File("/dev/null")).redirectError(File("/dev/null"))
            .start().also { it.outputStream.close() }
    }

    fun probeGraphics(path: GpuPath, token: String, workspace: File, output: File,
        chromiumBackend: ChromiumBackend?,
    ): Process {
        val command = if (chromiumBackend == null) {
            arrayOf("/usr/lib/hybris/adreno-probe", path.platform)
        } else {
            chromiumCommand(chromiumBackend, "/tmp/${workspace.name}", "file:///usr/lib/hybris/webgl.html") +
                arrayOf("--class=mobile-terminal-webgl-probe", "--disable-background-networking",
                    "--enable-logging=stderr")
        }
        return guest(rootfs, *command, asDebian = true, graphics = true, activation = token,
            backend = chromiumBackend?.display ?: path, chromiumBackend = chromiumBackend)
            .redirectErrorStream(true).redirectOutput(output).start().also { it.outputStream.close() }
    }

    fun probeDirectory(): File = java.nio.file.Files.createTempDirectory(
        File(rootfs, "tmp").toPath(), "mobile-terminal-gpu-",
    ).toFile()

    @Synchronized
    fun prepareGpuLinker() {
        // TAWC's libhybris fork reads this exact guest path. Android 10
        // has no generated file and libhybris supplies its own defaults.
        val source = File("/linkerconfig/ld.config.txt")
        if (!source.isFile || source.length() == 0L) return
        val directory = File(rootfs, "usr/lib/hybris-config")
        checkDirectory(directory)
        val target = File(directory, source.name)
        if (target.length() == source.length() && target.lastModified() == source.lastModified()) return
        val temp = File.createTempFile("linker-", ".tmp", directory)
        try {
            source.copyTo(temp, overwrite = true)
            Os.chmod(temp.path, 420)
            temp.setLastModified(source.lastModified())
            Os.rename(temp.path, target.path)
        } finally {
            temp.delete()
        }
    }

    private fun chromiumCommand(backend: ChromiumBackend?, profile: String, page: String): Array<String> {
        val args = mutableListOf("/usr/bin/chromium", "--no-sandbox", "--ozone-platform=${backend?.display?.platform ?: "wayland"}",
            "--no-first-run", "--no-default-browser-check", "--user-data-dir=$profile")
        if (backend == null) {
            args.add("--disable-gpu")
        } else {
            args.addAll(listOf("--use-gl=angle", "--disable-software-rasterizer",
                if (backend.display == GpuPath.WAYLAND) "--use-angle=gles" else "--use-angle=gles-egl"))
            if (!backend.gpuCompositing) args.add("--disable-gpu-compositing")
            if (backend.gpuCompositing && backend.display == GpuPath.WAYLAND) {
                // Ozone needs the browser's Wayland connection for native EGL windows.
                // Its native EGL path applies integer buffer scale in place of a fractional viewport.
                args.addAll(listOf("--in-process-gpu", "--disable-features=WaylandFractionalScaleV1"))
            }
            if (backend.nativeBgra) args.add("--disable-angle-features=bgraTexImageFormatsBroken")
        }
        args.add(page)
        return args.toTypedArray()
    }

    private fun guest(
        root: File,
        vararg command: String,
        asDebian: Boolean = false,
        graphics: Boolean = false,
        activation: String? = null,
        backend: GpuPath? = acceleration.backend(GpuPath.WAYLAND),
        allowX11Override: Boolean = false,
        chromiumBackend: ChromiumBackend? = null,
    ): ProcessBuilder {
        prepareHomeMount(root)
        val account = if (asDebian) prepareAccount(root) else null
        val args = mutableListOf(tawcroot.path, "-s", "-r", root.path)
        for (bind in listOf("/dev:/dev", "/proc:/proc", "/sys:/sys", "${home.path}:$DEBIAN_HOME", "${runnerDir.path}:/opt/warp:ro")) {
            args.addAll(listOf("-b", bind))
        }
        val x11 = File(graphicsShare, "xtmp/.X11-unix")
        val display = graphics && x11.isDirectory
        if (display) {
            checkDirectory(File(root, "usr/share/warp-display"))
            checkDirectory(File(root, "tmp/.X11-unix"))
            args.addAll(listOf("-b", "${graphicsShare.path}:/usr/share/warp-display",
                "-b", "${x11.path}:/tmp/.X11-unix"))
            if (backend != null || (allowX11Override && acceleration.backend(GpuPath.X11) != null)) {
                for (partition in listOf("/apex", "/vendor", "/system", "/system_ext")) {
                    if (File(partition).isDirectory) args.addAll(listOf("-b", "$partition:$partition:ro"))
                }
                args.addAll(listOf("-b", "${hybris.path}:/usr/lib/hybris:ro"))
            }
        }
        val user = if (asDebian) DEBIAN_USER else "root"
        val guestHome = if (asDebian) DEBIAN_HOME else "/root"
        args.addAll(listOf("--", "/usr/bin/env", "-i", "HOME=$guestHome", "USER=$user", "LOGNAME=$user", "SHELL=/bin/bash",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games",
            "LANG=C.UTF-8", "TMPDIR=/tmp", "DEBIAN_FRONTEND=noninteractive"))
        if (display) {
            args.addAll(listOf("WAYLAND_DISPLAY=wayland-0", "DISPLAY=:0",
                "XDG_RUNTIME_DIR=/usr/share/warp-display",
                "XDG_SESSION_TYPE=${chromiumBackend?.display?.platform ?: "wayland"}"))
            if (backend == null) args.add("LIBGL_ALWAYS_SOFTWARE=1")
            else args.addAll(listOf(
                "LD_LIBRARY_PATH=/usr/lib/hybris/gl-shims:/usr/lib/hybris",
                "HYBRIS_EGLPLATFORM=${backend.platform}",
                "__EGL_VENDOR_LIBRARY_FILENAMES=/usr/lib/hybris/10-mobile-terminal.json",
            ))
            if (chromiumBackend != null) args.add("MOBILE_TERMINAL_CHROMIUM_EGL=1")
            if (chromiumBackend?.gpuCompositing == true) {
                args.add(if (chromiumBackend.display == GpuPath.WAYLAND) {
                    "MOBILE_TERMINAL_CHROMIUM_WAYLAND_COMPOSITING=1"
                } else {
                    "MOBILE_TERMINAL_CHROMIUM_X11_COMPOSITING=1"
                })
            }
        }
        if (activation != null) args.addAll(listOf("XDG_ACTIVATION_TOKEN=$activation", "DESKTOP_STARTUP_ID=$activation"))
        if (account != null) {
            // setpriv applies its UID change before its GID change. Set the
            // guest group while the launcher still has virtual root privileges.
            args.addAll(listOf("/usr/bin/setpriv", "--regid=${account.second}", "--clear-groups", "--",
                "/usr/bin/setpriv", "--reuid=${account.first}", "--"))
        }
        args.addAll(command)
        return ProcessBuilder(args).directory(File(root, "tmp")).apply {
            environment().clear()
            environment()["TMPDIR"] = File(root, "tmp").path
        }
    }

    @Synchronized
    private fun prepareAccount(root: File): Pair<Int, Int> {
        val hosts = File(root, "etc/hosts").toPath()
        if (!Files.exists(hosts, NOFOLLOW_LINKS)) try {
            Files.write(hosts, "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n".toByteArray(), CREATE_NEW, WRITE)
        } catch (_: FileAlreadyExistsException) {
            // Preserve a hosts file created by another process.
        }
        val passwd = File(root, "etc/passwd")
        fun entry(): List<String>? = passwd.useLines { lines ->
            lines.firstOrNull { it.startsWith("$DEBIAN_USER:") }?.split(':')
        }
        var account = entry()
        if (account == null) {
            runStep(guest(root, "/usr/sbin/useradd", "--badname", "--no-create-home", "--user-group",
                "--home-dir", DEBIAN_HOME, "--shell", "/bin/bash", DEBIAN_USER), "创建 Debian 用户")
            account = entry()
        }
        val record = account ?: throw IOException("找不到 Debian 用户")
        val uid = record.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }
        val gid = record.getOrNull(3)?.toIntOrNull()?.takeIf { it > 0 }
        if (uid == null || gid == null) throw IOException("Debian 用户必须使用非 root 身份")
        if (record.getOrNull(5) != DEBIAN_HOME || record.getOrNull(6) != "/bin/bash") {
            runStep(guest(root, "/usr/sbin/usermod", "--home", DEBIAN_HOME, "--shell", "/bin/bash", DEBIAN_USER),
                "配置 Debian 用户主目录")
        }
        return uid to gid
    }

    private fun configureSudo(root: File) {
        val script = """
            set -eu
            temp=${'$'}(mktemp /etc/sudoers.d/.mobile-terminal-XXXXXX)
            trap 'rm -f -- "${'$'}temp"' EXIT
            printf '%s\n' '$DEBIAN_USER ALL=(ALL:ALL) NOPASSWD: ALL' > "${'$'}temp"
            chmod 0440 "${'$'}temp"
            /usr/sbin/visudo -cf "${'$'}temp"
            mv -f -- "${'$'}temp" /etc/sudoers.d/90-mobile-terminal
        """.trimIndent()
        runStep(guest(root, "/bin/bash", "-c", script), "配置 sudo")
        try {
            runStep(guest(root, "/usr/bin/sudo", "-n", "/bin/bash", "-c",
                """test "${'$'}(/usr/bin/id -u)" = 0""", asDebian = true),
                "检查 Debian 用户的 sudo 权限")
        } catch (error: IOException) {
            File(root, "etc/sudoers.d/90-mobile-terminal").delete()
            throw error
        }
    }

    @Synchronized
    private fun prepareHomeMount(root: File) {
        val parent = File(root, "home").toPath()
        val mount = File(root, DEBIAN_HOME.removePrefix("/")).toPath()
        val legacy = File(root, LEGACY_DEBIAN_HOME.removePrefix("/")).toPath()
        if (!Files.exists(parent, NOFOLLOW_LINKS)) Files.createDirectory(parent)
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) throw IOException("/home 不是可用的目录")

        fun emptyDirectory(path: java.nio.file.Path): Boolean =
            Files.isDirectory(path, NOFOLLOW_LINKS) && Files.newDirectoryStream(path).use { !it.iterator().hasNext() }

        if (!Files.exists(mount, NOFOLLOW_LINKS)) Files.createDirectory(mount)
        if (!emptyDirectory(mount)) throw IOException("$DEBIAN_HOME 已有独立内容，无法挂载用户主目录。")
        if (Files.isSymbolicLink(legacy) &&
            Files.readSymbolicLink(legacy).toString() in listOf("Debian", DEBIAN_HOME)) return
        if (Files.exists(legacy, NOFOLLOW_LINKS)) {
            if (!emptyDirectory(legacy)) throw IOException("$LEGACY_DEBIAN_HOME 已有独立内容，无法创建兼容路径。")
            Files.delete(legacy)
        }
        Os.symlink("Debian", legacy.toString())
    }

    private fun prepareRoot(root: File) {
        prepareHomeMount(root)
        for (path in listOf("opt/warp", "dev", "proc", "sys", "tmp")) {
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
        val process = guest(root, "/opt/warp/warp-android-backend", asDebian = true)
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
