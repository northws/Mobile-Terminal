package dev.warpterminal.mobile

import java.io.File
import java.io.IOException
import java.nio.file.Files

internal data class ElectronLaunch(val arguments: List<String>, val directory: String, val windowClass: String?)

/** Inspect the guest installation. Read paths and symlinks relative to Debian's mounts. */
internal class ElectronDesktop(private val root: File, private val home: File) {
    fun read(path: String, name: String): ElectronLaunch? {
        if (path == "/usr/share/applications/chromium.desktop") return null
        return try {
            val file = resolve(path) ?: return null
            if (!file.isFile || file.length() > 1024 * 1024) return null
            val fields = file.useLines { lines ->
                lines.map(String::trim).dropWhile { it != "[Desktop Entry]" }.drop(1)
                    .takeWhile { !it.startsWith('[') }.filter { '=' in it && !it.startsWith('#') }
                    .associate { it.substringBefore('=').trim() to it.substringAfter('=') }
            }
            val words = splitExec(unescape(fields["Exec"] ?: return null)) ?: return null
            if (words.isEmpty()) return null
            val index = if (words.first() in listOf("env", "/usr/bin/env")) {
                (1 until words.size).firstOrNull { !assignment.matches(words[it]) && words[it] != "--" } ?: return null
            } else 0
            val program = words[index]
            val executable = if (program.startsWith('/')) resolve(program) else {
                if ('/' in program) return null
                pathDirectories.firstNotNullOfOrNull { resolve("$it/$program")?.takeIf(File::canExecute) }
            } ?: return null
            if (!executable.isFile || !executable.canExecute()) return null
            val folder = executable.parentFile ?: return null
            if (fields["X-Mobile-Terminal-Electron"] != "true" && !hasApp(folder) &&
                !(folder.name == "bin" && folder.parentFile?.let(::hasApp) == true) &&
                !runtimeName.matches(executable.name) && !runtimeName.matches(program.substringAfterLast('/'))) return null
            val args = mutableListOf<String>()
            for ((i, word) in words.withIndex()) {
                when (word) {
                    "%u", "%U", "%f", "%F", "%d", "%D", "%n", "%N", "%v", "%m" -> Unit
                    "%c" -> args.add(name)
                    "%k" -> args.add(path)
                    "%i" -> fields["Icon"]?.takeIf(String::isNotBlank)?.let { args.addAll(listOf("--icon", unescape(it))) }
                    else -> {
                        if ('%' in word.replace("%%", "")) return null
                        args.add(word.replace("%%", "%"))
                    }
                }
                if (i == index) args.addAll(listOf("--no-sandbox", "--ozone-platform=wayland", "--disable-gpu"))
            }
            val cwd = fields["Path"]?.let(::unescape)?.takeIf { it.startsWith('/') } ?: DEBIAN_HOME
            ElectronLaunch(args, cwd, fields["StartupWMClass"]?.trim()?.takeIf(String::isNotEmpty))
        } catch (_: IOException) {
            null
        }
    }

    private fun hasApp(folder: File) =
        File(folder, "resources/app.asar").isFile || File(folder, "resources/app/package.json").isFile

    private fun resolve(path: String): File? {
        if (!path.startsWith('/') || '\u0000' in path) return null
        val pending = ArrayDeque(path.split('/'))
        val parts = mutableListOf<String>()
        var links = 0
        fun mounted(): File {
            val inHome = parts.size >= 2 && parts[0] == "home" && parts[1] in listOf(DEBIAN_USER, "warp")
            return if (inHome) File(home, parts.drop(2).joinToString("/")) else File(root, parts.joinToString("/"))
        }
        while (pending.isNotEmpty()) {
            when (val part = pending.removeFirst()) {
                "", "." -> continue
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> {
                    parts.add(part)
                    val file = mounted()
                    if (!Files.isSymbolicLink(file.toPath())) continue
                    if (++links > 40) return null
                    val target = Files.readSymbolicLink(file.toPath()).toString()
                    parts.removeAt(parts.lastIndex)
                    if (target.startsWith('/')) parts.clear()
                    target.split('/').asReversed().forEach(pending::addFirst)
                }
            }
        }
        return mounted()
    }

    private fun unescape(value: String) = desktopEscape.replace(value) {
        when (it.value.last()) {
            's' -> " "
            'n' -> "\n"
            't' -> "\t"
            'r' -> "\r"
            else -> "\\"
        }
    }

    // Desktop Exec quoting is not shell syntax. No expansion or shell evaluation occurs.
    private fun splitExec(value: String): List<String>? {
        val result = mutableListOf<String>()
        val word = StringBuilder()
        var quoted = false
        var escaped = false
        var started = false
        for (c in value) {
            when {
                escaped -> { word.append(c); escaped = false }
                c == '\\' -> escaped = true
                c == '"' -> { quoted = !quoted; started = true }
                c.isWhitespace() && !quoted -> {
                    if (started) result.add(word.toString())
                    word.clear()
                    started = false
                }
                else -> { word.append(c); started = true }
            }
        }
        if (quoted || escaped) return null
        if (started) result.add(word.toString())
        return result
    }

    private companion object {
        val assignment = Regex("[A-Za-z_][A-Za-z0-9_]*=.*")
        val runtimeName = Regex("electron(?:-?v?[0-9]+)?")
        val desktopEscape = Regex("""\\[sntr\\]""")
        val pathDirectories = listOf("/usr/local/sbin", "/usr/local/bin", "/usr/sbin", "/usr/bin", "/sbin", "/bin", "/usr/games")
    }
}
