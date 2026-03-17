package com.dexradar.parser

import com.dexradar.MainApplication
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PhotonParser(private val dispatcher: EventDispatcher) {

    fun parse(data: ByteArray, offset: Int, length: Int) {
        if (length < 12) return
        try {
            val buf = ByteBuffer.wrap(data, offset, length)
            buf.order(ByteOrder.BIG_ENDIAN)

            // Photon header (12 bytes)
            buf.short              // peerId   — skip
            buf.get()              // flags    — skip
            val cmdCount = buf.get().toInt() and 0xFF
            buf.int                // timestamp — skip
            buf.int                // challenge — skip

            repeat(cmdCount) {
                if (buf.remaining() >= 12) parseCommand(buf)
            }
        } catch (e: Exception) {
            // Malformed packet — silently discard
        }
    }

    private fun parseCommand(buf: ByteBuffer) {
        val cmdType   = buf.get().toInt() and 0xFF
        buf.get()                                    // channelId — parse ALL
        buf.get()                                    // cmdFlags  — skip
        buf.get()                                    // reserved  — skip
        val cmdLength   = buf.int
        buf.int                                      // reliableSeq — skip
        val payloadSize = cmdLength - 12

        if (payloadSize <= 0) return
        if (buf.remaining() < payloadSize) return

        val payloadStart = buf.position()

        // Parse reliable (6) and unreliable (7) commands only
        if (cmdType == 6 || cmdType == 7) {
            val msgType = buf.get().toInt() and 0xFF
            if (msgType == 0x04) parseEvent(buf)   // 0x04 = Event
        }

        // ALWAYS advance to next command boundary
        buf.position(payloadStart + payloadSize)
    }

    private fun parseEvent(buf: ByteBuffer) {
        if (buf.remaining() < 3) return
        buf.get()   // eventCode byte — NOT used for routing; params[252] has the real code
        val paramCount = buf.short.toInt() and 0xFFFF
        val params = HashMap<Int, Any?>(paramCount)

        repeat(paramCount) {
            if (buf.remaining() < 2) return
            val key   = buf.get().toInt() and 0xFF
            val value = readValue(buf)
            params[key] = value
        }

        dispatcher.dispatch(params)
    }

    private fun readValue(buf: ByteBuffer): Any? {
        if (buf.remaining() < 1) return null
        return when (val typeCode = buf.get().toInt() and 0xFF) {
            0x2A -> null                               // Null
            0x6F -> buf.get() != 0.toByte()            // Boolean
            0x62 -> buf.get().toInt() and 0xFF         // Byte (unsigned)
            0x6B -> buf.short.toInt()                  // Short
            0x69 -> buf.int                            // Integer
            0x6C -> buf.long                           // Long
            0x66 -> buf.float                          // Float  <- POSITIONS
            0x64 -> buf.double                         // Double
            0x73 -> readString(buf)                    // String <- TYPE NAMES
            0x78 -> readByteArray(buf)                 // ByteArray
            0x6E -> readIntArray(buf)                  // IntArray
            0x61 -> readArray(buf)                     // Array
            0x68 -> readHashtable(buf)                 // Hashtable
            0x44 -> readDictionary(buf)                // Dictionary
            0x7A -> readObjectArray(buf)               // ObjectArray
            else -> {
                MainApplication.discoveryLogger.logUnknownType(typeCode)
                null
            }
        }
    }

    private fun readString(buf: ByteBuffer): String? {
        if (buf.remaining() < 2) return null
        val len = buf.short.toInt() and 0xFFFF
        if (buf.remaining() < len) return null
        val bytes = ByteArray(len)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readByteArray(buf: ByteBuffer): ByteArray? {
        if (buf.remaining() < 4) return null
        val len = buf.int
        if (len < 0 || buf.remaining() < len) return null
        val bytes = ByteArray(len)
        buf.get(bytes)
        return bytes
    }

    private fun readIntArray(buf: ByteBuffer): IntArray? {
        if (buf.remaining() < 4) return null
        val count = buf.int
        if (count < 0 || buf.remaining() < count * 4) return null
        return IntArray(count) { buf.int }
    }

    private fun readArray(buf: ByteBuffer): List<Any?>? {
        if (buf.remaining() < 3) return null
        val len      = buf.short.toInt() and 0xFFFF
        val elemType = buf.get().toInt() and 0xFF
        return (0 until len).map { readValueWithKnownType(buf, elemType) }
    }

    private fun readValueWithKnownType(buf: ByteBuffer, typeCode: Int): Any? = when (typeCode) {
        0x2A -> null
        0x6F -> buf.get() != 0.toByte()
        0x62 -> buf.get().toInt() and 0xFF
        0x6B -> buf.short.toInt()
        0x69 -> buf.int
        0x6C -> buf.long
        0x66 -> buf.float
        0x64 -> buf.double
        0x73 -> readString(buf)
        0x78 -> readByteArray(buf)
        else -> null
    }

    private fun readHashtable(buf: ByteBuffer): Map<Any?, Any?>? {
        if (buf.remaining() < 2) return null
        val count = buf.short.toInt() and 0xFFFF
        val map = HashMap<Any?, Any?>(count)
        repeat(count) { map[readValue(buf)] = readValue(buf) }
        return map
    }

    private fun readDictionary(buf: ByteBuffer): Map<Any?, Any?>? {
        if (buf.remaining() < 4) return null
        val keyType = buf.get().toInt() and 0xFF
        val valType = buf.get().toInt() and 0xFF
        val count   = buf.short.toInt() and 0xFFFF
        val map = HashMap<Any?, Any?>(count)
        repeat(count) {
            val k = if (keyType == 0) readValue(buf) else readValueWithKnownType(buf, keyType)
            val v = if (valType == 0) readValue(buf) else readValueWithKnownType(buf, valType)
            map[k] = v
        }
        return map
    }

    private fun readObjectArray(buf: ByteBuffer): List<Any?>? {
        if (buf.remaining() < 2) return null
        val len = buf.short.toInt() and 0xFFFF
        return (0 until len).map { readValue(buf) }
    }
}
