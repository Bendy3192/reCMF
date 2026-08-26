/*
 * reCMF — a third-party companion app for the CMF Watch Pro 2.
 * Copyright (C) 2026 reCMF contributors
 *
 * Ported from Gadgetbridge (Copyright (C) 2024 José Rebelo), AGPL-3.0-or-later.
 * See LICENSE and NOTICE at the repository root.
 */
package dev.recmf.protocol

import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/** Something the transport should do on the authenticator's behalf. */
sealed interface CmfAuthAction {
    /** Frame and write [payload] for [cmd] on the command characteristic. */
    class Send(val cmd: CmfCommand, val payload: ByteArray) : CmfAuthAction

    /** Write raw (unframed) text to the shell characteristic. */
    data class WriteShell(val text: String) : CmfAuthAction

    /** Install [key] as the codec's session key from here on. */
    class UseSessionKey(val key: ByteArray) : CmfAuthAction

    /** Newly negotiated long-term key — store it so the next connection skips pairing. */
    class PersistAuthKey(val key: ByteArray) : CmfAuthAction

    /** The watch accepted us; the connection is ready for real commands. */
    data object Authenticated : CmfAuthAction

    data class Failed(val reason: String) : CmfAuthAction
}

/**
 * The pairing and session-key handshake, as a pure state machine.
 *
 * Two paths lead in. With a stored long-term key (`K1`) we go straight to the nonce
 * exchange. Without one we ask the watch's shell characteristic for its app secret and
 * negotiate `K1` first — this is what lets reCMF pair without the user ever having to
 * find an auth key by hand.
 *
 * Either way the key that actually encrypts traffic is per-session: `SHA-256(nonce ||
 * K1)`, truncated to 16 bytes. `K1` itself never encrypts anything after the handshake.
 *
 * Holds no Android or Bluetooth types, so the whole flow is unit-testable.
 */
