/*
 * reCMF — a third-party companion app for the CMF Watch Pro 2.
 * Copyright (C) 2026 reCMF contributors
 *
 * Ported from Gadgetbridge (Copyright (C) 2024 José Rebelo), AGPL-3.0-or-later.
 * See LICENSE and NOTICE at the repository root.
 */
package dev.recmf.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * One BLE write/notification: an 11-byte big-endian header followed by a chunk body.
 *
 * ```
 * f5 | len:u16 | cmd1:u16 | chunkCount:u16 | chunkIndex:u16 | cmd2:u16 | body...
 * ```
 *
 * `chunkIndex` is 1-based. The body is either plaintext or an AES-CBC blob; in both
 * cases a little-endian CRC32 of the plaintext is appended before encryption.
 */
object CmfFrame {
    const val HEADER_MAGIC: Byte = 0xf5.toByte()
    const val HEADER_SIZE: Int = 11

    /**
     * A single 0xa5 byte, which the watch expects as the payload of most commands that
     * carry no arguments. Reads as a "this side really can encrypt" marker.
     */
    val A5: ByteArray = byteArrayOf(0xa5.toByte())

    fun crc32le(data: ByteArray): ByteArray {
        val crc = CRC32()
        crc.update(data, 0, data.size)
        return uint32le(crc.value.toInt())
    }

    fun uint32le(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 24) and 0xff).toByte(),
    )

    fun readUint32le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16) or
            ((data[offset + 3].toInt() and 0xff) shl 24)

    fun header(cmd: CmfCommand, bodyLength: Int, chunkCount: Int, chunkIndex: Int): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN).apply {
            put(HEADER_MAGIC)
            putShort(bodyLength.toShort())
            putShort(cmd.cmd1.toShort())
            putShort(chunkCount.toShort())
            putShort(chunkIndex.toShort())
            putShort(cmd.cmd2.toShort())
        }.array()

    /** The header of an inbound frame, before the body is decrypted or reassembled. */
    data class Parsed(
        val bodyLength: Int,
        val cmd1: Int,
        val cmd2: Int,
        val chunkCount: Int,
        val chunkIndex: Int,
        val body: ByteArray,
    ) {
        val command: CmfCommand? get() = CmfCommand.fromCodes(cmd1, cmd2)

        /** Data class equality on a [ByteArray] field would compare identities. */
        override fun equals(other: Any?): Boolean =
            this === other ||
                (
                    other is Parsed &&
                        bodyLength == other.bodyLength &&
                        cmd1 == other.cmd1 &&
                        cmd2 == other.cmd2 &&
                        chunkCount == other.chunkCount &&
                        chunkIndex == other.chunkIndex &&
                        body.contentEquals(other.body)
                    )

        override fun hashCode(): Int {
            var result = bodyLength
            result = 31 * result + cmd1
            result = 31 * result + cmd2
            result = 31 * result + chunkCount
            result = 31 * result + chunkIndex
            result = 31 * result + body.contentHashCode()
            return result
        }
    }

    /**
     * Splits an inbound BLE value into header fields and body.
     *
     * @return null if the value is too short or does not start with [HEADER_MAGIC] —
     *   both are things a flaky link produces, so callers log and drop rather than throw.
     */
    fun parse(value: ByteArray): Parsed? {
        if (value.size < HEADER_SIZE) return null
        if (value[0] != HEADER_MAGIC) return null

        val buf = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN)
        buf.get() // magic, already checked
        val bodyLength = buf.short.toInt() and 0xffff
        val cmd1 = buf.short.toInt() and 0xffff
        val chunkCount = buf.short.toInt() and 0xffff
        val chunkIndex = buf.short.toInt() and 0xffff
        val cmd2 = buf.short.toInt() and 0xffff

        val body = ByteArray(buf.remaining())
        buf.get(body)

        return Parsed(bodyLength, cmd1, cmd2, chunkCount, chunkIndex, body)
    }
}
