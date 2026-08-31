#!/usr/bin/env python3
"""
Takes a CMF Watch Pro 2 watchface apart: its elements, and its pictures as PNGs.

The format was worked out from two files read off the wire — see ROADMAP.md for how — and
every claim it makes is checked as it reads, so a file that is not this format fails here
rather than halfway through a Bluetooth transfer.

    file      header 36 | name block | element table | resources | header again, 36
    element   61 | count:u8 | 00 | start:u32 | count sizes:u16 | placement
    image     lv_img_header_t:u32 | length:u32 | LZ4 block

`count` is how many pictures an element cycles through: 1 is a static image, 10 a digit
place, 7 the days of the week, 2 a two-state icon.

The image header is **LVGL's own** — the watch runs Zephyr and LVGL — so it is one
little-endian word: colour format in the low 5 bits, three bits that are always zero, two
reserved, then width in 11 bits and height in 11. Only two colour formats appear:
`LV_IMG_CF_TRUE_COLOR` (4), which is RGB565 at two bytes a pixel, and
`LV_IMG_CF_TRUE_COLOR_ALPHA` (5), which is RGB565 followed by one byte of alpha — three
bytes a pixel, and *not* RGB888.

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

TRUE_COLOR = 4
TRUE_COLOR_ALPHA = 5
BYTES_PER_PIXEL = {TRUE_COLOR: 2, TRUE_COLOR_ALPHA: 3}


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


def header(word: int) -> tuple[int, int, int]:
    """LVGL's image header: colour format, width, height."""
    return word & 0x1F, (word >> 10) & 0x7FF, (word >> 21) & 0x7FF


def header_word(cf: int, width: int, height: int) -> int:
    return cf | (width << 10) | (height << 21)


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


def rgba(pixels: bytes, cf: int) -> bytes:
    """Whatever the picture stores, as four bytes a pixel.

    One representation for both formats keeps everything above this honest: a picture that
    carries transparency keeps it through a rebuild instead of being flattened onto black.
    """
    step = BYTES_PER_PIXEL[cf]
    out = bytearray()
    for at in range(0, len(pixels), step):
        value = pixels[at] | (pixels[at + 1] << 8)
        alpha = pixels[at + 2] if cf == TRUE_COLOR_ALPHA else 255
        out += bytes((
            ((value >> 11) & 0x1F) * 255 // 31,
            ((value >> 5) & 0x3F) * 255 // 63,
            (value & 0x1F) * 255 // 31,
            alpha,
        ))
    return bytes(out)



def lz4_compress(src: bytes) -> bytes:
    """A plain LZ4 block, good enough to be smaller than the input and correct enough for
    a decoder that is not ours.

    Greedy: hash four bytes, remember where they were, take the first match that verifies.
    The spec's two end rules are kept because the watch's decoder is not ours to test —
    the last five bytes are always literals, and no match starts within twelve of the end.
    """
    out = bytearray()
    table: dict[int, int] = {}
    at = 0
    anchor = 0
    end = len(src)
    last_match = max(0, end - 12)

    def emit(literals: bytes, match_length: int, offset: int) -> None:
        # A match of the minimum four bytes has a length field of nought, which is not the
        # same as having no match at all. The offset is what says which it is.
        out.append((min(len(literals), 15) << 4) | min(match_length, 15))
        if len(literals) >= 15:
            spill = len(literals) - 15
            while spill >= 255:
                out.append(255)
                spill -= 255
            out.append(spill)
        out.extend(literals)
        if offset:
            out.append(offset & 0xFF)
            out.append(offset >> 8)
            if match_length >= 15:
                spill = match_length - 15
                while spill >= 255:
                    out.append(255)
                    spill -= 255
                out.append(spill)

    while at < last_match:
        key = int.from_bytes(src[at:at + 4], "little")
        found = table.get(key, -1)
        table[key] = at

        if found < 0 or at - found > 0xFFFF or src[found:found + 4] != src[at:at + 4]:
            at += 1
            continue

        length = 4
        while (at + length < last_match
               and src[found + length] == src[at + length]
               and length < 0xFFFF):
            length += 1

        emit(src[anchor:at], length - 4, at - found)
        for step in range(at + 1, at + length):
            if step + 4 <= end:
                table[int.from_bytes(src[step:step + 4], "little")] = step
        at += length
        anchor = at

    emit(src[anchor:], 0, 0)
    return bytes(out)


def encode(pixels: bytes, cf: int) -> bytes:
    """Four bytes a pixel back into whatever the picture stores.

    Round trips exactly: expanding five bits to eight and taking the top five again gives
    back what was there, so a rebuilt picture is the same picture.
    """
    out = bytearray()
    for at in range(0, len(pixels), 4):
        value = ((pixels[at] >> 3) << 11) | ((pixels[at + 1] >> 2) << 5) | (pixels[at + 2] >> 3)
        out += bytes((value & 0xFF, value >> 8))
        if cf == TRUE_COLOR_ALPHA:
            out.append(pixels[at + 3])
    return bytes(out)


def picture(cf: int, width: int, height: int, pixels: bytes) -> bytes:
    """One picture, header and all, ready to sit in the resource blob."""
    body = lz4_compress(encode(pixels, cf))
    return struct.pack("<II", header_word(cf, width, height), len(body)) + body


