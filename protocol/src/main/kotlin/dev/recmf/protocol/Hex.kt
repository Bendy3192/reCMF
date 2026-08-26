/*
 * reCMF — a third-party companion app for the CMF Watch Pro 2.
 * Copyright (C) 2026 reCMF contributors
 *
 * This file is derived from Gadgetbridge (Copyright (C) 2024 José Rebelo),
 * licensed under the GNU Affero General Public License v3 or later.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version. See <https://www.gnu.org/licenses/>.
 */
package dev.recmf.protocol

private const val HEX_DIGITS = "0123456789abcdef"

/** Lower-case hex, no separators. */
fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        out.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0f])
    }
    return out.toString()
}

/**
 * Parses a hex string, tolerating a `0x` prefix and interspersed whitespace, so a key
 * pasted out of a log or a wiki page still works.
 *
 * @throws IllegalArgumentException if the cleaned string is not an even run of hex digits.
 */
fun String.hexToBytes(): ByteArray {
    val cleaned = filterNot { it.isWhitespace() }.removePrefix("0x").removePrefix("0X")
    require(cleaned.length % 2 == 0) { "Hex string has odd length: ${cleaned.length}" }
    val out = ByteArray(cleaned.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(cleaned[i * 2], 16)
        val lo = Character.digit(cleaned[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "Not a hex string: $this" }
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
