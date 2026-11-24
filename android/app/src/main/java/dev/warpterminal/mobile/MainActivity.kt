package dev.warpterminal.mobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var terminal by mutableStateOf<TerminalService?>(null)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            terminal = (service as TerminalService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName) { terminal = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        bindService(Intent(this, TerminalService::class.java), connection, Context.BIND_AUTO_CREATE)
        setContent {
            MaterialTheme(colorScheme = WarpColors) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val service = terminal
                    if (service == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        TerminalHome(service) { intent ->
                            ContextCompat.startForegroundService(
                                this, intent.setClass(this, TerminalService::class.java),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        unbindService(connection)
        super.onDestroy()
    }
}

private val WarpColors = darkColorScheme(
    primary = Color(0xFF9CF1CE),
    onPrimary = Color(0xFF003827),
    secondaryContainer = Color(0xFF254339),
    onSecondaryContainer = Color(0xFFCCF4E5),
    background = Color(0xFF101716),
    surface = Color(0xFF192220),
    surfaceVariant = Color(0xFF23312E),
    onSurface = Color(0xFFE4F0EA),
    onSurfaceVariant = Color(0xFFABC5BA),
    error = Color(0xFFFFAFA3),
)

@Composable
private fun TerminalHome(service: TerminalService, start: (Intent) -> Unit) {
    val notices = remember { SnackbarHostState() }
    val tab = service.tabs.firstOrNull { it.id == service.selected }
    val graphics = service.graphics
    val window = graphics.tabs.firstOrNull { it.id == service.selected }
    val application = service.terminalAppTabs.firstOrNull { it.id == service.selected }
    val commandBlockStates = rememberSaveableStateHolder()
    val sessionIds = service.tabs.map(TerminalTab::id)
    var previousSessionIds by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    LaunchedEffect(sessionIds) {
        (previousSessionIds - sessionIds.toSet()).forEach(commandBlockStates::removeState)
        previousSessionIds = sessionIds
    }
    var menu by remember { mutableStateOf(false) }
    var showApps by rememberSaveable { mutableStateOf(false) }
    var showCommand by rememberSaveable { mutableStateOf(false) }
    var confirmChromium by remember { mutableStateOf<LinuxApp?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(service.selected) { keyboard?.hide() }
    fun bash() = start(Intent(TerminalService.ADD_TAB))
    fun openApp(app: TerminalApp) {
        keyboard?.hide()
        start(Intent(TerminalService.ADD_TERMINAL_APP)
            .putExtra(TerminalService.APP_NAME, app.name)
            .putExtra(TerminalService.APP_COMMAND, app.command))
    }
    fun openGraphics(action: String, app: LinuxApp, software: Boolean = false) = start(Intent(action)
        .putExtra(TerminalService.APP_ID, app.id)
        .putExtra(TerminalService.APP_NAME, app.name)
        .putExtra(TerminalService.APP_FILE, app.desktopFile)
        .putExtra(TerminalService.SOFTWARE_GRAPHICS, software))
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            if (service.installation == Installation.Ready) {
                FileActions(service.linux, notices)
                Box {
                    FilledTonalButton(onClick = { menu = true }) { Text("＋ 新标签") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Bash") }, enabled = service.tabs.size < TerminalService.MAX_TABS,
                            onClick = { menu = false; bash() })
                        DropdownMenuItem(text = { Text("OpenCode") }, enabled = service.tabs.size < TerminalService.MAX_TABS,
                            onClick = { menu = false; openApp(TerminalApp.OpenCode) })
                        DropdownMenuItem(text = { Text("Vim") }, enabled = service.tabs.size < TerminalService.MAX_TABS,
                            onClick = { menu = false; openApp(TerminalApp.Vim) })
                        DropdownMenuItem(text = { Text("其他终端应用") }, enabled = service.tabs.size < TerminalService.MAX_TABS,
                            onClick = { menu = false; showCommand = true })
                        DropdownMenuItem(text = { Text("图形应用") },
                            onClick = { menu = false; showApps = true })
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        when (val state = service.installation) {
            Installation.Absent -> InstallCard("在手机上安装 Debian", "首次安装需要联网和 600 MB 可用空间。") {
                start(Intent(TerminalService.INSTALL))
            }
            is Installation.Working -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(state.message, style = MaterialTheme.typography.titleMedium)
                if (state.percent != null) LinearProgressIndicator(progress = { state.percent }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is Installation.Failed -> InstallCard("安装未完成", state.message, "重试") {
                start(Intent(TerminalService.INSTALL))
            }
            Installation.Ready -> if (tab == null && window == null && application == null) {
                InstallCard(if (service.interrupted) "上次会话已结束" else "打开本地终端",
                    "Linux 主目录保存在此设备上。", "打开 Bash", ::bash)
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val labels = service.tabs.map { it.id to if (it.exited) "${it.title} · 已结束" else it.title } +
                        service.terminalAppTabs.map { it.id to it.title } + graphics.tabs.map { it.id to it.title }
                    labels.sortedBy { it.first }.forEach { (id, label) ->
                        FilterChip(selected = id == service.selected,
                            onClick = { service.selectTab(id) },
                            label = { Text(label, Modifier.widthIn(max = 180.dp), maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis) })
                    }
                }
                if (tab != null) {
                    key(tab.id) {
                        if (tab.showingStartupApp) {
                            TerminalAppPanel(tab.title, tab, Modifier.weight(1f), closesSession = true) {
                                service.closeTab(tab)
                            }
                        } else {
                            commandBlockStates.SaveableStateProvider(tab.id) {
                                SessionPanel(tab, Modifier.weight(1f),
                                    appActive = tab.alternate || service.terminalAppTabs.any { it.session == tab },
                                    openApp = { service.openTerminalApp(tab) },
                                    close = { service.closeTab(tab) })
                            }
                        }
                    }
                } else if (application != null) {
                    key(application.id) {
                        TerminalAppPanel(application.title, application.session, Modifier.weight(1f)) {
                            service.closeTerminalApp(application)
                        }
                    }
                } else if (window != null) {
                    key(window.id) { GraphicsPanel(window, Modifier.weight(1f)) { graphics.close(window) } }
                }
            }
        }
        SnackbarHost(notices)
    }
    if (showCommand && service.installation == Installation.Ready) {
        TerminalCommandPicker(close = { showCommand = false }, open = {
            showCommand = false
            openApp(it)
        })
    }
    if (showApps && service.installation == Installation.Ready) {
        GraphicsPicker(graphics, close = { showApps = false },
            install = { start(Intent(TerminalService.INSTALL_GRAPHICS)) },
            open = { app ->
                showApps = false
                keyboard?.hide()
                if (app.isDebianChromium) confirmChromium = app else openGraphics(TerminalService.ADD_GRAPHICS, app)
            })
    }
    confirmChromium?.let { app ->
        AlertDialog(
            onDismissRequest = { confirmChromium = null },
            title = { Text("以兼容模式启动 Chromium？") },
            text = { Text("此 Linux 环境无法启用 Chromium 的沙箱。兼容模式会关闭 Chromium 沙箱，使用独立的浏览器配置。请只打开可信页面。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmChromium = null
                    openGraphics(TerminalService.ADD_COMPATIBLE_CHROMIUM, app)
                }) { Text("兼容启动") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmChromium = null }) { Text("取消") }
                    TextButton(onClick = {
                        confirmChromium = null
                        openGraphics(TerminalService.ADD_COMPATIBLE_CHROMIUM, app, software = true)
                    }) { Text("软件启动") }
                }
            },
        )
    }
}

@Composable
private fun TerminalCommandPicker(close: () -> Unit, open: (TerminalApp) -> Unit) {
    var command by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("打开终端应用") },
        text = {
            OutlinedTextField(command, { if (it.length <= 4096) command = it },
                label = { Text("命令与参数") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(enabled = command.isNotBlank(), onClick = {
                val input = command.trim()
                open(TerminalApp(input.take(80), input))
            }) { Text("打开") }
        },
        dismissButton = { TextButton(onClick = close) { Text("取消") } },
    )
}

@Composable
private fun GraphicsPicker(graphics: GraphicsSessions, close: () -> Unit, install: () -> Unit, open: (LinuxApp) -> Unit) {
    var apps by remember { mutableStateOf<List<LinuxApp>?>(null) }
    var scanError by remember { mutableStateOf(false) }
    LaunchedEffect(graphics.installRevision) {
        graphics.detectGraphics(includeChromium = true)
        scanError = false
        try {
            apps = graphics.apps()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            scanError = true
        } catch (_: LinkageError) {
            scanError = true
        }
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("图形应用") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val gpu = graphics.acceleration
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("图形渲染", Modifier.weight(1f))
                    TextButton(onClick = { graphics.setAutomaticGraphics(true) }, enabled = !gpu.automatic) {
                        Text("自动")
                    }
                    TextButton(onClick = { graphics.setAutomaticGraphics(false) }, enabled = gpu.automatic) {
                        Text("软件")
                    }
                }
                if (gpu.automatic && gpu.potentialAdreno) {
                    for (path in GpuPath.entries) {
                        val result = gpu.result(path)
                        if (result != null || gpu.running == path) {
                            val mode = when {
                                gpu.running == path -> "正在检测"
                                result?.hardware == true -> result.detail
                                else -> "软件渲染（${result?.detail.orEmpty()}）"
                            }
                            Text("${path.label} · $mode", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = { graphics.detectGraphics(includeChromium = true, retry = true) },
                        enabled = gpu.running == null) { Text("重新检测") }
                } else {
                    Text("新启动的程序使用软件渲染", style = MaterialTheme.typography.bodySmall)
                }
                when {
                    scanError -> Text("无法读取已安装的图形应用")
                    apps == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    apps?.isEmpty() == true -> Text("Debian 中还没有图形应用。")
                    else -> LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 300.dp)) {
                        items(apps.orEmpty(), key = LinuxApp::id) { app ->
                            TextButton(onClick = { open(app) }, modifier = Modifier.fillMaxWidth()) { Text(app.name) }
                        }
                    }
                }
                graphics.installStatus?.let {
                    Text(it)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                graphics.installError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = install, enabled = graphics.installStatus == null) { Text("安装示例应用") }
        },
        dismissButton = { TextButton(onClick = close) { Text("完成") } },
    )
}

