#!/usr/bin/env python3
"""
Takes a CMF Watch Pro 2 watchface apart: its elements, and its pictures as PNGs.

The format was worked out from two files read off the wire — see ROADMAP.md for how — and
every claim it makes is checked as it reads, so a file that is not this format fails here
rather than halfway through a Bluetooth transfer.

    file      header 36 | name block | element table | resources | header again, 36
    element   61 | count:u8 | 00 | start:u32 | count sizes:u16 | placement
    image     tag:u8 | dimensions:u24 | length:u32 | LZ4 block

`count` is how many pictures an element cycles through: 1 is a static image, 10 a digit
place, 7 the days of the week, 2 a two-state icon. The dimensions are packed oddly — the low
twelve bits are the width times four and the high twelve the height times two — and the tag
is the pixel format: 4 is RGB565, 5 is RGB888.

The pictures are LZ4 blocks. Their match offsets are usually exactly one row, which is a
compressor saying "this row is the last one again" and is what makes a watchface with large
flat areas come to a few kilobytes.

Usage:
    python3 tools/watchface.py face.bin              # what is in it
    python3 tools/watchface.py face.bin --png out/   # and every picture as a PNG
"""

from __future__ import annotations

import argparse
import os
import struct
import sys
import zlib

HEADER_BYTES = 36
CONTENT_SIZE_OFFSET = 24
RESOURCE_SIZE_OFFSET = 28
TABLE_START = 120

BYTES_PER_PIXEL = {4: 2, 5: 3}


def lz4_block(src: bytes) -> tuple[bytes, int]:
    """Plain LZ4 block decompression: token, literals, offset, match."""
    out = bytearray()
    at = 0
    end = len(src)

    while at < end:
        token = src[at]
        at += 1

        literals = token >> 4
        if literals == 15:
            literals, at = extend(src, at, literals)
        if at + literals > end:
            raise ValueError("literals run past the end of the block")
        out += src[at:at + literals]
        at += literals

        # The last sequence is literals and nothing else, which is how a block ends.
        if at >= end:
            return bytes(out), at

        offset = src[at] | (src[at + 1] << 8)
        at += 2
        if offset == 0 or offset > len(out):
            raise ValueError(f"match offset {offset} points outside what has been decoded")

        length = token & 0x0F
        if length == 15:
            length, at = extend(src, at, length)
        length += 4

        # Byte at a time on purpose: a match may overlap its own output, which is how a
        # run of one colour is spelled — offset 3, length however many bytes it fills.
        start = len(out) - offset
        for step in range(length):
            out.append(out[start + step])

    return bytes(out), at


def extend(src: bytes, at: int, value: int) -> tuple[int, int]:
    """The 255-at-a-time length extension both fields use."""
    while True:
        if at >= len(src):
            raise ValueError("length extension runs past the end of the block")
        more = src[at]
        at += 1
        value += more
        if more != 255:
            return value, at


def dimensions(word: int) -> tuple[int, int]:
    """Width times four in the low twelve bits, height times two in the high twelve."""
    return (word & 0xFFF) // 4, (word >> 12) // 2


def elements(face: bytes) -> list[tuple[int, int, list[int]]]:
    """The element table, followed from the first resource to the last."""
    content, resources = struct.unpack_from("<II", face, CONTENT_SIZE_OFFSET)
    table = content - resources

    found: dict[int, tuple[int, list[int]]] = {}
    for at in range(TABLE_START, table - 7):
        if face[at] != 0x61 or face[at + 2] != 0:
            continue
        count = face[at + 1]
        if not 1 <= count <= 16 or at + 7 + 2 * count > table:
            continue
        start = struct.unpack_from("<I", face, at + 3)[0]
        sizes = list(struct.unpack_from(f"<{count}H", face, at + 7))
        if 0 in sizes or not 0 < start <= content or start + sum(sizes) > content:
            continue
        found.setdefault(start, (count, sizes))

    walked: list[tuple[int, int, list[int]]] = []
    seen: set[int] = set()
    where = table
    while where in found and where not in seen:
        seen.add(where)
        count, sizes = found[where]
        walked.append((where, count, sizes))
        where += sum(sizes)
    return walked


def rgb(pixels: bytes, tag: int) -> bytes:
    """Whatever the picture stores, as three bytes a pixel."""
    if tag == 5:
        return pixels
    out = bytearray()
    for at in range(0, len(pixels), 2):
        value = pixels[at] | (pixels[at + 1] << 8)
        red, green, blue = (value >> 11) & 0x1F, (value >> 5) & 0x3F, value & 0x1F
        out += bytes((red * 255 // 31, green * 255 // 63, blue * 255 // 31))
    return bytes(out)


def write_png(path: str, width: int, height: int, pixels: bytes) -> None:
    rows = b"".join(
        b"\x00" + pixels[y * width * 3:(y + 1) * width * 3] for y in range(height)
    )

    def chunk(kind: bytes, body: bytes) -> bytes:
        return (struct.pack(">I", len(body)) + kind + body
                + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF))

    with open(path, "wb") as out:
        out.write(b"\x89PNG\r\n\x1a\n")
        out.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)))
        out.write(chunk(b"IDAT", zlib.compress(rows, 9)))
        out.write(chunk(b"IEND", b""))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("face")
    parser.add_argument("--png", metavar="DIR", help="write every picture here as a PNG")
    args = parser.parse_args()

    face = open(args.face, "rb").read()
    content = struct.unpack_from("<I", face, CONTENT_SIZE_OFFSET)[0]

    if content + HEADER_BYTES != len(face) or face[content:] != face[:HEADER_BYTES]:
        print("This file does not account for itself: its header says it ends somewhere "
              "other than where it does, or it does not close with its own first 36 bytes. "
              "A face rebuilt from a Bluetooth capture with the transfer's checksums left "
              "in looks exactly like this.", file=sys.stderr)
        raise SystemExit(1)

    name = face[8:20].split(b"\0")[0].decode("utf-8", "replace")
    print(f"{name}: {len(face)} bytes")

    if args.png:
        os.makedirs(args.png, exist_ok=True)

    for index, (start, count, sizes) in enumerate(elements(face)):
        kinds = {1: "static", 2: "two-state", 7: "days of the week", 10: "digits 0-9"}
        print(f"  element {index}: {count} picture(s), {kinds.get(count, 'unknown')}")
        at = start
        for which, size in enumerate(sizes):
            tag = face[at]
            word = face[at + 1] | (face[at + 2] << 8) | (face[at + 3] << 16)
            width, height = dimensions(word)
            pixels, used = lz4_block(face[at + 8:at + size])

            wanted = width * height * BYTES_PER_PIXEL.get(tag, 0)
            state = "ok" if used == size - 8 and len(pixels) == wanted else "MISMATCH"
            depth = {4: "RGB565", 5: "RGB888"}.get(tag, f"tag {tag:02x}")
            print(f"      [{which}] {width:4d} x {height:<4d} {depth:7s} "
                  f"{size:6d} B -> {len(pixels):8d}  {state}")

            if args.png and state == "ok":
                write_png(os.path.join(args.png, f"{index:02d}-{which}.png"),
                          width, height, rgb(pixels, tag))
            at += size


if __name__ == "__main__":
    try:
        main()
    except BrokenPipeError:
        # Piping into head is a normal way to read this; a traceback is not a useful
        # answer to it.
        os._exit(0)
