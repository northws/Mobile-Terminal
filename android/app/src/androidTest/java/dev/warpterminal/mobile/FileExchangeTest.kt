package dev.warpterminal.mobile

import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileExchangeTest {
    @Test fun cancelAfterWritingPreservesOriginalAndRemovesPartialCopy() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val home = Files.createTempDirectory(context.cacheDir.toPath(), "warp-cancel-").toFile()
        val downloads = File(home, "Downloads").apply { mkdir() }
        val original = File(downloads, "验收文件.bin")
        val expected = byteArrayOf(17, 22, 9)
        original.writeBytes(expected)
        val partial = File(downloads, "验收文件 (1).bin")
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = object : InputStream() {
            private var sent = false

            override fun read(): Int = error("Expected buffered reads")

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (!sent) {
                    sent = true
                    bytes.fill(7, offset, offset + length)
                    return length
                }
                blocked.countDown()
                check(release.await(6, TimeUnit.SECONDS)) { "Copy was not released" }
                return -1
            }
        }
        try {
            val exchange = FileExchange(context.contentResolver, home)
            val transfer = launch { exchange.importStream(original.name) { source } }
            try {
                assertTrue(withContext(Dispatchers.IO) { blocked.await(4, TimeUnit.SECONDS) })
                assertTrue(partial.length() > 0)
            } finally {
                transfer.cancel()
                release.countDown()
                transfer.join()
            }
            assertArrayEquals(expected, original.readBytes())
            assertFalse(partial.exists())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test fun symbolicLinkOutsideHomeCannotBeListedOrExported() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val temp = Files.createTempDirectory(context.cacheDir.toPath(), "warp-symlink-").toFile()
        val home = File(temp, "home").apply { mkdir() }
        val outside = File(temp, "outside.bin").apply { writeBytes(byteArrayOf(17, 22, 9)) }
        try {
            val exchange = FileExchange(context.contentResolver, home)
            Os.symlink(outside.path, File(home, "escape.bin").path)
            assertTrue(exchange.list(emptyList()).isEmpty())
            assertTrue(runCatching { exchange.export(listOf("escape.bin"), Uri.EMPTY) }.exceptionOrNull() is ErrnoException)
        } finally {
            temp.deleteRecursively()
        }
    }
}