@Composable
private fun GraphicsPanel(tab: GraphicsTab, modifier: Modifier, close: () -> Unit) {
    var view by remember { mutableStateOf<GraphicsView?>(null) }
    Column(modifier.fillMaxWidth()) {
        AppTabToolbar(tab.title, view != null, keyboard = { view?.showKeyboard() }, close = close)
        val error = tab.error
        when {
            error != null -> Text(error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            tab.pending -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("正在打开 ${tab.title}")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            else -> AndroidView(
                factory = { context -> GraphicsView(context, tab.windowId).also { view = it } },
                modifier = Modifier.fillMaxSize(),
                onRelease = { view = null },
            )
        }
    }
}

@Composable
private fun AppTabToolbar(title: String, keyboardReady: Boolean, keyboard: () -> Unit, close: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = keyboard, enabled = keyboardReady) { Text("键盘") }
        TextButton(onClick = close) { Text("关闭标签") }
    }
}

@Composable
private fun TerminalAppPanel(
    title: String,
    tab: TerminalTab,
    modifier: Modifier,
    closesSession: Boolean = false,
    close: () -> Unit,
) {
    var view by remember { mutableStateOf<TerminalGrid?>(null) }
    var confirmClose by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        AppTabToolbar(title, view != null, keyboard = { view?.showKeyboard() }, close = {
            if (closesSession && tab.running) confirmClose = true else close()
        })
        TerminalSurface(tab, Modifier.weight(1f)) { view = it }
    }
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("关闭终端应用？") },
            text = { Text("如有未保存的更改，请先在应用内保存。关闭标签将结束此会话。") },
            confirmButton = { TextButton(onClick = close) { Text("关闭标签") } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun TerminalSurface(tab: TerminalTab, modifier: Modifier, onView: (TerminalGrid?) -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    Column(modifier.fillMaxWidth()) {
        val revision = tab.revision
        AndroidView(
            factory = { context -> TerminalGrid(context, tab).also(onView) },
            update = { if (revision >= 0) it.invalidate() },
            modifier = Modifier.fillMaxWidth().weight(1f),
            onRelease = { onView(null) },
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { tab.control() }) { Text("Ctrl") }
            TextButton(onClick = { tab.input(byteArrayOf(3)) }) { Text("Ctrl+C") }
            Shortcut("Esc", KeyEvent.KEYCODE_ESCAPE, tab)
            Shortcut("Tab", KeyEvent.KEYCODE_TAB, tab)
            Shortcut("←", KeyEvent.KEYCODE_DPAD_LEFT, tab)
            Shortcut("↑", KeyEvent.KEYCODE_DPAD_UP, tab)
            Shortcut("↓", KeyEvent.KEYCODE_DPAD_DOWN, tab)
            Shortcut("→", KeyEvent.KEYCODE_DPAD_RIGHT, tab)
            TextButton(onClick = { clipboard.getText()?.text?.let(tab::paste) }) { Text("粘贴") }
        }
    }
}