class CmfAuthenticator(
    private val phoneName: String,
    storedAuthKey: ByteArray? = null,
    private val randomBytes: (Int) -> ByteArray = ::secureRandomBytes,
) {
    /** The long-term key: either loaded from storage or negotiated during pairing. */
    private var authKey: ByteArray? = storedAuthKey?.also {
        require(it.size == CmfCrypto.KEY_SIZE) { "Stored auth key must be ${CmfCrypto.KEY_SIZE} bytes" }
    }

    private var appSecret: ByteArray? = null
    private var random1: ByteArray? = null

    /** True once the watch has confirmed the session; reset by [start]. */
    var isAuthenticated: Boolean = false
        private set

    /** Emits the first step of the handshake. Safe to call again on reconnect. */
    fun start(): List<CmfAuthAction> {
        isAuthenticated = false
        appSecret = null
        random1 = null

        val key = authKey
            ?: return listOf(CmfAuthAction.WriteShell(SHELL_GET_SECRET))

        // Until the nonce exchange re-keys us, K1 itself is the session key.
        return listOf(CmfAuthAction.UseSessionKey(key), sendPhoneName())
    }

    /**
     * Handles raw bytes from the shell characteristic, which answers [SHELL_GET_SECRET]
     * with `GETSECRET:<32 hex chars>,OK`.
     */
    fun onShellData(value: ByteArray): List<CmfAuthAction> {
        val text = String(value, StandardCharsets.UTF_8).trim()

        if (!text.startsWith(SHELL_SECRET_PREFIX)) {
            // The shell carries other chatter too; ignore what we did not ask for.
            return emptyList()
        }
        if (!text.endsWith(SHELL_SECRET_SUFFIX)) {
            return listOf(CmfAuthAction.Failed("Watch refused to hand out its app secret: $text"))
        }

        val hexStart = SHELL_SECRET_PREFIX.length
        val hexEnd = hexStart + CmfCrypto.KEY_SIZE * 2
        if (text.length < hexEnd) {
            return listOf(CmfAuthAction.Failed("App secret is too short: $text"))
        }

        val secret = try {
            text.substring(hexStart, hexEnd).hexToBytes()
        } catch (e: IllegalArgumentException) {
            return listOf(CmfAuthAction.Failed("App secret is not hex: ${e.message}"))
        }

        val nonce = randomBytes(CmfCrypto.KEY_SIZE)
        appSecret = secret
        random1 = nonce

        // Sent in the clear — there is no shared key yet.
        return listOf(
            CmfAuthAction.Send(
                CmfCommand.AUTH_PAIR_REQUEST,
                nonce + CmfCrypto.sha256(nonce, secret),
            ),
        )
    }

    fun onCommand(cmd: CmfCommand, payload: ByteArray): List<CmfAuthAction> = when (cmd) {
        CmfCommand.AUTH_PAIR_REPLY -> onPairReply(payload)
        CmfCommand.AUTH_WATCH_MAC -> listOf(CmfAuthAction.Send(CmfCommand.AUTH_NONCE_REQUEST, CmfFrame.A5))
        CmfCommand.AUTH_NONCE_REPLY -> onNonceReply(payload)
        CmfCommand.AUTHENTICATED_CONFIRM_REPLY -> {
            isAuthenticated = true
            listOf(CmfAuthAction.Authenticated)
        }

        CmfCommand.AUTH_FAILED ->
            listOf(CmfAuthAction.Failed("Watch rejected our key — unpair it and pair again"))

        else -> emptyList()
    }

    private fun onPairReply(payload: ByteArray): List<CmfAuthAction> {
        val secret = appSecret ?: return listOf(CmfAuthAction.Failed("Pair reply arrived before the app secret"))
        val nonce1 = random1 ?: return listOf(CmfAuthAction.Failed("Pair reply arrived before we sent a nonce"))

        if (payload.size < PAIR_REPLY_SIZE) {
            return listOf(CmfAuthAction.Failed("Pair reply is ${payload.size} bytes, expected $PAIR_REPLY_SIZE"))
        }

        val random2 = payload.copyOfRange(0, CmfCrypto.KEY_SIZE)
        val signature = payload.copyOfRange(CmfCrypto.KEY_SIZE, PAIR_REPLY_SIZE)

        if (!signature.contentEquals(CmfCrypto.sha256(random2, secret))) {
            return listOf(CmfAuthAction.Failed("Watch's nonce signature does not match the app secret"))
        }

        val key = CmfCrypto.deriveKey(nonce1, random2, secret)
        authKey = key

        return listOf(
            CmfAuthAction.PersistAuthKey(key),
            CmfAuthAction.UseSessionKey(key),
            sendPhoneName(),
        )
    }

    private fun onNonceReply(nonce: ByteArray): List<CmfAuthAction> {
        val key = authKey ?: return listOf(CmfAuthAction.Failed("Nonce arrived before we had a key"))

        return listOf(
            CmfAuthAction.UseSessionKey(CmfCrypto.deriveKey(nonce, key)),
            CmfAuthAction.Send(CmfCommand.AUTHENTICATED_CONFIRM_REQUEST, CmfFrame.A5),
        )
    }

    /** The watch shows this name in its own Bluetooth settings. */
    private fun sendPhoneName() = CmfAuthAction.Send(
        CmfCommand.AUTH_PHONE_NAME,
        CmfFrame.A5 + phoneName.toByteArray(StandardCharsets.UTF_8),
    )

    companion object {
        const val SHELL_GET_SECRET: String = "AT GETSECRET"
        private const val SHELL_SECRET_PREFIX = "GETSECRET:"
        private const val SHELL_SECRET_SUFFIX = ",OK"

        /** 16-byte nonce followed by its 32-byte SHA-256 signature. */
        private const val PAIR_REPLY_SIZE = CmfCrypto.KEY_SIZE + 32

        private val secureRandom by lazy { SecureRandom() }

        fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }
    }
}
