package dev.warpterminal.mobile

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LinuxAppGrid(apps: List<LinuxApp>, icons: ShortcutIcon, modifier: Modifier,
    open: (LinuxApp) -> Unit) {
    val pixels = with(LocalDensity.current) { 52.dp.roundToPx().coerceAtMost(256) }
    val cache = remember(apps, icons, pixels) { LruCache<String, Bitmap>(48) }
    val visibleApps = remember(apps) {
        val seen = mutableSetOf<Pair<String, ElectronLaunch>>()
        apps.filter { app -> app.electron?.let { seen.add(app.name to it) } ?: true }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(94.dp), modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(visibleApps, key = LinuxApp::id) { app ->
            val bitmap by produceState<Bitmap?>(cache.get(app.id), cache, app) {
                value = cache.get(app.id) ?: withContext(Dispatchers.IO) {
                    icons.load(app, null, pixels)?.also { cache.put(app.id, it) }
                }
            }
            Column(Modifier.clip(MaterialTheme.shapes.medium).clickable(role = Role.Button) { open(app) }
                .padding(horizontal = 4.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val icon = bitmap
                if (icon != null) {
                    Image(icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(52.dp))
                } else {
                    Surface(Modifier.size(52.dp), shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.secondaryContainer) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(app.name.take(1).uppercase(), Modifier.clearAndSetSemantics {},
                                style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(app.name, textAlign = TextAlign.Center, minLines = 2, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
