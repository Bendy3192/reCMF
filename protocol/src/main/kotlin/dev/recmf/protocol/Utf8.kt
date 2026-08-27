/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.protocol

import java.nio.charset.StandardCharsets

/**
 * Encodes [this] as UTF-8, cut to at most [maxBytes] on a character boundary.
 *
 * Every string reCMF hands the watch goes into a fixed-width field — a notification
 * title, a place name, an alarm label — so all of them need the same cut, and cutting on
 * a byte boundary would send half a code point. For anything outside ASCII, Cyrillic
 * included, that is most of the text.
 *
 * Lives here rather than beside any one caller because it had already been written twice
 * and the alarm labels would have made three.
 */
fun String.truncateToUtf8Bytes(maxBytes: Int): ByteArray {
    val encoded = toByteArray(StandardCharsets.UTF_8)
    if (encoded.size <= maxBytes) return encoded

    var end = maxBytes
    // Continuation bytes are 10xxxxxx; back off until we are on a lead byte.
    while (end > 0 && (encoded[end].toInt() and 0xc0) == 0x80) end--

    return encoded.copyOf(end)
}
