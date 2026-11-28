package dev.warpterminal.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*

private class MenuEntry(val items: @Composable (dismiss: () -> Unit) -> Unit) {
    var bounds by mutableStateOf(IntRect.Zero)
    val buttonFocus = FocusRequester()
}

private val LocalToolbarMenu = staticCompositionLocalOf<MutableState<MenuEntry?>> {
    error("ToolbarMenuButton requires ToolbarMenuHost")
}

@Composable
internal fun ToolbarMenuButton(button: @Composable (open: () -> Unit) -> Unit,
    items: @Composable (dismiss: () -> Unit) -> Unit) {
    val active = LocalToolbarMenu.current
    val latest by rememberUpdatedState(items)
    val entry = remember { MenuEntry { dismiss -> latest(dismiss) } }
    Box(Modifier.onGloballyPositioned { entry.bounds = it.boundsInRoot().roundToIntRect() }
        .focusRequester(entry.buttonFocus)) {
        button { active.value = entry }
    }
    DisposableEffect(active, entry) {
        onDispose { if (active.value === entry) active.value = null }
    }
}

/** Keep the buttons and their menus on the same surface so desktop scaling applies to both. */
@Composable
internal fun ToolbarMenuHost(content: @Composable BoxScope.() -> Unit) {
    val active = remember { mutableStateOf<MenuEntry?>(null) }
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot().round() }) {
        CompositionLocalProvider(LocalToolbarMenu provides active) { content() }
        val entry = active.value ?: return@Box
        val dismiss = {
            active.value = null
            entry.buttonFocus.requestFocus()
            Unit
        }
        BackHandler(onBack = dismiss)
        Spacer(Modifier.matchParentSize().pointerInput(entry) { detectTapGestures { dismiss() } })
        val menuFocus = remember { FocusRequester() }
        Layout(modifier = Modifier.matchParentSize(), content = {
            Surface(
                modifier = Modifier.semantics { paneTitle = "菜单" }.onPreviewKeyEvent {
                    if (it.key == Key.Escape) {
                        if (it.type == KeyEventType.KeyUp) dismiss()
                        true
                    } else false
                }.focusRequester(menuFocus).focusable(),
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = 3.dp,
            ) {
                Column(Modifier.width(IntrinsicSize.Max).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                    entry.items(dismiss)
                }
            }
        }) { children, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val anchor = entry.bounds.translate(-origin.x, -origin.y)
            val top = anchor.top.coerceIn(0, height)
            val bottom = anchor.bottom.coerceIn(top, height)
            val below = height - bottom
            val menu = children.single().measure(Constraints(maxWidth = width, maxHeight = maxOf(top, below)))
            val x = if (layoutDirection == LayoutDirection.Ltr) anchor.right - menu.width else anchor.left
            val y = if (menu.height <= below) bottom else top - menu.height
            layout(width, height) { menu.place(x.coerceIn(0, width - menu.width), y) }
        }
        val focusedWindow = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(entry, focusedWindow) { if (focusedWindow) menuFocus.requestFocus() }
    }
}
