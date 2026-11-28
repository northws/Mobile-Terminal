package dev.warpterminal.mobile

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CancellationException

class TabWindowActivity : SessionActivity() {
    private var request by mutableStateOf<WindowRequest?>(null)
    private var serial = 0
    private var ownedTab: Int? = null

    private data class WindowRequest(val key: String, val reopen: Boolean, val serial: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        readIntent(intent, restore = savedInstanceState != null)
        super.onCreate(savedInstanceState)
        if (request == null) finishAndRemoveTask()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent, restore: Boolean = false) {
        request = WindowLinks.windowKey(intent)?.let { key ->
            WindowRequest(key, !restore && intent.action == WindowLinks.REOPEN, ++serial)
        }
    }

    override fun onDestroy() {
        if (isFinishing) ownedTab?.let { boundService?.returnWindow(it, taskId) }
        super.onDestroy()
    }

    @Composable
    internal override fun SessionContent(service: TerminalService, bars: WindowInsetsControllerCompat,
        appearance: Appearance, changeAppearance: (Appearance) -> Unit) {
        val current = request ?: return
        var tab by remember { mutableStateOf<Int?>(null) }
        var failure by remember(current) { mutableStateOf<String?>(null) }
        var confirm by remember(current) { mutableStateOf<Pair<LinuxApp, ChromiumRendering>?>(null) }
        var menu by remember { mutableStateOf(false) }
        var shortcutTab by rememberSaveable { mutableStateOf<Int?>(null) }
        fun attach(id: Int?) {
            ownedTab = id
            tab = id
        }
        fun launch(start: () -> Int?) {
            try {
                startTerminal(Intent(TerminalService.KEEP_ALIVE))
                val id = start()
                if (id == null) {
                    failure = "无法新建标签。请关闭一个 Bash 标签，或等待图形程序启动后重试。"
                } else attach(service.claimWindow(current.key, taskId, id))
            } catch (error: Exception) {
                failure = error.localizedMessage ?: "无法启动 Linux 会话"
            }
        }
        LaunchedEffect(service, current) {
            if (!current.reopen) {
                attach(service.attachedTab(current.key, taskId) ?: service.claimWindow(current.key, taskId))
                if (tab == null) failure = "会话已结束"
                return@LaunchedEffect
            }
            service.claimWindow(current.key, taskId)?.let { attach(it); return@LaunchedEffect }
            ownedTab?.let { service.returnWindow(it, taskId) }
            attach(null)
            if (service.installation != Installation.Ready) {
                failure = "请先在 Mobile Terminal 主界面安装 Debian。"
                return@LaunchedEffect
            }
            when (val saved = WindowLinks(this@TabWindowActivity).load(current.key)) {
                is TabLaunch.Shell -> launch { service.addTab(saved.app, saved.directory) }
                is TabLaunch.Graphics -> {
                    val app = try {
                        service.graphics.apps().firstOrNull { it.id == saved.desktopId }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        failure = "无法读取 Debian 图形应用列表。"
                        return@LaunchedEffect
                    } catch (_: LinkageError) {
                        failure = "无法加载图形运行组件。"
                        return@LaunchedEffect
                    }
                    if (app == null) failure = "快捷方式对应的图形应用已卸载或不可用。"
                    else if (app.isDebianChromium) confirm = app to saved.rendering
                    else launch { service.launchGraphics(app, false, saved.rendering, reusePending = false) }
                }
                null -> failure = "找不到此桌面快捷方式。"
            }
        }
        val id = tab
        val hosted = id != null && service.ownsWindow(id, taskId)
        LaunchedEffect(id, hosted) {
            if (id != null && !hosted) finishAndRemoveTask()
        }
        fun returnToMain() {
            id?.let { service.returnWindow(it, taskId) }
            val tasks = getSystemService(ActivityManager::class.java).appTasks
            val main = tasks.firstOrNull { task ->
                runCatching { task.taskInfo.baseActivity?.className == MainActivity::class.java.name }.getOrDefault(false)
            }
            val moved = main?.let { task -> runCatching { task.moveToFront() }.isSuccess } == true
            if (!moved) startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finishAndRemoveTask()
        }
        val title = if (hosted && id != null) service.labels().firstOrNull { it.first == service.shownInWindow(id) }?.second
            else null
        val taskLabel = title ?: getString(R.string.app_name)
        LaunchedEffect(taskLabel) { setTaskDescription(ActivityManager.TaskDescription(taskLabel)) }
        TabViewport(appearance.size, fullscreen = false, exit = {}) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(taskLabel, Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium)
                Box {
                    TextButton(onClick = { menu = true }) { Text("窗口") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(if (hosted) "收回到主界面" else "打开主界面") },
                            onClick = { menu = false; returnToMain() })
                        if (hosted && id != null) DropdownMenuItem(text = { Text("创建桌面快捷方式") },
                            onClick = { menu = false; shortcutTab = service.shownInWindow(id) })
                    }
                }
            }
            HorizontalDivider()
            when {
                hosted && id != null -> HostedTab(service, service.shownInWindow(id), Modifier.weight(1f), false) { next ->
                    service.windowFor(next)?.takeIf { it != current.key }?.let { WindowLinks.open(this@TabWindowActivity, it) }
                }
                failure != null -> Column(Modifier.padding(16.dp)) {
                    Text(checkNotNull(failure))
                    TextButton(onClick = ::returnToMain) { Text("打开主界面") }
                }
                confirm == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        shortcutTab?.let { TabShortcutDialog(service, it) { shortcutTab = null } }
        confirm?.let { (app, rendering) ->
            ChromiumLaunchConfirmation(service.graphics.acceleration, rendering, cancel = { finishAndRemoveTask() }) { mode ->
                confirm = null
                launch { service.launchGraphics(app, true, mode, reusePending = false) }
            }
        }
        BackHandler(enabled = confirm == null) { finishAndRemoveTask() }
    }
}
