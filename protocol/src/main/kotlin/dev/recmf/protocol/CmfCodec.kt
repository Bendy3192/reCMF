/*
 * reCMF — a third-party companion app for the CMF Watch Pro 2.
 * Copyright (C) 2026 reCMF contributors
 *
 * Ported from Gadgetbridge (Copyright (C) 2024 José Rebelo), AGPL-3.0-or-later.
 * See LICENSE and NOTICE at the repository root.
 */
package dev.recmf.protocol

import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException

/** Why a frame was thrown away instead of surfacing as a command. */
enum class CmfDropReason {
    MALFORMED_HEADER,
    UNKNOWN_COMMAND,
    NO_SESSION_KEY,
    DECRYPT_FAILED,
    CRC_MISMATCH,
    CHUNK_OUT_OF_ORDER,
    REASSEMBLY_TOO_LARGE,
}

sealed interface CmfDecoded {
    /** A whole payload, reassembled and (if applicable) decrypted and CRC-checked. */
    class Command(val cmd: CmfCommand, val payload: ByteArray) : CmfDecoded

    /**
     * A frame under an acknowledging opcode: `<cmd1>/0x0003` for a generic command,
     * `0xffff/0xaxxx` for a vendor one.
     *
     * Usually that is the watch confirming something it applied, and [payload] is empty.
     * Not always, and that is why the bytes are carried rather than discarded: four
     * `0xffff/0xa06a` frames arrived during a workout, minutes apart, when nothing had
     * been sent for them to acknowledge — which looks much more like the watch asking for
     * a position than like it confirming one. Thrown away, that question was invisible.
     *
     * Not a data class: [payload] is a [ByteArray], which would compare by identity.
     */
    class Acknowledgement(
        val of: CmfCommand,
        val cmd1: Int,
        val cmd2: Int,
        val payload: ByteArray = ByteArray(0),
    ) : CmfDecoded

    /** A chunk was buffered; more are expected before the payload is complete. */
    data object Pending : CmfDecoded

    /**
     * [cmd1], [cmd2] and, where it could be recovered, [payload] are carried even when the
     * command is unknown. An opcode the watch uses and this app does not is exactly the
     * thing worth writing down, and the numbers alone rarely say what it means — the bytes
     * do.
     *
     * Not a data class: [payload] is a [ByteArray], which would compare by identity.
     */
    class Dropped(
        val reason: CmfDropReason,
        val cmd: CmfCommand?,
        val cmd1: Int? = null,
        val cmd2: Int? = null,
        val payload: ByteArray? = null,
    ) : CmfDecoded
}

/**
 * Turns commands into BLE writes and BLE notifications back into commands, for one
 * characteristic.
 *
 * Not thread-safe by itself: reassembly state is mutable, so every instance must be
 * driven from a single thread. In reCMF that is the GATT callback thread, which is the
 * only place notifications arrive anyway.
 *
 * @param maxReassemblySize hard ceiling on a single reassembled payload. A watch that
 *   announces a huge `chunkCount` — or a link that drops the final chunk of one transfer
 *   and then starts another — would otherwise grow this buffer without bound, and this
 *   process gets killed for it long before the watch notices.
 */