def all_records(face: bytes, table: int) -> list[tuple[int, int, int, list[int]]]:
    """Every element record in the table, as (offset, count, start, sizes).

    A face can hold several elements pointing at the *same* run of pictures — the hours and
    the minutes share one set of digits — so a rewrite has to move every record that names
    a run, not just the first one found.
    """
    out = []
    for at in range(TABLE_START, table - 7):
        if face[at] != 0x61 or face[at + 2] != 0:
            continue
        count = face[at + 1]
        if not 1 <= count <= 16 or at + 7 + 2 * count > table:
            continue
        start = struct.unpack_from("<I", face, at + 3)[0]
        sizes = list(struct.unpack_from(f"<{count}H", face, at + 7))
        if 0 in sizes or not 0 < start <= len(face) or start + sum(sizes) > len(face):
            continue
        out.append((at, count, start, sizes))
    return out


def rebuild(face: bytes, replaced: dict[tuple[int, int], bytes]) -> bytes:
    """Writes the face back out, with any pictures in [replaced] swapped in.

    Everything before the resources keeps its bytes — the element table is the same shape,
    only the offsets and sizes inside it move — so this touches as little as a rewrite can.
    """
    content, resources = struct.unpack_from("<II", face, CONTENT_SIZE_OFFSET)
    table = content - resources
    out = bytearray(face[:table])

    records = all_records(face, table)
    blob = bytearray()
    moved: dict[int, tuple[int, list[int]]] = {}

    for index, (start, _count, sizes) in enumerate(elements(face)):
        where = table + len(blob)
        built_sizes = []
        at = start
        for which, size in enumerate(sizes):
            cf, width, height = header(struct.unpack_from("<I", face, at)[0])
            pixels = replaced.get((index, which))
            if pixels is None:
                pixels = rgba(lz4_block(face[at + 8:at + size])[0], cf)
            built = picture(cf, width, height, pixels)
            if len(built) > 0xFFFF:
                raise ValueError(
                    f"picture {index}.{which} comes to {len(built)} bytes, and the element "
                    "table has sixteen bits to say so — it has to fit in 65535")
            built_sizes.append(len(built))
            blob += built
            at += size
        moved[start] = (where, built_sizes)

    for at, _count, start, _sizes in records:
        if start not in moved:
            continue
        where, built_sizes = moved[start]
        struct.pack_into("<I", out, at + 3, where)
        for which, size in enumerate(built_sizes):
            struct.pack_into("<H", out, at + 7 + 2 * which, size)

    out += blob
    # Everything before the closing copy, and the pictures alone: the two lengths the header
    # carries. Their difference is the table, which has not moved.
    struct.pack_into("<I", out, CONTENT_SIZE_OFFSET, len(out))
    struct.pack_into("<I", out, RESOURCE_SIZE_OFFSET, len(blob))
    return bytes(out) + bytes(out[:HEADER_BYTES])


def write_png(path: str, width: int, height: int, pixels: bytes) -> None:
    """RGBA, so a picture's transparency survives being looked at."""
    rows = b"".join(
        b"\x00" + pixels[y * width * 4:(y + 1) * width * 4] for y in range(height)
    )

    def chunk(kind: bytes, body: bytes) -> bytes:
        return (struct.pack(">I", len(body)) + kind + body
                + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF))

    with open(path, "wb") as out:
        out.write(b"\x89PNG\r\n\x1a\n")
        out.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)))
        out.write(chunk(b"IDAT", zlib.compress(rows, 9)))
        out.write(chunk(b"IEND", b""))


def pictures_of(face: bytes) -> list[bytes]:
    """Every picture in a face as plain RGB, for comparing one face against another."""
    out = []
    for start, _count, sizes in elements(face):
        at = start
        for size in sizes:
            cf = header(struct.unpack_from("<I", face, at)[0])[0]
            out.append(rgba(lz4_block(face[at + 8:at + size])[0], cf))
            at += size
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("face")
    parser.add_argument("--png", metavar="DIR", help="write every picture here as a PNG")
    parser.add_argument("--rebuild", metavar="FILE",
                        help="write the face back out, recompressed, and check that every "
                             "picture in it still decodes to the same pixels")
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

    if args.rebuild:
        rebuilt = rebuild(face, {})
        before = [pictures_of(face)]
        after = [pictures_of(rebuilt)]
        if before != after:
            print("the rebuilt face does not decode to the same pixels", file=sys.stderr)
            raise SystemExit(1)
        open(args.rebuild, "wb").write(rebuilt)
        print(f"  rebuilt: {len(rebuilt)} bytes, every picture identical")
        return

    for index, (start, count, sizes) in enumerate(elements(face)):
        kinds = {1: "static", 2: "two-state", 7: "days of the week", 10: "digits 0-9"}
        print(f"  element {index}: {count} picture(s), {kinds.get(count, 'unknown')}")
        at = start
        for which, size in enumerate(sizes):
            cf, width, height = header(struct.unpack_from("<I", face, at)[0])
            pixels, used = lz4_block(face[at + 8:at + size])

            wanted = width * height * BYTES_PER_PIXEL.get(cf, 0)
            state = "ok" if used == size - 8 and len(pixels) == wanted else "MISMATCH"
            depth = {TRUE_COLOR: "RGB565", TRUE_COLOR_ALPHA: "RGB565+A"}.get(cf, f"cf {cf}")
            print(f"      [{which}] {width:4d} x {height:<4d} {depth:7s} "
                  f"{size:6d} B -> {len(pixels):8d}  {state}")

            if args.png and state == "ok":
                write_png(os.path.join(args.png, f"{index:02d}-{which}.png"),
                          width, height, rgba(pixels, cf))
            at += size


if __name__ == "__main__":
    try:
        main()
    except BrokenPipeError:
        # Piping into head is a normal way to read this; a traceback is not a useful
        # answer to it.
        os._exit(0)
