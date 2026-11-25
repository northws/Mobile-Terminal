package dev.warpterminal.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val LocalTerminalSize = staticCompositionLocalOf { TerminalSize.Standard }

@Composable
internal fun gridFontPixels(): Int {
    val size = LocalTerminalSize.current.gridSp.sp
    return with(LocalDensity.current) { size.roundToPx() }
}

private val mint = darkColorScheme(
    primary = Color(0xFF9CF1CE), onPrimary = Color(0xFF003827),
    secondaryContainer = Color(0xFF254339), onSecondaryContainer = Color(0xFFCCF4E5),
    background = Color(0xFF101716), surface = Color(0xFF192220),
    surfaceVariant = Color(0xFF23312E), onSurface = Color(0xFFE4F0EA),
    onSurfaceVariant = Color(0xFFABC5BA), error = Color(0xFFFFAFA3),
)
private val ocean = darkColorScheme(
    primary = Color(0xFF95CEFF), onPrimary = Color(0xFF063250),
    secondaryContainer = Color(0xFF22445D), onSecondaryContainer = Color(0xFFDBEFFF),
    background = Color(0xFF0C1722), surface = Color(0xFF152434),
    surfaceVariant = Color(0xFF293D50), onSurface = Color(0xFFE0EEFD),
    onSurfaceVariant = Color(0xFFB4C9DC), surfaceContainerHigh = Color(0xFF203345),
)
private val paper = lightColorScheme(
    primary = Color(0xFF695017), onPrimary = Color.White,
    secondaryContainer = Color(0xFFF2E6CA), onSecondaryContainer = Color(0xFF44371A),
    background = Color(0xFFFAF8F1), surface = Color(0xFFFFFDF8),
    surfaceVariant = Color(0xFFE9E3D5), onSurface = Color(0xFF28251F),
    onSurfaceVariant = Color(0xFF585347), surfaceContainerHigh = Color(0xFFF1ECDF),
)

internal val Palette.colors get() = when (this) {
    Palette.Mint -> mint
    Palette.Ocean -> ocean
    Palette.Paper -> paper
}

@Composable
internal fun AppearanceDialog(value: Appearance, change: (Appearance) -> Unit, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text("外观设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("界面配色")
            Palette.entries.forEach { palette ->
                FilterChip(selected = palette == value.palette, onClick = { change(value.copy(palette = palette)) },
                    label = { Text(palette.label) }, leadingIcon = {
                        Box(Modifier.size(22.dp).background(palette.colors.background, CircleShape),
                            contentAlignment = Alignment.Center) {
                            Box(Modifier.size(12.dp).background(palette.colors.primary, CircleShape))
                        }
                    })
            }
            Text("终端字号")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TerminalSize.entries.forEach { size ->
                    FilterChip(selected = size == value.size, onClick = { change(value.copy(size = size)) },
                        label = { Text(size.label) })
                }
            }
            Text("$ echo 你好", fontFamily = FontFamily.Monospace, fontSize = value.size.blockSp.sp,
                color = MaterialTheme.colorScheme.primary)
        }
    }, dismissButton = { TextButton(onClick = { change(Appearance()) }) { Text("恢复默认") } },
        confirmButton = { TextButton(onClick = close) { Text("完成") } })
}
