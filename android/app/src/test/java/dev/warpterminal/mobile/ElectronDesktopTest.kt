package dev.warpterminal.mobile

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ElectronDesktopTest {
    @get:Rule val temp = TemporaryFolder()
    private val root get() = temp.root
    private val home get() = File(root, "private-home").apply { mkdirs() }
    private val flags = listOf("--no-sandbox", "--ozone-platform=wayland", "--disable-gpu")
    private fun file(path: String) = File(root, path).apply { parentFile!!.mkdirs(); createNewFile() }
    private fun entry(path: String, exec: String, fields: String = ""): ElectronLaunch? {
        val target = if (path.startsWith("$DEBIAN_HOME/")) File(home, path.removePrefix("$DEBIAN_HOME/")) else File(root, path)
        target.parentFile!!.mkdirs()
        target.writeText("[Desktop Entry]\nExec=$exec\n$fields\n[Desktop Action Other]\nExec=ignore\n")
        return ElectronDesktop(root, home).read(path, "My App")
    }

    @Test fun chatgptPathWrapperAndGuestAbsoluteSymlinks() {
        file("usr/lib/chatgpt/codex-launcher").setExecutable(true)
        file("usr/lib/chatgpt/resources/app.asar")
        val bin = temp.newFolder("usr", "bin").toPath()
        Files.createSymbolicLink(bin.resolve("chatgpt"), Paths.get("/etc/alternatives/chatgpt"))
        val alternatives = temp.newFolder("etc", "alternatives").toPath()
        Files.createSymbolicLink(alternatives.resolve("chatgpt"), Paths.get("../../usr/lib/chatgpt/codex-launcher"))
        val app = entry("/usr/share/applications/chatgpt.desktop", "chatgpt %U")
        assertEquals(listOf("chatgpt") + flags, app?.arguments)
    }

    @Test fun opencodeAliasesRetainClassAndNonElectronUsesOrdinaryLaunch() {
        file("opt/OpenCode/ai.opencode").setExecutable(true)
        val marker = file("opt/OpenCode/resources/app.asar")
        val first = entry("/usr/share/applications/ai.opencode.desktop.desktop", "/opt/OpenCode/ai.opencode %U", "StartupWMClass=ai.opencode.desktop")
        val alias = entry("/usr/share/applications/opencode-desktop.desktop", "/opt/OpenCode/ai.opencode %U", "StartupWMClass=ai.opencode.desktop")
        assertEquals(listOf("/opt/OpenCode/ai.opencode") + flags, first?.arguments)
        assertEquals(first, alias)
        marker.delete()
        assertNull(entry("/usr/share/applications/opencode-desktop.desktop", "/opt/OpenCode/ai.opencode %U"))
    }

    @Test fun envQuotedPathDesktopFieldsAndWorkingDirectory() {
        file("opt/My App/bin/start").setExecutable(true)
        file("opt/My App/resources/app/package.json")
        val path = "$DEBIAN_HOME/.local/share/applications/myapp.desktop"
        val app = entry(path, """env LANG=C "/opt/My App/bin/start" "two words" 100%% %i %c %k %F""",
            "Icon=myapp\nPath=/home/Debian/Some Files")
        assertEquals(listOf("env", "LANG=C", "/opt/My App/bin/start") + flags +
            listOf("two words", "100%", "--icon", "myapp", "My App", path), app?.arguments)
        assertEquals("/home/Debian/Some Files", app?.directory)
    }

    @Test fun sharedRuntimeAndWrapperOptInWithoutMatchingOtherPrograms() {
        file("usr/bin/electron-39").setExecutable(true)
        val path = "/usr/share/applications/client.desktop"
        assertEquals(listOf("electron-39") + flags + "/usr/lib/client.asar",
            entry(path, "electron-39 /usr/lib/client.asar %u")?.arguments)
        file("usr/bin/launcher").setExecutable(true)
        assertNull(entry(path, "launcher %U"))
        assertEquals(listOf("launcher") + flags, entry(path, "launcher %U", "X-Mobile-Terminal-Electron=true")?.arguments)
        assertNull(entry(path, "launcher %X", "X-Mobile-Terminal-Electron=true"))
        assertNull(entry("/usr/share/applications/chromium.desktop", "launcher", "X-Mobile-Terminal-Electron=true"))
    }
}
