package dev.warpterminal.mobile

import android.content.ComponentName
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class SessionActivity : ComponentActivity() {
    @Composable
    internal abstract fun SessionContent(
        service: TerminalService, bars: WindowInsetsControllerCompat, appearance: Appearance,
        changeAppearance: (Appearance) -> Unit,
    )

    private var terminal by mutableStateOf<TerminalService?>(null)
    protected val boundService get() = terminal
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
            val prefs = remember { getSharedPreferences("appearance", MODE_PRIVATE) }
            fun readAppearance() = Appearance.restore(prefs.getString("palette", null), prefs.getString("size", null))
            var appearance by remember { mutableStateOf(readAppearance()) }
            DisposableEffect(prefs) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                    appearance = readAppearance()
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            val bars = remember { WindowCompat.getInsetsController(window, window.decorView) }
            LaunchedEffect(appearance.palette) {
                bars.isAppearanceLightStatusBars = appearance.palette == Palette.Paper
                bars.isAppearanceLightNavigationBars = appearance.palette == Palette.Paper
            }
            MaterialTheme(colorScheme = appearance.palette.colors) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val service = terminal
                    if (service == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        SessionContent(service, bars, appearance, changeAppearance = { next ->
                            appearance = next
                            prefs.edit().putString("palette", next.palette.name).putString("size", next.size.name).apply()
                        })
                    }
                }
            }
        }
    }

    internal fun startTerminal(intent: Intent) {
        ContextCompat.startForegroundService(this, intent.setClass(this, TerminalService::class.java))
    }

    override fun onDestroy() {
        unbindService(connection)
        super.onDestroy()
    }
}

class MainActivity : SessionActivity() {
    @Composable
    internal override fun SessionContent(
        service: TerminalService, bars: WindowInsetsControllerCompat, appearance: Appearance,
        changeAppearance: (Appearance) -> Unit,
    ) = TerminalHome(service, bars, appearance, changeAppearance, ::startTerminal)
}

