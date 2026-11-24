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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
                        TerminalHome(service) { action, app ->
                            val intent = Intent(this, TerminalService::class.java).setAction(action)
                            if (app != null) {
                                intent.putExtra(TerminalService.APP_ID, app.id)
                                intent.putExtra(TerminalService.APP_NAME, app.name)
                                intent.putExtra(TerminalService.APP_FILE, app.desktopFile)
                            }
                            ContextCompat.startForegroundService(
                                this, intent,
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
private fun TerminalHome(service: TerminalService, start: (String, LinuxApp?) -> Unit) {
    val notices = remember { SnackbarHostState() }
    val tab = service.tabs.firstOrNull { it.id == service.selected }
    val graphics = service.graphics
    val window = graphics.tabs.firstOrNull { it.id == service.selected }
    var menu by remember { mutableStateOf(false) }
    var showApps by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    fun bash() = start(TerminalService.ADD_TAB, null)
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
                        DropdownMenuItem(text = { Text("图形应用") },
                            onClick = { menu = false; showApps = true })
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        when (val state = service.installation) {
            Installation.Absent -> InstallCard("在手机上安装 Debian", "首次安装需要联网和 600 MB 可用空间。") {
                start(TerminalService.INSTALL, null)
            }
            is Installation.Working -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(state.message, style = MaterialTheme.typography.titleMedium)
                if (state.percent != null) LinearProgressIndicator(progress = { state.percent }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is Installation.Failed -> InstallCard("安装未完成", state.message, "重试") {
                start(TerminalService.INSTALL, null)
            }
            Installation.Ready -> if (tab == null && window == null) {
                InstallCard(if (service.interrupted) "上次会话已结束" else "打开本地终端",
                    "Linux 主目录保存在此设备上。", "打开 Bash", ::bash)
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val labels = service.tabs.map { it.id to if (it.exited) "Bash ${it.id} · 已结束" else "Bash ${it.id}" } +
                        graphics.tabs.map { it.id to it.title }
                    labels.sortedBy { it.first }.forEach { (id, label) ->
                        FilterChip(selected = id == service.selected,
                            onClick = {
                                if (id != service.selected) keyboard?.hide()
                                service.selectTab(id)
                            },
                            label = { Text(label, Modifier.widthIn(max = 180.dp), maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis) })
                    }
                }
                if (tab != null) {
                    key(tab.id) { SessionPanel(tab, Modifier.weight(1f), close = { service.closeTab(tab) }) }
                } else if (window != null) {
                    key(window.id) { GraphicsPanel(window, Modifier.weight(1f)) { graphics.close(window) } }
                }
            }
        }
        SnackbarHost(notices)
    }
    if (showApps && service.installation == Installation.Ready) {
        GraphicsPicker(graphics, close = { showApps = false },
            install = { start(TerminalService.INSTALL_GRAPHICS, null) },
            open = { app -> showApps = false; keyboard?.hide(); start(TerminalService.ADD_GRAPHICS, app) })
    }
}

@Composable
private fun GraphicsPicker(graphics: GraphicsSessions, close: () -> Unit, install: () -> Unit, open: (LinuxApp) -> Unit) {
    var apps by remember { mutableStateOf<List<LinuxApp>?>(null) }
    var scanError by remember { mutableStateOf(false) }
    LaunchedEffect(graphics.installRevision) {
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
                when {
                    scanError -> Text("无法读取已安装的图形应用")
                    apps == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    apps?.isEmpty() == true -> Text("Debian 中还没有图形应用。")
                    else -> LazyColumn(Modifier.heightIn(max = 300.dp)) {
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tab.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { view?.showKeyboard() }, enabled = view != null) { Text("键盘") }
            TextButton(onClick = close) { Text("关闭标签") }
        }
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
private fun InstallCard(title: String, body: String, action: String = "安装 Debian", onClick: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun SessionPanel(tab: TerminalTab, modifier: Modifier, close: () -> Unit) {
    val raw = tab.alternate || tab.rawMode
    val clipboard = LocalClipboardManager.current
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tab.directory, Modifier.weight(1f), fontFamily = FontFamily.Monospace, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { tab.rawMode = !tab.rawMode }, enabled = !tab.alternate && !tab.exited) {
                Text(if (tab.rawMode) "命令块" else "终端")
            }
            TextButton(onClick = close) { Text("关闭") }
        }
        tab.error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        if (raw) {
            val revision = tab.revision
            AndroidView(
                factory = { TerminalGrid(it, tab).apply { requestFocus() } },
                update = { if (revision >= 0) it.invalidate() },
                modifier = Modifier.fillMaxWidth().weight(1f),
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
        } else {
            val state = rememberLazyListState()
            val lastBlock = tab.blocks.lastOrNull()
            LaunchedEffect(lastBlock?.id, lastBlock?.code) {
                if (tab.blocks.isNotEmpty()) state.scrollToItem(tab.blocks.lastIndex)
            }
            val context = LocalContext.current
            val measure = remember(context) { TerminalGrid.renderer(context) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().onSizeChanged { size ->
                TerminalGrid.resize(tab, measure, size.width, size.height)
            }, state = state, contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tab.blocks.isEmpty()) item {
                    Text(if (tab.ready) "输入第一条 Bash 命令" else if (tab.exited) "会话已结束" else "正在启动 Bash",
                        Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(tab.blocks, key = { it.id }) { block -> BlockCard(block) }
            }
            if (!tab.exited) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(tab.draft, { if (it.length <= 16_384) tab.draft = it },
                        Modifier.weight(1f), label = { Text("Bash 命令") }, maxLines = 5,
                        enabled = tab.ready, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace))
                    if (tab.ready) {
                        Button(onClick = tab::submit, enabled = tab.draft.isNotBlank()) { Text("运行") }
                    } else {
                        FilledTonalButton(onClick = { tab.input(byteArrayOf(3)) },
                            enabled = tab.blocks.lastOrNull()?.code == null && tab.blocks.isNotEmpty()) {
                            Text("Ctrl+C")
                        }
                    }
                }
            }
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
            if (block.lines.isNotEmpty()) {
                SelectionContainer(Modifier.padding(top = 8.dp)) {
                    Text(buildAnnotatedString {
                        block.lines.forEach { line ->
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
                if (block.lines.isNotEmpty()) TextButton(onClick = {
                    clipboard.setText(AnnotatedString(block.lines.joinToString("") { it.text }))
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
