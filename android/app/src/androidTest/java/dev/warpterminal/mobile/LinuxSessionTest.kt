package dev.warpterminal.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LinuxSessionTest {
    @Test fun runningBlockAcceptsMultipleRepliesAndAnEmptyEnter() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val linux = LinuxEnvironment(context)
        linux.install { _, _ -> }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val ended = CompletableDeferred<Unit>()
        val tab = withContext(Dispatchers.Main) { TerminalTab(1, linux, scope) { ended.complete(Unit) }.also { it.start() } }

        suspend fun await(condition: () -> Boolean) = withTimeout(15_000) {
            withContext(Dispatchers.Main) { while (!condition()) delay(25) }
        }

        fun output() = tab.blocks.lastOrNull()?.lines.orEmpty().joinToString("") { it.text }

        try {
            await { tab.ready }
            withContext(Dispatchers.Main) {
                tab.draft = "read -r -p 'Continue? [Y/n] ' answer; read -r -p 'Use default? [Y/n] ' choice; " +
                    "printf 'RESULT=<%s,%s>\\n' \"\$answer\" \"\${choice:-default}\""
                tab.submit()
            }
            await { tab.running && output().contains("Continue? [Y/n]") }
            withContext(Dispatchers.Main) {
                tab.replyDraft = "n"
                tab.sendReply()
            }
            await { tab.running && output().contains("Use default? [Y/n]") }
            withContext(Dispatchers.Main) {
                assertEquals("", tab.replyDraft)
                tab.sendReply()
            }
            await { tab.ready }
            withContext(Dispatchers.Main) {
                assertEquals(1, tab.blocks.size)
                assertEquals(0, tab.blocks.single().code)
                assertTrue(output().contains("RESULT=<n,default>"))
                tab.draft = "printf 'keep\\nAPT 0%%'; sleep 0.1; printf '\\r\\033[KAPT 100%%\\n'"
                tab.submit()
            }
            await { tab.ready && tab.blocks.size == 2 }
            withContext(Dispatchers.Main) {
                assertEquals("keep\nAPT 100%\n", output())
            }
        } finally {
            withContext(Dispatchers.Main) { tab.close() }
            if (withTimeoutOrNull(3_000) { ended.await() } == null) {
                withContext(Dispatchers.Main) { tab.terminate() }
            }
            scope.cancel()
        }
    }

    @Test fun debianAndIndependentBashSessions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val linux = LinuxEnvironment(context)
        linux.install { _, _ -> }
        assertTrue(linux.installed())
        val first = Session(linux.spawn())
        val second = Session(linux.spawn())
        first.use {
            second.use {
                first.handshake()
                second.handshake()
                assertEquals("你好\n", first.command("printf '你好\\n' | cat", 0))
                first.command("false", 1)
                first.command("export WARP_DEVICE_TEST=one", 0)
                assertEquals("one", first.command("printf %s \"\$WARP_DEVICE_TEST\"", 0))
                assertEquals("", second.command("printf %s \"\$WARP_DEVICE_TEST\"", 0))
                first.input("sleep 30\r")
                first.until("started")
                Thread.sleep(150)
                first.input("\u0003")
                val end = first.until("prompt").single { it.getString("type") == "finished" }
                assertEquals(130, end.getInt("code"))
            }
        }
    }

    private class Session(private val child: Process) : AutoCloseable {
        private val source = DataInputStream(child.inputStream.buffered())
        private val sink = DataOutputStream(child.outputStream.buffered())
        private val timeout = Executors.newSingleThreadScheduledExecutor()

        init { timeout.schedule({ child.destroyForcibly() }, 70, TimeUnit.SECONDS) }

        fun input(text: String) = sink.writeFrame(TERMINAL_FRAME, text.toByteArray())

        fun handshake() {
            assertEquals(PROTOCOL_VERSION, until("hello").single().getInt("version"))
            assertEquals("/home/warp", until("prompt").last().getString("directory"))
        }

        fun command(command: String, exitCode: Int): String {
            input("$command\r")
            val events = until("prompt")
            assertEquals(1, events.count { it.getString("type") == "started" })
            assertEquals(exitCode, events.single { it.getString("type") == "finished" }.getInt("code"))
            return events.filter { it.getString("type") == "output" }.joinToString("") { it.getString("text") }
        }

        fun until(type: String): List<JSONObject> = buildList {
            while (true) {
                val frame = requireNotNull(source.readFrame()) { "Unexpected EOF before $type" }
                if (frame.kind != JSON_FRAME) continue
                val event = frame.json()
                add(event)
                if (event.getString("type") == type) break
            }
        }

        override fun close() {
            try {
                if (child.isAlive) sink.writeEvent("close")
                sink.close()
                if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly()
            } finally {
                source.close()
                timeout.shutdownNow()
            }
        }
    }
}