@Composable
private fun TerminalHome(service: TerminalService, bars: WindowInsetsControllerCompat, appearance: Appearance,
    changeAppearance: (Appearance) -> Unit, start: (Intent) -> Unit) {
    val notices = remember { SnackbarHostState() }
    val activity = LocalContext.current as Activity
    val tab = service.tabs.firstOrNull { it.id == service.selected }
    val graphics = service.graphics
    val window = graphics.tabs.firstOrNull { it.id == service.selected }
    val application = service.terminalAppTabs.firstOrNull { it.id == service.selected }
    val selected = (tab?.id ?: window?.id ?: application?.id).takeIf { service.installation == Installation.Ready }
    var owner by rememberSaveable { mutableStateOf<Int?>(null) }
    val activeOwner = fullscreenOwner(owner, selected)
    val fullscreen = activeOwner != null
    if (owner != activeOwner) SideEffect { owner = activeOwner }
    DisposableEffect(fullscreen, bars) {
        if (fullscreen) {
            val previous = bars.systemBarsBehavior
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                bars.show(WindowInsetsCompat.Type.systemBars())
                bars.systemBarsBehavior = previous
            }
        } else onDispose { }
    }
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(enabled = fullscreen && !imeOpen) { owner = null }
    var settings by rememberSaveable { mutableStateOf(false) }
    var shortcutTab by rememberSaveable { mutableStateOf<Int?>(null) }
    var showApps by rememberSaveable { mutableStateOf(false) }
    var showCommand by rememberSaveable { mutableStateOf(false) }
    var confirmChromium by remember { mutableStateOf<LinuxApp?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(service.selected) { keyboard?.hide() }
    fun activateTab(id: Int) {
        val windowKey = service.windowFor(id)
        if (windowKey == null) service.selectTab(id) else WindowLinks.open(activity, windowKey)
    }
    fun bash() = start(Intent(TerminalService.ADD_TAB))
    fun openApp(app: TerminalApp) {
        keyboard?.hide()
        start(Intent(TerminalService.ADD_TERMINAL_APP)
            .putExtra(TerminalService.APP_NAME, app.name)
            .putExtra(TerminalService.APP_COMMAND, app.command))
    }
    fun openGraphics(action: String, app: LinuxApp, rendering: ChromiumRendering = ChromiumRendering.AUTOMATIC) =
        start(Intent(action)
        .putExtra(TerminalService.APP_ID, app.id)
        .putExtra(TerminalService.APP_NAME, app.name)
        .putExtra(TerminalService.APP_FILE, app.desktopFile)
        .putExtra(TerminalService.CHROMIUM_RENDERING, rendering.name))
    TabViewport(appearance.size, fullscreen, exit = { owner = null }) {
        Row(
            Modifier.fillMaxWidth().then(if (fullscreen) Modifier else Modifier.padding(horizontal = 12.dp, vertical = 6.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!fullscreen) Text(stringResource(R.string.app_name), Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (service.installation == Installation.Ready) {
                FileActions(service.linux, notices, visible = !fullscreen)
                if (!fullscreen) ToolbarMenuButton(button = { open ->
                    FilledTonalButton(onClick = open) { Text("＋ 新标签") }
                }) { dismiss ->
                    DropdownMenuItem(text = { Text("Bash") }, enabled = service.tabs.size < TerminalService.MAX_TABS,
                        onClick = { dismiss(); bash() })
                    DropdownMenuItem(text = { Text("OpenCode") }, enabled = service.tabs.size < TerminalService.MAX_TABS,
                        onClick = { dismiss(); openApp(TerminalApp.OpenCode) })
                    DropdownMenuItem(text = { Text("Vim") }, enabled = service.tabs.size < TerminalService.MAX_TABS,
                        onClick = { dismiss(); openApp(TerminalApp.Vim) })
                    DropdownMenuItem(text = { Text("其他终端应用") }, enabled = service.tabs.size < TerminalService.MAX_TABS,
                        onClick = { dismiss(); showCommand = true })
                    DropdownMenuItem(text = { Text("图形应用") },
                        onClick = { dismiss(); showApps = true })
                }
            }
            if (!fullscreen) ToolbarMenuButton(button = { open ->
                TextButton(onClick = open) { Text("更多") }
            }) { dismiss ->
                DropdownMenuItem(text = { Text("当前标签全屏") }, enabled = selected != null,
                    onClick = { dismiss(); owner = selected })
                DropdownMenuItem(text = { Text("在独立窗口打开") },
                    enabled = selected != null && tab?.exited != true && window?.error == null,
                    onClick = {
                        dismiss()
                        keyboard?.hide()
                        selected?.let { WindowLinks.open(activity, service.keyForTab(it), adjacent = true) }
                    })
                DropdownMenuItem(text = { Text("创建桌面快捷方式") }, enabled = selected != null,
                    onClick = { dismiss(); shortcutTab = selected })
                DropdownMenuItem(text = { Text("外观设置") }, onClick = { dismiss(); settings = true })
                if (service.needsSudo) {
                    DropdownMenuItem(
                        text = { Text(if (service.installingSudo) "正在安装 sudo" else "安装 sudo") },
                        enabled = !service.installingSudo,
                        onClick = { dismiss(); start(Intent(TerminalService.INSTALL_SUDO)) },
                    )
                }
            }
        }
        if (!fullscreen) {
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            service.sudoInstallError?.let {
                Text(it, Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.error)
            }
        }

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
            Installation.Ready -> {
                if (!fullscreen) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    service.labels().forEach { (id, label) ->
                        val detached = service.windowFor(id) != null
                        FilterChip(selected = id == service.selected,
                            onClick = { activateTab(id) },
                            label = { Text(if (detached) "$label · 独立窗口" else label, Modifier.widthIn(max = 220.dp), maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis) })
                    }
                }
                if (selected == null) {
                    InstallCard(if (service.interrupted) "上次会话已结束" else "打开本地终端",
                        "Linux 主目录保存在此设备上。", "打开 Bash", ::bash)
                } else HostedTab(service, selected, Modifier.weight(1f), !fullscreen, activate = ::activateTab)
            }
        }
        SnackbarHost(notices)
    }
    shortcutTab?.let { id -> TabShortcutDialog(service, id) { shortcutTab = null } }
    if (settings) AppearanceDialog(appearance, changeAppearance) { settings = false }
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
        ChromiumLaunchConfirmation(service.graphics.acceleration, cancel = { confirmChromium = null }) { rendering ->
            confirmChromium = null
            openGraphics(TerminalService.ADD_COMPATIBLE_CHROMIUM, app, rendering)
        }
    }
}

