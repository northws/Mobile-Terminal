package dev.warpterminal.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import java.io.IOException
import org.json.JSONObject

internal sealed interface TabLaunch {
    data class Shell(val directory: String, val app: TerminalApp? = null) : TabLaunch
    data class Graphics(val desktopId: String, val rendering: ChromiumRendering = ChromiumRendering.AUTOMATIC) : TabLaunch
}

internal class WindowLinks(private val context: Context) {
    private val preferences = context.getSharedPreferences("tab_shortcuts", Context.MODE_PRIVATE)
    private val shortcuts = context.getSystemService(ShortcutManager::class.java)
    val canPin get() = shortcuts?.isRequestPinShortcutSupported == true

    fun load(key: String): TabLaunch? = preferences.getString(key, null)?.let { value ->
        runCatching {
            val data = JSONObject(value)
            when (data.getString("kind")) {
                "shell" -> TabLaunch.Shell(data.getString("directory"), data.optString("command")
                    .takeIf { it.isNotBlank() }?.let { TerminalApp(data.getString("name"), it) })
                "graphics" -> TabLaunch.Graphics(data.getString("desktopId"),
                    ChromiumRendering.entries.firstOrNull { it.name == data.optString("rendering") }
                        ?: ChromiumRendering.AUTOMATIC)
                else -> null
            }
        }.getOrNull()
    }

    fun save(key: String, launch: TabLaunch) {
        val data = JSONObject()
        when (launch) {
            is TabLaunch.Shell -> {
                data.put("kind", "shell").put("directory", launch.directory)
                launch.app?.let { data.put("name", it.name).put("command", it.command) }
            }
            is TabLaunch.Graphics -> data.put("kind", "graphics")
                .put("desktopId", launch.desktopId).put("rendering", launch.rendering.name)
        }
        if (!preferences.edit().putString(key, data.toString()).commit()) throw IOException("无法保存快捷方式")
    }

    fun pin(key: String, title: String): Boolean {
        val shortcut = ShortcutInfo.Builder(context, key)
            .setShortLabel(title.take(30).ifBlank { context.getString(R.string.app_name) })
            .setIcon(Icon.createWithResource(context, R.drawable.ic_launcher))
            .setIntent(Intent(Intent.ACTION_VIEW, address(key), context, TabShortcutActivity::class.java))
            .build()
        return shortcuts?.requestPinShortcut(shortcut, null) == true
    }

    companion object {
        const val REOPEN = "dev.warpterminal.mobile.REOPEN_SHORTCUT"
        private val keys = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")

        private fun address(key: String) = Uri.Builder().scheme("mobile-terminal").authority("tab").appendPath(key).build()

        fun windowKey(intent: Intent): String? = intent.data?.let { uri ->
            uri.pathSegments.singleOrNull()?.takeIf { keys.matches(it) && uri == address(it) }
        }

        fun open(context: Activity, key: String, reopen: Boolean = false, adjacent: Boolean = false) {
            val intent = Intent(if (reopen) REOPEN else Intent.ACTION_VIEW, address(key), context,
                TabWindowActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            if (adjacent) intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            context.startActivity(intent)
        }
    }
}

/** Only a stored shortcut ID crosses the launcher boundary. */
class TabShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = WindowLinks.windowKey(intent)
        if (intent.action == Intent.ACTION_VIEW && key != null && WindowLinks(this).load(key) != null) {
            WindowLinks.open(this, key, reopen = true)
        }
        if (isTaskRoot) finishAndRemoveTask() else finish()
    }
}