@Composable
private fun InstallCard(title: String, body: String, action: String = "安装 Debian", onClick: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun SessionPanel(
    tab: TerminalTab,
    modifier: Modifier,
    appActive: Boolean,
    openApp: () -> Unit,
    close: () -> Unit,
) {
    val raw = tab.rawMode && !appActive
    val state = rememberLazyListState()
    var followTail by rememberSaveable { mutableStateOf(true) }
    var outputHeight by remember { mutableIntStateOf(0) }
    val manualScroll = remember(state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0 && state.canScrollBackward) {
                    followTail = false
                }
                return Offset.Zero
            }
        }
    }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tab.directory, Modifier.weight(1f), fontFamily = FontFamily.Monospace, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!raw && !followTail) {
                TextButton(onClick = { followTail = true }) { Text("回到最新") }
            }
            if (appActive) {
                TextButton(onClick = openApp) { Text("打开终端应用") }
            } else {
                TextButton(onClick = { if (tab.running && !raw) openApp() else tab.rawMode = !raw },
                    enabled = !tab.exited) {
                    Text(if (raw) "命令块" else "终端")
                }
            }
            TextButton(onClick = close) { Text("关闭") }
        }
        tab.error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        if (raw) {
            TerminalSurface(tab, Modifier.weight(1f))
        } else {
            val lastBlock = tab.blocks.lastOrNull()
            LaunchedEffect(state) {
                snapshotFlow {
                    state.layoutInfo.totalItemsCount > 0 && !state.isScrollInProgress && !state.canScrollForward
                }.collect { atTail ->
                    if (atTail) followTail = true
                }
            }
            LaunchedEffect(lastBlock?.id, lastBlock?.outputRevision, lastBlock?.code, followTail, outputHeight) {
                if (followTail && tab.blocks.isNotEmpty()) state.requestScrollToItem(tab.blocks.size)
            }
            val context = LocalContext.current
            val measure = remember(context) { TerminalGrid.renderer(context) }
            Column(
                Modifier.weight(1f).fillMaxWidth().onSizeChanged { size ->
                    if (!appActive) TerminalGrid.resize(tab, measure, size.width, size.height)
                },
            ) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().nestedScroll(manualScroll).onSizeChanged { outputHeight = it.height },
                    state = state, contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (tab.blocks.isEmpty()) item {
                        Text(if (tab.ready) "输入第一条 Bash 命令" else if (tab.exited) "会话已结束" else "正在启动 Bash",
                            Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(tab.blocks, key = { it.id }) { block -> BlockCard(block) }
                    if (tab.blocks.isNotEmpty()) item(key = "output-tail") { Spacer(Modifier.height(1.dp)) }
                }
                tab.statusLine?.let { CommandStatus(it) }
            }
            if (!tab.exited && !appActive) {
                val running = tab.running
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    key(running) {
                        OutlinedTextField(
                            value = if (running) tab.replyDraft else tab.draft,
                            onValueChange = {
                                if (it.length <= 16_384) {
                                    if (running) tab.replyDraft = it else if (tab.ready) tab.draft = it
                                }
                            },
                            modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                                val key = event.nativeKeyEvent
                                if (tab.running && (key.keyCode == KeyEvent.KEYCODE_ENTER ||
                                        key.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                                ) {
                                    if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) tab.sendReply()
                                    true
                                } else false
                            },
                            label = { Text(if (running) "进程输入" else "Bash 命令") },
                            singleLine = running,
                            maxLines = if (running) 1 else 5,
                            enabled = tab.ready || running,
                            keyboardOptions = if (running) KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send)
                                else KeyboardOptions.Default,
                            keyboardActions = KeyboardActions(onSend = { tab.sendReply() }),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                    if (running) {
                        Button(onClick = tab::sendReply) { Text("发送") }
                        FilledTonalButton(onClick = { tab.input(byteArrayOf(3)) },
                            enabled = tab.running) {
                            Text("Ctrl+C")
                        }
                    } else {
                        Button(onClick = tab::submit, enabled = tab.ready && tab.draft.isNotBlank()) { Text("运行") }
                    }
                }
            }
        }
    }
}

