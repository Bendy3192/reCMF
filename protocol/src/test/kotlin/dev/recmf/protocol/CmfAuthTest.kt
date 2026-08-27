package dev.recmf.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CmfAuthTest {
    private val appSecret = "aabbccddeeff00112233445566778899".hexToBytes()
    private val random1 = ByteArray(16) { 0x11 }
    private val random2 = ByteArray(16) { 0x22 }

    private fun authenticator(storedKey: ByteArray? = null) =
        CmfAuthenticator("Test Phone", storedKey, randomBytes = { size -> random1.copyOf(size) })

    private fun pairReply(secret: ByteArray = appSecret) = random2 + CmfCrypto.sha256(random2, secret)

    private fun shellReply(secret: ByteArray = appSecret) = "GETSECRET:${secret.toHex()},OK".toByteArray()

    private inline fun <reified T : CmfAuthAction> List<CmfAuthAction>.only(): T {
        assertEquals(1, size, "expected one action, got $this")
        return assertInstanceOf(T::class.java, single())
    }

    @Test
    fun `without a stored key the handshake starts by asking for the app secret`() {
        val actions = authenticator().start()

        assertEquals(CmfAuthenticator.SHELL_GET_SECRET, actions.only<CmfAuthAction.WriteShell>().text)
    }

    @Test
    fun `with a stored key the handshake skips pairing`() {
        val stored = ByteArray(16) { 0x33 }
        val actions = authenticator(stored)

        val emitted = actions.start()
        assertEquals(2, emitted.size)
        assertArrayEquals(stored, (emitted[0] as CmfAuthAction.UseSessionKey).key)
        assertEquals(CmfCommand.AUTH_PHONE_NAME, (emitted[1] as CmfAuthAction.Send).cmd)
    }

    @Test
    fun `the phone name is sent behind the 0xa5 marker`() {
        val send = authenticator(ByteArray(16)).start().filterIsInstance<CmfAuthAction.Send>().single()

        assertEquals(0xa5.toByte(), send.payload[0])
        assertEquals("Test Phone", String(send.payload, 1, send.payload.size - 1))
    }

    @Test
    fun `pairing negotiates the documented K1`() {
        val auth = authenticator()
        auth.start()

        val pairRequest = auth.onShellData(shellReply()).only<CmfAuthAction.Send>()
        assertEquals(CmfCommand.AUTH_PAIR_REQUEST, pairRequest.cmd)
        assertArrayEquals(random1, pairRequest.payload.copyOfRange(0, 16))
        assertArrayEquals(
            CmfCrypto.sha256(random1, appSecret),
            pairRequest.payload.copyOfRange(16, 48),
        )

        val actions = auth.onCommand(CmfCommand.AUTH_PAIR_REPLY, pairReply())
        val expectedK1 = CmfCrypto.sha256(random1, random2, appSecret).copyOf(16)

        assertArrayEquals(expectedK1, actions.filterIsInstance<CmfAuthAction.PersistAuthKey>().single().key)
        assertArrayEquals(expectedK1, actions.filterIsInstance<CmfAuthAction.UseSessionKey>().single().key)
        assertEquals(
            CmfCommand.AUTH_PHONE_NAME,
            actions.filterIsInstance<CmfAuthAction.Send>().single().cmd,
        )
    }

    @Test
    fun `the pair request goes out unencrypted`() {
        // It has to: both sides are still deriving the key it would be encrypted under.
        assertFalse(CmfCommand.AUTH_PAIR_REQUEST.isEncrypted)
        assertFalse(CmfCommand.AUTH_PAIR_REPLY.isEncrypted)
    }

    @Test
    fun `a pair reply signed with the wrong secret is rejected`() {
        val auth = authenticator()
        auth.start()
        auth.onShellData(shellReply())

        val actions = auth.onCommand(CmfCommand.AUTH_PAIR_REPLY, pairReply(ByteArray(16) { 0x77 }))

        assertTrue(actions.single() is CmfAuthAction.Failed, "expected rejection, got $actions")
    }

    @Test
    fun `a truncated pair reply is rejected rather than read out of bounds`() {
        val auth = authenticator()
        auth.start()
        auth.onShellData(shellReply())

        val actions = auth.onCommand(CmfCommand.AUTH_PAIR_REPLY, ByteArray(20))

        assertInstanceOf(CmfAuthAction.Failed::class.java, actions.single())
    }

    @Test
    fun `a shell error reply fails the handshake`() {
        val auth = authenticator()
        auth.start()

        val actions = auth.onShellData("GETSECRET:${appSecret.toHex()},FAIL".toByteArray())

        assertInstanceOf(CmfAuthAction.Failed::class.java, actions.single())
    }

    @Test
    fun `unrelated shell chatter is ignored`() {
        val auth = authenticator()
        auth.start()

        assertTrue(auth.onShellData("AT+SOMETHINGELSE".toByteArray()).isEmpty())
    }

    @Test
    fun `the session key is derived from the nonce, not reused from K1`() {
        val k1 = ByteArray(16) { 0x44 }
        val auth = authenticator(k1)
        auth.start()

        assertEquals(
            CmfCommand.AUTH_NONCE_REQUEST,
            auth.onCommand(CmfCommand.AUTH_WATCH_MAC, ByteArray(6)).only<CmfAuthAction.Send>().cmd,
        )

        val nonce = ByteArray(16) { 0x55 }
        val actions = auth.onCommand(CmfCommand.AUTH_NONCE_REPLY, nonce)
        val sessionKey = actions.filterIsInstance<CmfAuthAction.UseSessionKey>().single().key

        assertArrayEquals(CmfCrypto.sha256(nonce, k1).copyOf(16), sessionKey)
        assertFalse(sessionKey.contentEquals(k1), "session key must not be K1 itself")
        assertEquals(
            CmfCommand.AUTHENTICATED_CONFIRM_REQUEST,
            actions.filterIsInstance<CmfAuthAction.Send>().single().cmd,
        )
    }

    @Test
    fun `a different nonce yields a different session key`() {
        val k1 = ByteArray(16) { 0x44 }

        fun sessionKeyFor(nonceByte: Byte): ByteArray {
            val auth = authenticator(k1).apply { start() }
            return auth.onCommand(CmfCommand.AUTH_NONCE_REPLY, ByteArray(16) { nonceByte })
                .filterIsInstance<CmfAuthAction.UseSessionKey>().single().key
        }

        assertFalse(sessionKeyFor(0x01).contentEquals(sessionKeyFor(0x02)))
    }

    @Test
    fun `the confirm reply marks the session authenticated`() {
        val auth = authenticator(ByteArray(16))
        auth.start()
        assertFalse(auth.isAuthenticated)

        val actions = auth.onCommand(CmfCommand.AUTHENTICATED_CONFIRM_REPLY, ByteArray(0))

        assertEquals(CmfAuthAction.Authenticated, actions.single())
        assertTrue(auth.isAuthenticated)
    }

    @Test
    fun `restarting clears the authenticated flag`() {
        val auth = authenticator(ByteArray(16))
        auth.start()
        auth.onCommand(CmfCommand.AUTHENTICATED_CONFIRM_REPLY, ByteArray(0))
        check(auth.isAuthenticated)

        auth.start()

        assertFalse(auth.isAuthenticated)
    }

    @Test
    fun `AUTH_FAILED surfaces as a failure`() {
        val auth = authenticator(ByteArray(16))
        auth.start()

        assertInstanceOf(
            CmfAuthAction.Failed::class.java,
            auth.onCommand(CmfCommand.AUTH_FAILED, ByteArray(0)).single(),
        )
    }

    @Test
    fun `commands the handshake does not care about are ignored`() {
        val auth = authenticator(ByteArray(16))
        auth.start()

        assertTrue(auth.onCommand(CmfCommand.BATTERY, byteArrayOf(50, 0)).isEmpty())
    }

    @Test
    fun `a re-paired watch reuses the freshly negotiated key on the next nonce`() {
        val auth = authenticator()
        auth.start()
        auth.onShellData(shellReply())
        auth.onCommand(CmfCommand.AUTH_PAIR_REPLY, pairReply())

        val nonce = ByteArray(16) { 0x66 }
        val expectedK1 = CmfCrypto.sha256(random1, random2, appSecret).copyOf(16)

        assertArrayEquals(
            CmfCrypto.sha256(nonce, expectedK1).copyOf(16),
            auth.onCommand(CmfCommand.AUTH_NONCE_REPLY, nonce)
                .filterIsInstance<CmfAuthAction.UseSessionKey>().single().key,
        )
    }
}
