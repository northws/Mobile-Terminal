package dev.warpterminal.mobile

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import org.json.JSONObject

internal const val PROTOCOL_VERSION = 1
internal const val JSON_FRAME = 0
internal const val TERMINAL_FRAME = 1
private const val FRAME_LIMIT = 256 * 1024

internal data class Frame(val kind: Int, val data: ByteArray) {
    fun json() = JSONObject(String(data, UTF_8))
}

internal fun DataInputStream.readFrame(): Frame? {
    val first = read()
    if (first < 0) return null
    val size = (first shl 24) or (readUnsignedByte() shl 16) or
        (readUnsignedByte() shl 8) or readUnsignedByte()
    if (size !in 1..FRAME_LIMIT) throw IOException("无效的终端消息长度")
    val kind = readUnsignedByte()
    if (kind != JSON_FRAME && kind != TERMINAL_FRAME) throw IOException("未知的终端消息类型")
    return Frame(kind, ByteArray(size - 1).also { readFully(it) })
}

internal fun DataOutputStream.writeFrame(kind: Int, bytes: ByteArray) {
    require(bytes.size < FRAME_LIMIT)
    writeInt(bytes.size + 1)
    writeByte(kind)
    write(bytes)
    flush()
}

internal fun DataOutputStream.writeEvent(type: String, modify: JSONObject.() -> Unit = {}) {
    writeFrame(JSON_FRAME, JSONObject().put("type", type).apply(modify).toString().toByteArray(UTF_8))
}