private val progressStatus = Regex("""(.+\[\s*([0-9]{1,3})%\])(?:\s+\[[ .#\u2580-\u259f]*\])?""")

@Composable
private fun CommandStatus(status: String) {
    val progress = remember(status) { progressStatus.matchEntire(status) }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(progress?.groupValues?.get(1) ?: status,
            if (progress == null) Modifier.weight(1f) else Modifier.widthIn(max = 180.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (progress != null) {
            val fraction = progress.groupValues[2].toInt().coerceIn(0, 100) / 100f
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun Shortcut(label: String, key: Int, tab: TerminalTab) {
    TextButton(onClick = { tab.key(key) }) { Text(label) }
}

@Composable
private fun BlockCard(block: CommandBlock) {
    val clipboard = LocalClipboardManager.current
    val output = block.lines
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(block.command, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                }
                val code = block.code
                Text(if (code == null) "运行中" else "退出 $code", fontSize = 12.sp,
                    color = if (code != null && code != 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            if (output.isNotEmpty()) {
                SelectionContainer(Modifier.padding(top = 8.dp)) {
                    Text(buildAnnotatedString {
                        output.forEach { line ->
                            val color = line.foreground?.let { Color(it or 0xff000000.toInt()) } ?: Color.Unspecified
                            withStyle(SpanStyle(color = color, fontWeight = if (line.bold) FontWeight.Bold else null)) {
                                append(line.text)
                            }
                        }
                    }, fontFamily = FontFamily.Monospace, fontSize = 13.sp, softWrap = true)
                }
            }
            if (block.truncated) Text("命令块输出已截断。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton(onClick = { clipboard.setText(AnnotatedString(block.command)) }) { Text("复制命令") }
                if (output.isNotEmpty()) TextButton(onClick = {
                    clipboard.setText(AnnotatedString(output.joinToString("") { it.text }))
                }) { Text("复制输出") }
            }
        }
    }
}

@Composable
private fun FileActions(linux: LinuxEnvironment, notices: SnackbarHostState) {
    val context = LocalContext.current
    val exchange = remember(linux) { FileExchange(context.contentResolver, linux.home) }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var browse by rememberSaveable { mutableStateOf(false) }
    var exportPath by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var transfer by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun launchTransfer(action: suspend () -> String) {
        if (transfer != null) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error.localizedMessage ?: "无法完成文件传输"
            } finally {
                transfer = null
            }
            notices.showSnackbar(result)
        }
        transfer = job
        job.start()
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) launchTransfer { "已导入 ${exchange.import(uri)}" }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val path = exportPath.toList()
        if (uri != null && path.isNotEmpty()) launchTransfer {
            exchange.export(path, uri)
            "已导出 ${path.last()}"
        }
    }
    Box {
        TextButton(onClick = { menu = true }) { Text("文件") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (transfer != null) {
                DropdownMenuItem(text = { Text("取消传输") }, onClick = { menu = false; transfer?.cancel() })
            } else {
                DropdownMenuItem(text = { Text("导入到 Downloads") }, onClick = {
                    menu = false; importPicker.launch(arrayOf("*/*"))
                })
                DropdownMenuItem(text = { Text("从主目录导出") }, onClick = { menu = false; browse = true })
            }
        }
    }
    if (browse) HomePicker(exchange, close = { browse = false }) { path ->
        exportPath = ArrayList(path)
        browse = false
        exportPicker.launch(path.last())
    }
}

@Composable
private fun HomePicker(exchange: FileExchange, close: () -> Unit, choose: (List<String>) -> Unit) {
    var path by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var entries by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        entries = emptyList()
        failure = null
        try {
            entries = withContext(Dispatchers.IO) { exchange.list(path) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failure = "无法打开这个目录"
        }
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("~/${path.joinToString("/")}", fontFamily = FontFamily.Monospace) },
        text = {
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                if (path.isNotEmpty()) item {
                    TextButton(onClick = { path = ArrayList(path.dropLast(1)) }) { Text("上一级") }
                }
                failure?.let { message -> item { Text(message) } }
                items(entries, key = { it.first }) { (name, directory) ->
                    TextButton(onClick = {
                        if (directory) path = ArrayList(path + name)
                        else choose(path + name)
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (directory) "$name/" else name) }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("取消") } },
    )
}
