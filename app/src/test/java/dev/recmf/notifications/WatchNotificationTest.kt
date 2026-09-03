package dev.recmf.notifications

import dev.recmf.protocol.truncateToUtf8Bytes
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class WatchNotificationTest {
    private fun notification(title: String = "Ivan", body: String = "hello") = WatchNotification(
        icon = NotificationIcon.TELEGRAM,
        title = title,
        body = body,
        whenEpochSeconds = 0x01020304,
    )

    @Test
    fun `the payload matches the documented layout`() {
        val payload = notification().toPayload()
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)

        assertEquals(NotificationIcon.TELEGRAM.code, buf.get())
        assertEquals(0, buf.get())
        assertEquals(0x01020304, buf.int)
        assertEquals(4, buf.get().toInt())

        val title = ByteArray(4).also { buf.get(it) }
        assertEquals("Ivan", String(title, StandardCharsets.UTF_8))

        val body = ByteArray(buf.remaining()).also { buf.get(it) }
        assertEquals("hello", String(body, StandardCharsets.UTF_8))
    }

    @Test
    fun `Cyrillic text is never cut mid-character`() {
        // Each Cyrillic letter is two bytes, so a naive cut at 20 bytes lands inside
        // one and the watch would render the rest of the line as garbage.
        val title = "Иван Петрович Сидоров"
        val truncated = title.truncateToUtf8Bytes(WatchNotification.MAX_TITLE_BYTES)

        assertTrue(truncated.size <= WatchNotification.MAX_TITLE_BYTES)
        val decoded = String(truncated, StandardCharsets.UTF_8)
        assertTrue(title.startsWith(decoded), "'$decoded' is not a prefix of the original")
        assertTrue('�' !in decoded, "decoded to a replacement character: $decoded")
    }

    @Test
    fun `an over-long body is truncated, not rejected`() {
        val payload = notification(body = "x".repeat(500)).toPayload()

        assertEquals(
            WatchNotification.HEADER_SIZE + 4 + WatchNotification.MAX_BODY_BYTES,
            payload.size,
        )
    }

    @Test
    fun `known apps get their own icon and the rest fall back`() {
        assertEquals(NotificationIcon.TELEGRAM, NotificationIcon.forPackage("org.telegram.messenger"))
        assertEquals(NotificationIcon.WHATSAPP, NotificationIcon.forPackage("com.whatsapp"))
        assertEquals(NotificationIcon.UNKNOWN, NotificationIcon.forPackage("com.example.something"))
    }

    @Test
    fun `marking a notification as a call does not change the bytes`() {
        // isCall decides whether the notification may be delivered while the screen is
        // on. The watch is told the same thing either way, and this pins that: a delivery
        // rule leaking into the wire format would be a bug that only shows on a watch.
        val plain = notification()

        assertArrayEquals(plain.toPayload(), plain.copy(isCall = true).toPayload())
    }
    /** The title out of a payload, by the length the payload declares. */
    private fun titleOf(payload: ByteArray): String {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        buf.position(WatchNotification.HEADER_SIZE - 1)
        val length = buf.get().toInt()
        return String(ByteArray(length).also { buf.get(it) }, StandardCharsets.UTF_8)
    }

    @Test
    fun `a title that fits arrives whole`() {
        // The one this was reported for. Thirteen characters, twenty-two bytes, and under
        // the old ceiling it reached the wrist as "Погода: Тве" — which reads as the app
        // misspelling a city rather than as a limit.
        val whole = "Погода: Тверь"

        assertEquals(whole, titleOf(notification(title = whole).toPayload()))
    }

    @Test
    fun `a title that does not fit says so`() {
        val long = "Объявление о плановом отключении горячей воды в вашем доме"

        val sent = titleOf(notification(title = long).toPayload())

        assertTrue(sent.endsWith("…"), "a cut title should be marked: $sent")
        assertTrue(long.startsWith(sent.dropLast(1)), "'$sent' is not a prefix of the original")
        assertTrue(
            sent.toByteArray(StandardCharsets.UTF_8).size <= WatchNotification.MAX_TITLE_BYTES,
            "the mark has to fit inside the budget, not on top of it",
        )
    }

    @Test
    fun `a title that fits exactly is not marked`() {
        // The ellipsis costs three bytes of the budget it is measured against, so the
        // boundary is the place to get this wrong.
        val exact = "a".repeat(WatchNotification.MAX_TITLE_BYTES)

        val sent = titleOf(notification(title = exact).toPayload())

        assertEquals(exact, sent)
    }

}
