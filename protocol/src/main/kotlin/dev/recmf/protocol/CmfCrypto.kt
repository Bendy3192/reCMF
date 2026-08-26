/*
 * reCMF — a third-party companion app for the CMF Watch Pro 2.
 * Copyright (C) 2026 reCMF contributors
 *
 * Ported from Gadgetbridge (Copyright (C) 2024 José Rebelo), AGPL-3.0-or-later.
 * See LICENSE and NOTICE at the repository root.
 */
package dev.recmf.protocol

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-CBC with a fixed IV baked into the firmware, plus the SHA-256 key derivations
 * the pairing handshake is built out of.
 *
 * The IV being constant is the watch's design, not ours — every session re-derives a
 * fresh key instead, which is what keeps two sessions from sharing a keystream.
 */
object CmfCrypto {
    /** Hard-coded in the watch firmware; the same for every device. */
    val AES_IV: ByteArray = byteArrayOf(
        0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57,
        0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x5a,
    )

    /** AES keys and the derived halves of every SHA-256 digest are 16 bytes wide. */
    const val KEY_SIZE: Int = 16

    fun encrypt(data: ByteArray, key: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE, key).doFinal(data)

    fun decrypt(data: ByteArray, key: ByteArray): ByteArray = cipher(Cipher.DECRYPT_MODE, key).doFinal(data)

    private fun cipher(mode: Int, key: ByteArray): Cipher {
        require(key.size == KEY_SIZE) { "Key must be $KEY_SIZE bytes, got ${key.size}" }
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(AES_IV))
        }
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) digest.update(part)
        return digest.digest()
    }

    /** SHA-256 truncated to an AES key. Every key in the handshake is derived this way. */
    fun deriveKey(vararg parts: ByteArray): ByteArray = sha256(*parts).copyOf(KEY_SIZE)
}