class CmfCodec(
    private val maxReassemblySize: Int = DEFAULT_MAX_REASSEMBLY_SIZE,
) {
    private val chunkBuffers = HashMap<CmfCommand, ChunkBuffer>()

    var sessionKey: ByteArray? = null
        set(value) {
            require(value == null || value.size == CmfCrypto.KEY_SIZE) {
                "Session key must be ${CmfCrypto.KEY_SIZE} bytes"
            }
            field = value
            // A rekey invalidates anything half-received under the old key.
            chunkBuffers.clear()
        }

    var mtu: Int = DEFAULT_MTU
        set(value) {
            require(value >= MIN_MTU) { "MTU $value is below the minimum $MIN_MTU" }
            field = value
        }

    /** Drops buffered chunks. Call on disconnect so a reconnect does not resume mid-payload. */
    fun reset() {
        chunkBuffers.clear()
    }

    /**
     * Frames [payload] for [cmd], splitting it across as many BLE writes as the current
     * MTU needs. Writes must go out in order and each must complete before the next.
     */
    fun encode(cmd: CmfCommand, payload: ByteArray = ByteArray(0)): List<ByteArray> {
        val bodies = if (cmd.isEncrypted) encryptedBodies(payload) else plaintextBodies(payload)

        return bodies.mapIndexed { index, body ->
            CmfFrame.header(cmd, body.size, bodies.size, index + 1) + body
        }
    }

    private fun plaintextBodies(payload: ByteArray): List<ByteArray> {
        if (payload.isEmpty()) return listOf(ByteArray(0))

        val chunkSize = mtu - 20
        return payload.chunked(chunkSize) { chunk -> chunk + CmfFrame.crc32le(chunk) }
    }

    private fun encryptedBodies(payload: ByteArray): List<ByteArray> {
        if (payload.isEmpty()) return listOf(ByteArray(0))

        val key = checkNotNull(sessionKey) { "Cannot encrypt ${payload.size} bytes without a session key" }

        // AES emits whole 16-byte blocks, so size the plaintext to land just under a
        // block boundary once the CRC and at least one padding byte are added.
        val maxCiphertext = ((mtu - CmfFrame.HEADER_SIZE) / 16) * 16
        val maxPlaintext = maxCiphertext - CRC_SIZE - 1
        check(maxPlaintext > 0) { "MTU $mtu is too small to carry an encrypted payload" }

        return payload.chunked(maxPlaintext) { chunk ->
            CmfCrypto.encrypt(chunk + CmfFrame.crc32le(chunk), key)
        }
    }

    private inline fun ByteArray.chunked(size: Int, transform: (ByteArray) -> ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>((this.size + size - 1) / size)
        var offset = 0
        while (offset < this.size) {
            val end = minOf(offset + size, this.size)
            out.add(transform(copyOfRange(offset, end)))
            offset = end
        }
        return out
    }

    /** Feeds one inbound BLE value through header parsing, decryption and reassembly. */
    fun decode(value: ByteArray): CmfDecoded {
        val frame = CmfFrame.parse(value)
            ?: return CmfDecoded.Dropped(CmfDropReason.MALFORMED_HEADER, null)

        val cmd = frame.command
            ?: return CmfCommand.acknowledgedBy(frame.cmd1, frame.cmd2)
                ?.let {
                    CmfDecoded.Acknowledgement(
                        of = it,
                        cmd1 = frame.cmd1,
                        cmd2 = frame.cmd2,
                        payload = decryptUnknownBody(frame) ?: ByteArray(0),
                    )
                }
                ?: CmfDecoded.Dropped(
                    CmfDropReason.UNKNOWN_COMMAND,
                    cmd = null,
                    cmd1 = frame.cmd1,
                    cmd2 = frame.cmd2,
                    payload = decryptUnknownBody(frame),
                )

        val chunk = when (val body = extractBody(cmd, frame)) {
            is BodyResult.Ok -> body.bytes
            is BodyResult.Fail -> {
                chunkBuffers.remove(cmd)
                return CmfDecoded.Dropped(body.reason, cmd, frame.cmd1, frame.cmd2)
            }
        }

        if (frame.chunkCount <= 1) {
            return CmfDecoded.Command(cmd, chunk)
        }

        return reassemble(cmd, frame, chunk)
    }

    private sealed interface BodyResult {
        class Ok(val bytes: ByteArray) : BodyResult
        class Fail(val reason: CmfDropReason) : BodyResult
    }

    private fun extractBody(cmd: CmfCommand, frame: CmfFrame.Parsed): BodyResult {
        if (frame.bodyLength == 0) return BodyResult.Ok(ByteArray(0))

        if (!cmd.isEncrypted) return BodyResult.Ok(stripPlaintextCrc(frame.body))

        val key = sessionKey ?: return BodyResult.Fail(CmfDropReason.NO_SESSION_KEY)

        val ciphertext = frame.body.copyOfRange(0, minOf(frame.bodyLength, frame.body.size))
        val plaintext = try {
            CmfCrypto.decrypt(ciphertext, key)
        } catch (_: GeneralSecurityException) {
            return BodyResult.Fail(CmfDropReason.DECRYPT_FAILED)
        }

        if (plaintext.size < CRC_SIZE) return BodyResult.Fail(CmfDropReason.CRC_MISMATCH)

        val payload = plaintext.copyOfRange(0, plaintext.size - CRC_SIZE)
        val expected = CmfFrame.readUint32le(plaintext, plaintext.size - CRC_SIZE)
        val actual = CmfFrame.readUint32le(CmfFrame.crc32le(payload), 0)
        if (expected != actual) return BodyResult.Fail(CmfDropReason.CRC_MISMATCH)

        return BodyResult.Ok(payload)
    }

    /**
     * Recovers the body of a command reCMF does not know.
     *
     * Unknown commands are assumed encrypted, which is what the watch does for everything
     * except the four that cannot be. The CRC is the check that the guess was right: if it
     * matches, these really are the plaintext bytes and they are worth showing; if not,
     * nothing is claimed.
     */
    private fun decryptUnknownBody(frame: CmfFrame.Parsed): ByteArray? {
        if (frame.bodyLength == 0) return null
        val key = sessionKey ?: return null

        val ciphertext = frame.body.copyOfRange(0, minOf(frame.bodyLength, frame.body.size))
        val plaintext = try {
            CmfCrypto.decrypt(ciphertext, key)
        } catch (_: GeneralSecurityException) {
            return null
        }

        if (plaintext.size < CRC_SIZE) return null

        val payload = plaintext.copyOfRange(0, plaintext.size - CRC_SIZE)
        val expected = CmfFrame.readUint32le(plaintext, plaintext.size - CRC_SIZE)
        val actual = CmfFrame.readUint32le(CmfFrame.crc32le(payload), 0)

        return payload.takeIf { expected == actual }
    }

    /**
     * Our plaintext encoder appends a CRC32 the way the encrypted one does, but the
     * watch does not always append one to the plaintext frames it sends — the pairing
     * reply notably arrives bare.
     *
     * So rather than trusting the length field, check whether the trailing four bytes
     * actually are the CRC of what precedes them and strip them only then. A bare body
     * passes through untouched, and a false match costs one in 2^32.
     */
    private fun stripPlaintextCrc(body: ByteArray): ByteArray {
        if (body.size <= CRC_SIZE) return body

        val withoutCrc = body.copyOfRange(0, body.size - CRC_SIZE)
        val trailing = CmfFrame.readUint32le(body, body.size - CRC_SIZE)
        val computed = CmfFrame.readUint32le(CmfFrame.crc32le(withoutCrc), 0)

        return if (trailing == computed) withoutCrc else body
    }

    private fun reassemble(cmd: CmfCommand, frame: CmfFrame.Parsed, chunk: ByteArray): CmfDecoded {
        if (cmd !in chunkBuffers && chunkBuffers.size >= MAX_CONCURRENT_REASSEMBLIES) {
            // The watch interleaves at most a couple of transfers. More than that means
            // buffers are being abandoned rather than completed, and each one is allowed
            // to reach maxReassemblySize — so refuse instead of keeping one per command.
            return CmfDecoded.Dropped(CmfDropReason.REASSEMBLY_TOO_LARGE, cmd, frame.cmd1, frame.cmd2)
        }

        val buffer = chunkBuffers.getOrPut(cmd) { ChunkBuffer() }

        if (frame.chunkIndex != buffer.expectedChunk) {
            if (frame.chunkIndex != 1) {
                // Lost a chunk mid-payload and this is not the start of a new one, so
                // there is nothing coherent left to build. Drop the whole transfer.
                chunkBuffers.remove(cmd)
                return CmfDecoded.Dropped(CmfDropReason.CHUNK_OUT_OF_ORDER, cmd, frame.cmd1, frame.cmd2)
            }
            // The watch restarted the transfer — discard what we had and follow it.
            buffer.reset()
        }

        if (buffer.bytes.size() + chunk.size > maxReassemblySize) {
            chunkBuffers.remove(cmd)
            return CmfDecoded.Dropped(CmfDropReason.REASSEMBLY_TOO_LARGE, cmd, frame.cmd1, frame.cmd2)
        }

        buffer.bytes.write(chunk)
        buffer.expectedChunk = frame.chunkIndex + 1

        if (frame.chunkIndex != frame.chunkCount) return CmfDecoded.Pending

        chunkBuffers.remove(cmd)
        return CmfDecoded.Command(cmd, buffer.bytes.toByteArray())
    }

    private class ChunkBuffer {
        var expectedChunk: Int = 1
        val bytes = ByteArrayOutputStream()

        fun reset() {
            expectedChunk = 1
            bytes.reset()
        }
    }

    companion object {
        const val CRC_SIZE: Int = 4

        /** What Android negotiates by default before an explicit MTU request lands. */
        const val DEFAULT_MTU: Int = 247

        /** Below this the encrypted path cannot fit a header, a CRC and one AES block. */
        const val MIN_MTU: Int = 32

        /**
         * A full activity backlog is tens of kilobytes; a megabyte is comfortably above
         * any real payload and far below what would threaten the process.
         */
        const val DEFAULT_MAX_REASSEMBLY_SIZE: Int = 1 shl 20

        /**
         * Bounds the total as well as each buffer: without it every command could hold
         * a buffer of its own, and the ceiling above would apply to each of them.
         */
        const val MAX_CONCURRENT_REASSEMBLIES: Int = 4
    }
}