@Composable
internal fun ChromiumLaunchConfirmation(gpu: GraphicsAcceleration, preferred: ChromiumRendering = ChromiumRendering.AUTOMATIC,
    cancel: () -> Unit, launch: (ChromiumRendering) -> Unit) {
        val result = gpu.result(GpuPath.CHROMIUM)?.takeIf { gpu.automatic }
        val webGl2Only = result?.webGl2Only == true
        AlertDialog(
            onDismissRequest = cancel,
            title = { Text("以兼容模式启动 Chromium？") },
            text = {
                Column {
                    Text("此 Linux 环境无法启用 Chromium 的沙箱。兼容模式会关闭 Chromium 沙箱，使用独立的浏览器配置。请只打开可信页面。")
                    if (webGl2Only) {
                        Spacer(Modifier.height(8.dp))
                        Text("Adreno 已通过 WebGL 2 检测。WebGL 1 仍无法使用。")
                        TextButton(onClick = {
                            launch(ChromiumRendering.WEBGL2)
                        }) { Text("WebGL 2 硬件启动") }
                    }
                    if (result?.xwaylandCompositor?.let(gpu::canProbeChromium) == true) {
                        TextButton(onClick = {
                            launch(ChromiumRendering.XWAYLAND_COMPOSITING)
                        }) { Text("XWayland 硬件合成") }
                    }
                    if (result?.softwareCompositor?.let(gpu::canProbeChromium) == true) {
                        TextButton(onClick = {
                            launch(ChromiumRendering.SOFTWARE_COMPOSITING)
                        }) { Text("软件合成（保留 WebGL 加速）") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { launch(preferred) }) {
                    Text(if (preferred == ChromiumRendering.AUTOMATIC) "兼容启动" else "按快捷方式设置启动")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = cancel) { Text("取消") }
                    TextButton(onClick = { launch(ChromiumRendering.SOFTWARE) }) { Text("软件启动") }
                }
            },
        )
}

@Composable
internal fun HostedTab(service: TerminalService, id: Int, modifier: Modifier, showToolbar: Boolean,
    windowMenu: (@Composable () -> Unit)? = null,
    activate: (Int) -> Unit) = key(id) {
    val terminal = service.tabs.firstOrNull { it.id == id }
    val application = service.terminalAppTabs.firstOrNull { it.id == id }
    val graphics = service.graphics.tabs.firstOrNull { it.id == id }
    when {
        terminal?.showingStartupApp == true -> TerminalAppPanel(terminal.title, terminal, modifier,
            showToolbar, windowMenu, closesSession = true) { service.closeTab(terminal) }
        terminal != null -> SessionPanel(terminal, modifier, showToolbar, windowMenu,
            appActive = terminal.alternate || service.terminalAppTabs.any { it.session == terminal },
            openApp = { service.openTerminalApp(terminal)?.let(activate) },
            close = { service.closeTab(terminal) })
        application != null -> TerminalAppPanel(application.title, application.session, modifier, showToolbar, windowMenu) {
            service.closeTerminalApp(application)
        }
        graphics != null -> GraphicsPanel(graphics, modifier, showToolbar, windowMenu) { service.graphics.close(graphics) }
        else -> Unit
    }
}

@Composable
internal fun TabViewport(size: TerminalSize, fullscreen: Boolean, exit: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        ToolbarMenuHost {
            CompositionLocalProvider(LocalTerminalSize provides size) {
                Column(Modifier.fillMaxSize(), content = content)
            }
            if (fullscreen) FilledTonalButton(onClick = exit,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)) { Text("退出全屏") }
        }
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
private fun GraphicsPanel(tab: GraphicsTab, modifier: Modifier, showToolbar: Boolean,
    windowMenu: (@Composable () -> Unit)?, close: () -> Unit) {
    var view by remember { mutableStateOf<GraphicsView?>(null) }
    Box(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize()) {
            if (showToolbar) AppTabToolbar(tab.title, view != null, { view?.showKeyboard() }, close, windowMenu)
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
        if (!showToolbar && windowMenu == null && view != null) FilledTonalButton(onClick = { view?.showKeyboard() },
            modifier = Modifier.align(Alignment.BottomEnd)) { Text("键盘") }
    }
}

@Composable
private fun AppTabToolbar(title: String, keyboardReady: Boolean, keyboard: () -> Unit, close: () -> Unit,
    windowMenu: (@Composable () -> Unit)?) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = keyboard, enabled = keyboardReady) { Text("键盘") }
        TextButton(onClick = close) { Text("关闭标签") }
        windowMenu?.invoke()
    }
}

