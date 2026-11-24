package dev.warpterminal.mobile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FramesTest {
    @Test fun fragmentedConsecutiveFrames() {
        val bytes = ByteArrayOutputStream()
        val terminal = "你好".toByteArray()
        DataOutputStream(bytes).apply {
            writeFrame(TERMINAL_FRAME, terminal)
            writeFrame(JSON_FRAME, """{"type":"hello","version":1}""".toByteArray())
        }
        val reader = DataInputStream(object : ByteArrayInputStream(bytes.toByteArray()) {
            override fun read(target: ByteArray, offset: Int, length: Int) =
                super.read(target, offset, minOf(length, 1))
        })
        assertArrayEquals(terminal, reader.readFrame()!!.data)
        assertEquals(JSON_FRAME, reader.readFrame()!!.kind)
        assertNull(reader.readFrame())
    }

    @Test fun rejectsInvalidLengthAndPartialBody() {
        assertThrows(IOException::class.java) {
            DataInputStream(ByteArrayInputStream(byteArrayOf(0, 4, 0, 1))).readFrame()
        }
        assertThrows(EOFException::class.java) {
            DataInputStream(ByteArrayInputStream(byteArrayOf(0, 0, 0, 3, 1, 1))).readFrame()
        }
    }

    @Test fun fileNames() {
        assertEquals("脚本 1.sh", validateFileComponent("脚本 1.sh"))
        for (name in listOf("", ".", "..", "a/b", "a\\b", "\u0000", "字".repeat(81))) {
            assertThrows(IllegalArgumentException::class.java) { validateFileComponent(name) }
        }
    }
}
