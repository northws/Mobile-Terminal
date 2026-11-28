package dev.warpterminal.mobile

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TabShortcutDialog(service: TerminalService, id: Int, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val links = remember(context) { WindowLinks(context) }
    val title = service.labels().firstOrNull { it.first == id }?.second
    val terminal = service.tabs.firstOrNull { it.id == id }
    val tui = service.terminalAppTabs.firstOrNull { it.id == id }
    val graphics = service.graphics.tabs.firstOrNull { it.id == id }
    val directory = (terminal ?: tui?.session)?.directory ?: DEBIAN_HOME
    val knownTui = tui?.title?.trim()?.let { command ->
        listOf(TerminalApp.Vim, TerminalApp.OpenCode).firstOrNull { command == it.command }
    }
    val known = when {
        terminal != null -> TabLaunch.Shell(directory, terminal.app)
        knownTui != null -> TabLaunch.Shell(directory, knownTui)
        else -> null
    }
    var apps by remember { mutableStateOf<List<LinuxApp>?>(null) }
    var loaded by remember { mutableStateOf(known != null || !links.canPin) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var command by rememberSaveable { mutableStateOf("") }
    var selectedApp by rememberSaveable { mutableStateOf<String?>(null) }
    var showApps by rememberSaveable { mutableStateOf(false) }
    var chosenDirectory by rememberSaveable { mutableStateOf<String?>(null) }
    val windowDirectory by produceState<String?>(null, graphics?.processId) {
        value = withContext(Dispatchers.IO) { service.linux.graphicalWorkingDirectory(graphics?.processId ?: 0) }
    }
    LaunchedEffect(service, id) {
        if (loaded) return@LaunchedEffect
        try {
            apps = service.graphics.apps()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            apps = emptyList()
        } catch (_: LinkageError) {
            apps = emptyList()
        }
        loaded = true
    }
    val recognised = graphics?.desktopId?.let { candidate ->
        apps.orEmpty().firstOrNull { it.id == candidate } ?: apps.orEmpty().singleOrNull {
            it.id.removeSuffix(".desktop").equals(candidate.removeSuffix(".desktop"), ignoreCase = true)
        }
    }
    val suggested = known ?: recognised?.let {
        TabLaunch.Graphics(it.id, graphics?.rendering ?: ChromiumRendering.AUTOMATIC)
    }
    val picked = apps.orEmpty().firstOrNull { it.id == selectedApp }
    val launch = suggested ?: picked?.let { TabLaunch.Graphics(it.id) }
        ?: TabLaunch.Shell(chosenDirectory?.trim()?.takeIf { it.startsWith("/") } ?: windowDirectory ?: directory,
            command.trim().takeIf { it.isNotBlank() }?.let { TerminalApp(it.take(80), it) })
    AlertDialog(
        onDismissRequest = { if (!saving) close() },
        title = { Text("创建桌面快捷方式") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !links.canPin -> Text("当前桌面不支持由应用添加固定快捷方式。可以从主界面的标签栏继续打开独立窗口。")
                    title == null -> Text("标签已关闭。")
                    !loaded -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    suggested != null -> Text("会话结束后，快捷方式会重新打开「${title}」。")
                    else -> {
                        Text("会话结束后要打开的应用。留空会在原工作目录打开 Bash。")
                        OutlinedTextField(command, {
                            if (it.length <= 4096) {
                                command = it
                                selectedApp = null
                            }
                        }, label = { Text("终端应用命令") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (graphics != null && picked == null) OutlinedTextField(
                            chosenDirectory ?: windowDirectory ?: DEBIAN_HOME,
                            { if (it.length <= 4096) chosenDirectory = it },
                            label = { Text("Bash 或终端应用的工作目录") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = { showApps = !showApps }, enabled = !apps.isNullOrEmpty()) {
                            Text(picked?.name ?: "选择已安装的图形应用")
                        }
                        if (showApps) LinuxAppGrid(apps.orEmpty(), service.graphics.appIcons,
                            Modifier.fillMaxWidth().heightIn(max = 220.dp), open = { app ->
                                selectedApp = app.id
                                command = ""
                                showApps = false
                            })
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (links.canPin && title != null && loaded) TextButton(enabled = !saving, onClick = {
                saving = true
                val key = service.keyForTab(id)
                val iconApp = recognised ?: picked
                val windowIcon = graphics?.iconPath.takeIf { picked == null }
                scope.launch {
                    try {
                        val bitmap = withContext(Dispatchers.IO) {
                            links.save(key, launch)
                            if (iconApp == null && windowIcon == null) null else
                                ShortcutIcon(service.linux.desktopRoot, service.linux.home).load(iconApp, windowIcon, links.iconSize)
                        }
                        val result = links.pin(key, title, bitmap)
                        if (result != WindowLinks.PinResult.FAILED) {
                            val message = if (result == WindowLinks.PinResult.UPDATED) "已更新桌面快捷方式"
                                else "请按桌面提示添加快捷方式"
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            close()
                        } else error = "桌面未接受快捷方式请求。"
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        error = "无法创建桌面快捷方式。"
                    } finally {
                        saving = false
                    }
                }
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = close, enabled = !saving) { Text(if (links.canPin) "取消" else "完成") } },
    )
}
