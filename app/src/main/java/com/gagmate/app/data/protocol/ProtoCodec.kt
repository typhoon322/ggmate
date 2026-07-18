package com.gagmate.app.data.protocol

import java.nio.ByteBuffer

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

/**
 * Protobuf codec for Gaggiuino v3 WebSocket protocol.
 *
 * The protocol uses a simple protobuf-like encoding:
 * - field 1 (string): command name
 * - field 2 (bytes): command payload
 *
 * Field types: 0=varint, 2=length-delimited, 5=32-bit float
 */
object ProtoCodec {

    fun encodeVarint(value: Long): ByteArray {
        val out = mutableListOf<Byte>()
        var v = value
        while (v >= 0x80L) {
            out.add(((v and 0x7FL) or 0x80L).toByte())
            v = v shr 7
        }
        out.add((v and 0x7FL).toByte())
        return out.toByteArray()
    }

    fun encodeTag(field: Int, wt: Int): ByteArray =
        encodeVarint(((field shl 3) or wt).toLong())

    fun encodeLengthDelimited(field: Int, value: ByteArray): ByteArray {
        val tag = encodeTag(field, 2)
        return tag + encodeVarint(value.size.toLong()) + value
    }

    fun encodeVarintField(field: Int, value: Long): ByteArray =
        encodeTag(field, 0) + encodeVarint(value)

    fun encodeFloatField(field: Int, value: Float): ByteArray =
        encodeTag(field, 5) + ByteBuffer.allocate(4).putFloat(value).array()

    /**
     * Build a complete command frame: command name (field 1) + payload (field 2).
     */
    fun buildCommand(cmd: String, payload: ByteArray? = null): ByteArray {
        val cmdPart = encodeLengthDelimited(1, cmd.toByteArray())
        return if (payload != null) cmdPart + encodeLengthDelimited(2, payload) else cmdPart
    }

    /**
     * Decode a response frame into (commandName, payloadBytes).
     * Returns null on malformed data.
     */
    fun decodeResponse(data: ByteArray): Pair<String, ByteArray>? {
        try {
            var offset = 0
            var command = ""
            var payload = byteArrayOf()
            while (offset < data.size) {
                val (tw, p1) = readVarint(data, offset); offset = p1
                val fn = (tw shr 3).toInt(); val wt = (tw and 0x7L).toInt()
                when (wt) {
                    0 -> {
                        val (_, p2) = readVarint(data, offset); offset = p2
                    }
                    2 -> {
                        val (len, p2) = readVarint(data, offset); offset = p2
                        val v = data.copyOfRange(offset, offset + len.toInt()); offset += len.toInt()
                        when (fn) { 1 -> command = v.decodeToString(); 2 -> payload = v }
                    }
                    5 -> offset += 4
                }
            }
            return if (command.isNotEmpty()) command to payload else null
        } catch (_: Exception) { return null }
    }

    /**
     * Decode a hex string representation for debugging.
     */
    fun toHexString(data: ByteArray): String = data.toHex()

    private fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int> {
        var value = 0L; var shift = 0; var pos = offset
        while (pos < data.size) {
            val byte = data[pos].toInt() and 0xFF
            value = value or ((byte and 0x7F).toLong() shl shift)
            shift += 7; pos++
            if (byte and 0x80 == 0) break
        }
        return value to pos
    }
}
