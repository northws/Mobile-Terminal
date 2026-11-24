package dev.warpterminal.mobile

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal fun validateFileComponent(name: String): String {
    require(name.isNotBlank() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it.isISOControl() } &&
        name.toByteArray().size <= 240) { "文件名无效" }
    return name
}

internal class FileExchange(private val resolver: ContentResolver, private val home: File) {
    fun list(path: List<String>): List<Pair<String, Boolean>> = inDirectory(path) { dir ->
        File(dir).listFiles().orEmpty().mapNotNull { item ->
            val kind = runCatching { Os.lstat(item.path).st_mode }.getOrNull() ?: return@mapNotNull null
            if (OsConstants.S_ISDIR(kind) || OsConstants.S_ISREG(kind)) item.name to OsConstants.S_ISDIR(kind)
            else null
        }.sortedWith(compareBy({ !it.second }, { it.first }))
    }

    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "导入文件"
        importStream(name) { resolver.openInputStream(uri) }
    }

    internal suspend fun importStream(name: String, open: () -> InputStream?): String = withContext(Dispatchers.IO) {
        validateFileComponent(name)
        inDirectory(emptyList()) { homeDir ->
            try {
                Os.mkdir("$homeDir/Downloads", 448)
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EEXIST) throw error
            }
        }
        inDirectory(listOf("Downloads")) { downloads ->
            var suffix = 0
            val extension = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            while (true) {
                currentCoroutineContext().ensureActive()
                val destination = if (suffix == 0) name else
                    "${name.substring(0, extension)} ($suffix)${name.substring(extension)}"
                val path = "$downloads/$destination"
                val fd = try {
                    // Android can disallow app-data hard links. Reserve the name
                    // atomically so simultaneous imports cannot replace a file.
                    Os.open(path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL, 384)
                } catch (error: ErrnoException) {
                    if (error.errno != OsConstants.EEXIST) throw error
                    suffix++
                    continue
                }
                try {
                    java.io.FileOutputStream(fd).use { target ->
                        val source = open() ?: throw IOException("无法读取所选文件")
                        source.use { copy(it, target) }
                        target.fd.sync()
                    }
                    currentCoroutineContext().ensureActive()
                    return@inDirectory "~/Downloads/$destination"
                } catch (failure: Exception) {
                    File(path).delete()
                    throw failure
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    }

    suspend fun export(path: List<String>, uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val name = validateFileComponent(path.last())
            inDirectory(path.dropLast(1)) { directory ->
                val fd = Os.open("$directory/$name", OsConstants.O_RDONLY or
                    OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
                java.io.FileInputStream(fd).use { source ->
                    if (!OsConstants.S_ISREG(Os.fstat(fd).st_mode)) throw IOException("请选择普通文件")
                    resolver.openOutputStream(uri, "wt")?.use { target -> copy(source, target) }
                        ?: throw IOException("无法写入所选位置")
                }
            }
        } catch (failure: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw failure
        }
    }

    private suspend fun copy(source: InputStream, target: OutputStream) {
        val bytes = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = source.read(bytes)
            if (read < 0) return
            target.write(bytes, 0, read)
        }
    }

    private inline fun <T> inDirectory(components: List<String>, use: (String) -> T): T {
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        try {
            var directory = openDirectory(home.path, descriptors)
            for (component in components) {
                directory = openDirectory("$directory/${validateFileComponent(component)}", descriptors)
            }
            return use(directory)
        } finally {
            descriptors.asReversed().forEach(ParcelFileDescriptor::close)
        }
    }

    private fun openDirectory(path: String, descriptors: MutableList<ParcelFileDescriptor>): String {
        val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        val descriptor = try {
            if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw IOException("请选择目录")
            ParcelFileDescriptor.dup(fd)
        } finally {
            Os.close(fd)
        }
        descriptors.add(descriptor)
        return "/proc/self/fd/${descriptor.fd}"
    }
}
