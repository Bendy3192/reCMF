package dev.recmf.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Utf8Test {

    @Test
    fun `text that fits is passed through whole`() {
        assertArrayEquals("Ivan".toByteArray(), "Ivan".truncateToUtf8Bytes(20))
        assertArrayEquals("Иван".toByteArray(), "Иван".truncateToUtf8Bytes(20))
    }

    @Test
    fun `a Cyrillic string is cut on a character boundary`() {
        // Two bytes per character, so an odd limit lands mid-character. Cutting there
        // would send half a code point and the watch would render the rest as garbage.
        val cut = "Иван".truncateToUtf8Bytes(5)

        assertEquals("Ив", String(cut, Charsets.UTF_8))
        assertTrue('�' !in String(cut, Charsets.UTF_8))
    }

    @Test
    fun `a limit of zero yields nothing rather than a broken character`() {
        assertEquals(0, "Иван".truncateToUtf8Bytes(0).size)
    }

    @Test
    fun `an emoji is dropped whole rather than split across its bytes`() {
        // Four bytes for one code point: three of them is not a shorter emoji, it is
        // nothing at all.
        assertEquals(0, "🙂".truncateToUtf8Bytes(3).size)
        assertEquals(4, "🙂".truncateToUtf8Bytes(4).size)
    }

    @Test
    fun `ascii is cut at exactly the limit`() {
        assertEquals("abcde", String("abcdefgh".truncateToUtf8Bytes(5), Charsets.UTF_8))
    }
}