@Composable
private fun TerminalAppPanel(
    title: String,
    tab: TerminalTab,
    modifier: Modifier,
    showToolbar: Boolean,
    windowMenu: (@Composable () -> Unit)?,
    closesSession: Boolean = false,
    close: () -> Unit,
) {
    var view by remember { mutableStateOf<TerminalGrid?>(null) }
    var confirmClose by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        if (showToolbar) AppTabToolbar(title, view != null, keyboard = { view?.showKeyboard() }, close = {
            if (closesSession && tab.running) confirmClose = true else close()
        }, windowMenu = windowMenu)
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
    val fontPx = gridFontPixels()
    Column(modifier.fillMaxWidth()) {
        val revision = tab.revision
        AndroidView(
            factory = { context -> TerminalGrid(context, tab, fontPx).also(onView) },
            update = { it.setFontPixels(fontPx); if (revision >= 0) it.invalidate() },
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
    showToolbar: Boolean,
    windowMenu: (@Composable () -> Unit)?,
    appActive: Boolean,
    openApp: () -> Unit,
    close: () -> Unit,
) {
    val raw = tab.rawMode && !appActive
    val state = rememberLazyListState(tab.scrollIndex, tab.scrollOffset)
    var followTail by remember { mutableStateOf(tab.followTail) }
    var outputHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            tab.scrollIndex = index
            tab.scrollOffset = offset
        }
    }
    SideEffect { tab.followTail = followTail }
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
        if (showToolbar) BoxWithConstraints(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .padding(start = 12.dp, end = 4.dp)) {
            val compact = maxWidth < if (windowMenu == null) 400.dp else 500.dp
            val modeTitle = if (appActive) "打开终端应用" else if (raw) "命令块" else "终端"
            val switchMode = { if (appActive || tab.running && !raw) openApp() else tab.rawMode = !raw }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tab.directory, Modifier.weight(1f), fontFamily = FontFamily.Monospace, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (compact) {
                    ToolbarMenuButton(button = { open ->
                        TextButton(onClick = open) { Text("操作") }
                    }) { dismiss ->
                        if (!raw && !followTail) DropdownMenuItem(text = { Text("回到最新") },
                            onClick = { dismiss(); followTail = true })
                        DropdownMenuItem(text = { Text(modeTitle) }, enabled = !tab.exited,
                            onClick = { dismiss(); switchMode() })
                        DropdownMenuItem(text = { Text("关闭标签") }, onClick = { dismiss(); close() })
                    }
                } else {
                    if (!raw && !followTail) TextButton(onClick = { followTail = true }) { Text("回到最新") }
                    TextButton(onClick = switchMode, enabled = !tab.exited) { Text(modeTitle) }
                    TextButton(onClick = close) { Text("关闭") }
                }
                windowMenu?.invoke()
            }
        }
        if (tab.directoryFallback) Text("快捷方式的工作目录不可用，已打开 $DEBIAN_HOME。",
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
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
            val fontPx = gridFontPixels()
            val measure = remember(fontPx) { TerminalGrid.renderer(fontPx) }
            var viewport by remember { mutableStateOf(IntSize.Zero) }
            LaunchedEffect(viewport, measure, appActive) {
                if (!appActive) TerminalGrid.resize(tab, measure, viewport.width, viewport.height)
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewport = it },
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
                    }, fontFamily = FontFamily.Monospace, fontSize = LocalTerminalSize.current.blockSp.sp, softWrap = true)
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
private fun FileActions(linux: LinuxEnvironment, notices: SnackbarHostState, visible: Boolean) {
    val context = LocalContext.current
    val exchange = remember(linux) { FileExchange(context.contentResolver, linux.home) }
    val scope = rememberCoroutineScope()
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
    if (visible) ToolbarMenuButton(button = { open ->
        TextButton(onClick = open) { Text("文件") }
    }) { dismiss ->
        if (transfer != null) {
            DropdownMenuItem(text = { Text("取消传输") }, onClick = { dismiss(); transfer?.cancel() })
        } else {
            DropdownMenuItem(text = { Text("导入到 Downloads") }, onClick = {
                dismiss(); importPicker.launch(arrayOf("*/*"))
            })
            DropdownMenuItem(text = { Text("从主目录导出") }, onClick = { dismiss(); browse = true })
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
