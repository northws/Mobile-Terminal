package dev.warpterminal.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal sealed interface Installation {
    data object Absent : Installation
    data class Working(val message: String, val percent: Float?) : Installation
    data class Failed(val message: String) : Installation
    data object Ready : Installation
}

class TerminalService : Service() {
    internal inner class LocalBinder : Binder() {
        val service get() = this@TerminalService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val linux by lazy { LinuxEnvironment(this) }
    internal val tabs = mutableStateListOf<TerminalTab>()
    internal val graphics by lazy {
        GraphicsSessions(this, linux, scope, { nextId++ }, {
            selectTab(it)
            interrupted = false
        }, ::graphicsChanged)
    }
    private val retiring = mutableSetOf<TerminalTab>()
    internal var installation by mutableStateOf<Installation>(Installation.Absent)
        private set
    internal var selected by mutableIntStateOf(0)
        private set
    internal var interrupted by mutableStateOf(false)
        private set
    private var nextId = 1
    private val recentTabs = mutableListOf<Int>()
    private val preferences by lazy { getSharedPreferences("terminal", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        interrupted = preferences.getBoolean("had_sessions", false)
        if (linux.installed()) installation = Installation.Ready
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Linux 终端", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification())
        when (intent?.action) {
            INSTALL -> install()
            ADD_TAB -> addTab()
            INSTALL_GRAPHICS -> if (installation == Installation.Ready) graphics.installExamples()
            ADD_GRAPHICS, ADD_COMPATIBLE_CHROMIUM -> if (installation == Installation.Ready) {
                val id = intent.getStringExtra(APP_ID)
                val name = intent.getStringExtra(APP_NAME)
                if (id != null && name != null) {
                    val app = LinuxApp(id, name, intent.getStringExtra(APP_FILE))
                    graphics.launchApp(app, chromiumCompatibility = intent.action == ADD_COMPATIBLE_CHROMIUM)
                }
            }
        }
        finishIfIdle()
        return START_NOT_STICKY
    }

    private fun install() {
        if (installation is Installation.Working || installation == Installation.Ready) return
        installation = Installation.Working("正在准备 Debian", null)
        scope.launch {
            try {
                linux.install { message, percent ->
                    installation = Installation.Working(message, percent)
                }
                installation = Installation.Ready
                addTab()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                installation = Installation.Failed(error.localizedMessage ?: "安装失败")
                finishIfIdle()
            }
        }
    }

    private fun addTab() {
        if (installation != Installation.Ready || tabs.size >= MAX_TABS) return
        val tab = TerminalTab(nextId++, linux, scope, ::sessionEnded)
        tabs.add(tab)
        selectTab(tab.id)
        interrupted = false
        recordActive()
        scope.launch {
            try {
                graphics.prepare()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("WarpGraphics", "Unable to prepare the local display", error)
            } catch (error: LinkageError) {
                android.util.Log.w("WarpGraphics", "Unable to load the compositor", error)
            }
            if (tab in tabs) {
                tab.start()
            } else {
                retiring.remove(tab)
                tab.terminate()
                sessionEnded()
            }
        }
        updateNotification()
    }

    internal fun closeTab(tab: TerminalTab) {
        if (!tabs.remove(tab)) return
        retiring.add(tab)
        tab.close()
        restoreSelection()
        sessionEnded()
    }

    internal fun selectTab(id: Int) {
        recentTabs.remove(id)
        recentTabs.add(id)
        selected = id
    }

    private fun restoreSelection() {
        val openIds = tabs.map(TerminalTab::id) + graphics.tabs.map(GraphicsTab::id)
        recentTabs.retainAll(openIds.toSet())
        if (selected !in openIds) selected = recentTabs.lastOrNull() ?: 0
    }

    private fun graphicsChanged() {
        restoreSelection()
        sessionEnded()
    }

    private fun recordActive() {
        preferences.edit().putBoolean("had_sessions", tabs.any { !it.exited } || graphics.activeCount > 0).apply()
    }

    private fun sessionEnded() {
        retiring.removeAll { it.exited }
        recordActive()
        if (tabs.any { !it.exited } || graphics.activeCount > 0 || graphics.installStatus != null) updateNotification()
        finishIfIdle()
    }

    private fun finishIfIdle() {
        if (installation !is Installation.Working && graphics.installStatus == null && graphics.activeCount == 0 &&
            tabs.none { !it.exited } && retiring.none { !it.exited }
        ) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun notification(): Notification {
        val reopen = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val active = tabs.count { !it.exited } + graphics.activeCount
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (active == 0) "正在准备 Linux 环境" else "$active 个本地 Linux 会话")
            .setContentIntent(reopen)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() = getSystemService(NotificationManager::class.java).notify(1, notification())

    override fun onDestroy() {
        tabs.forEach(TerminalTab::terminate)
        retiring.forEach(TerminalTab::terminate)
        graphics.dispose()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        internal const val INSTALL = "install"
        internal const val ADD_TAB = "add_tab"
        internal const val INSTALL_GRAPHICS = "install_graphics"
        internal const val ADD_GRAPHICS = "add_graphics"
        internal const val ADD_COMPATIBLE_CHROMIUM = "add_compatible_chromium"
        internal const val APP_ID = "app_id"
        internal const val APP_NAME = "app_name"
        internal const val APP_FILE = "app_file"
        internal const val MAX_TABS = 8
        private const val CHANNEL = "linux_terminal"
    }
}

internal class TerminalTab(
    val id: Int,
    private val linux: LinuxEnvironment,
    private val scope: CoroutineScope,
    private val onExit: () -> Unit,
) {
    private val requests = Channel<Frame>(128)
    private var process: Process? = null
    private var handshake = false
    private var nextBlock = 1
    private var pendingCommand: String? = null
    private var closing = false
    private var control = false
    val blocks = mutableStateListOf<CommandBlock>()
    var draft by mutableStateOf("")
    var replyDraft by mutableStateOf("")
    val running get() = !ready && !closing && !exited && blocks.lastOrNull()?.let { it.code == null } == true
    var directory by mutableStateOf("/home/warp")
        private set
    var ready by mutableStateOf(false)
        private set
    var alternate by mutableStateOf(false)
        private set
    var rawMode by mutableStateOf(false)
    var exited by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var revision by mutableIntStateOf(0)
        private set
    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = input(data.copyOfRange(offset, offset + count))
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }
    val emulator = TerminalEmulator(output, 80, 24, 8, 16, 2000, TerminalClient)

    fun start() {
        scope.launch {
            var writer: kotlinx.coroutines.Job? = null
            var timeout: kotlinx.coroutines.Job? = null
            try {
                val running = withContext(Dispatchers.IO) {
                    linux.prepareRunner()
                    linux.spawn()
                }
                process = running
                timeout = launch {
                    delay(15_000)
                    if (!handshake && !closing) {
                        error = "Bash 启动超时"
                        running.destroyForcibly()
                    }
                }
                launch(Dispatchers.IO) {
                    try {
                        running.errorStream.use { source ->
                            val buffer = ByteArray(4096)
                            while (source.read(buffer) >= 0) Unit
                        }
                    } catch (_: IOException) {
                        // Android can close the pipe while a read is blocked during process teardown.
                    }
                }
                writer = launch(Dispatchers.IO) {
                    try {
                        DataOutputStream(running.outputStream.buffered()).use { sink ->
                            for (frame in requests) sink.writeFrame(frame.kind, frame.data)
                        }
                    } catch (_: IOException) {
                        running.destroy()
                    }
                }
                withContext(Dispatchers.IO) {
                    DataInputStream(running.inputStream.buffered()).use { source ->
                        val hello = source.readFrame()
                        if (hello?.kind != JSON_FRAME || hello.json().optString("type") != "hello" ||
                            hello.json().optInt("version") != PROTOCOL_VERSION
                        ) throw IOException("无法与 Warp 后端建立会话")
                        while (true) {
                            val frame = source.readFrame() ?: break
                            withContext(Dispatchers.Main.immediate) { receive(frame) }
                        }
                    }
                    val code = if (running.waitFor(1, TimeUnit.SECONDS)) running.exitValue() else 1
                    if (code != 0 && !closing) throw IOException("Linux 终端退出（$code）")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (failure: Exception) {
                if (!closing && error == null) error = failure.localizedMessage ?: "会话已结束"
            } finally {
                writer?.cancel()
                timeout?.cancel()
                terminate()
                exited = true
                ready = false
                replyDraft = ""
                alternate = false
                onExit()
            }
        }
    }

    private fun receive(frame: Frame) {
        if (frame.kind == TERMINAL_FRAME) {
            emulator.append(frame.data, frame.data.size)
            revision++
            return
        }
        val value = frame.json()
        when (value.getString("type")) {
            "started" -> {
                ready = false
                replyDraft = ""
                if (blocks.size >= 100) blocks.removeAt(0)
                blocks.add(CommandBlock(nextBlock++, pendingCommand ?: value.getString("command")))
                pendingCommand = null
            }
            "output" -> blocks.lastOrNull()?.takeIf { it.code == null }?.append(
                Output(value.getString("text"), if (value.isNull("foreground")) null else value.getInt("foreground"),
                    value.getBoolean("bold")),
            )
            "line_edit" -> blocks.lastOrNull()?.takeIf { it.code == null }?.editLine(
                value.getString("operation"), value.optInt("column"),
            )
            "finished" -> {
                blocks.lastOrNull()?.takeIf { it.code == null }?.code = value.getInt("code")
                replyDraft = ""
            }
            "prompt" -> {
                handshake = true
                pendingCommand = null
                ready = true
                if (!value.isNull("directory")) directory = value.getString("directory")
            }
            "screen" -> alternate = value.getBoolean("alternate")
            "exit" -> blocks.lastOrNull()?.takeIf { it.code == null }?.code = value.getInt("code")
        }
    }

    fun submit() {
        if (!ready || draft.isBlank() || pendingCommand != null) return
        pendingCommand = draft
        emulator.paste(draft)
        input(byteArrayOf(13))
        draft = ""
        ready = false
    }

    fun sendReply() {
        if (!running) return
        val line = replyDraft.replace('\r', ' ').replace('\n', ' ')
        input((line + '\r').toByteArray())
        replyDraft = ""
    }

    fun input(bytes: ByteArray) {
        if (closing || exited || bytes.isEmpty()) return
        var start = 0
        while (start < bytes.size) {
            val end = minOf(start + 8192, bytes.size)
            send(Frame(TERMINAL_FRAME, bytes.copyOfRange(start, end)))
            start = end
        }
    }

    fun text(value: String) {
        if (control && value.length == 1 && value[0].uppercaseChar() in '@'..'_') {
            input(byteArrayOf((value[0].uppercaseChar().code - 64).toByte()))
        } else input(value.toByteArray())
        control = false
    }

    fun paste(text: String) = emulator.paste(text)

    fun control() { control = !control }

    fun key(code: Int, modifiers: Int = 0): Boolean {
        val sequence = KeyHandler.getCode(code, modifiers, emulator.isCursorKeysApplicationMode,
            emulator.isKeypadApplicationMode) ?: return false
        input(sequence.toByteArray())
        control = false
        return true
    }

    fun resize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        if (exited || closing) return
        if (emulator.mColumns != columns || emulator.mRows != rows) {
            emulator.resize(columns, rows, cellWidth, cellHeight)
            revision++
            send(json("resize") { put("columns", columns); put("rows", rows) })
        }
    }

    fun close() {
        if (closing || exited) return
        closing = true
        send(json("close"))
        requests.close()
        scope.launch {
            delay(2_000)
            if (!exited) process?.destroyForcibly()
        }
    }

    fun terminate() {
        requests.close()
        process?.destroy()
    }

    private fun send(frame: Frame) {
        if (requests.trySend(frame).isFailure && !closing && !exited) {
            error = "终端输入队列已满"
            terminate()
        }
    }

    private fun json(type: String, contents: JSONObject.() -> Unit = {}) =
        Frame(JSON_FRAME, JSONObject().put("type", type).apply(contents).toString().toByteArray())
}

private object TerminalClient : TerminalSessionClient {
    override fun onTextChanged(session: TerminalSession) = Unit
    override fun onTitleChanged(session: TerminalSession) = Unit
    override fun onSessionFinished(session: TerminalSession) = Unit
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit
    override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) = Unit
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, error: Exception?) = Unit
    override fun logStackTrace(tag: String?, error: Exception?) = Unit
}
