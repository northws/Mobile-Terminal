package dev.warpterminal.mobile

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

// TAWC also delays directory permissions and falls back to symlinks when
// Android denies app-data hard links. This installer only accepts Debian's gzip archive.
internal suspend fun extractDebianArchive(archive: File, destination: File) {
    extractLinuxTar(GZIPInputStream(archive.inputStream().buffered()), destination)
}

internal suspend fun extractLinuxTar(archive: InputStream, destination: File) {
    val root = destination.canonicalFile
    val prefix = root.path + File.separator
    val directories = mutableListOf<Pair<File, Int>>()
    val permissionsMask = 0x1ff

    fun resolve(name: String): File {
        val relative = name.removePrefix("./").trimEnd('/')
        if (relative.isBlank() || relative.startsWith('/') || relative.split('/').any { it == ".." }) {
            throw IOException("Debian 镜像包含无效路径")
        }
        val target = File(root, relative)
        if (!target.canonicalPath.startsWith(prefix)) {
            throw IOException("Debian 镜像中的路径超出安装目录")
        }
        return target
    }

    TarArchiveInputStream(archive).use { source ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = source.nextEntry ?: break
            if (entry.name.trimEnd('/') == ".") continue
            val target = resolve(entry.name)
            val mode = entry.mode and permissionsMask
            if (entry.isDirectory) {
                if (!target.isDirectory && !target.mkdirs()) throw IOException("无法创建 Debian 目录 ${entry.name}")
                directories.add(target to mode)
                continue
            }
            target.parentFile?.mkdirs()
            when {
                entry.isSymbolicLink -> Os.symlink(entry.linkName, target.path)
                entry.isLink -> {
                    val linkSource = resolve(entry.linkName)
                    try {
                        Os.link(linkSource.path, target.path)
                    } catch (error: ErrnoException) {
                        if (error.errno != OsConstants.EACCES && error.errno != OsConstants.EPERM) throw error
                        Os.symlink(linkSource.relativeTo(target.parentFile!!).path, target.path)
                    }
                }
                entry.isFile -> {
                    target.outputStream().buffered().use { source.copyTo(it) }
                    Os.chmod(target.path, mode)
                }
            }
        }
    }

    for ((directory, mode) in directories.sortedByDescending { it.first.path.length }) {
        Os.chmod(directory.path, mode or OsConstants.S_IRWXU)
    }
}
