#!/usr/bin/env python3
"""
Reads a CMF watch's own Bluetooth traffic out of an Android HCI snoop log.

The point of this is that the protocol's encryption can be undone with nothing but the
capture — provided the capture contains the pairing. The watch hands its app secret over
the shell characteristic in plain text, both nonces travel unencrypted, and every key
after that is a SHA-256 of things already on the wire:

    K1      = SHA-256(nonce1 || random2 || appSecret)[:16]
    session = SHA-256(nonce || K1)[:16]        re-derived on every reconnect

So this is not breaking anything. It is reading your own devices talking, which is what
the whole project is built on.

A capture needs the pairing in it, because each app that pairs negotiates its own K1.
But the app secret belongs to the **watch**, not the app, and does not change between
pairings — so a secret read once can decrypt any later pairing of any app on that watch.
Pass it with --secret when the capture has the nonces but no GETSECRET, which is what a
ring buffer usually leaves you.

Usage:
    python3 tools/btsnoop.py btsnoop_hci.log
    python3 tools/btsnoop.py btsnoop_hci.log --only 9055,a055
    python3 tools/btsnoop.py btsnoop_hci.log --secret d61272b0...
    python3 tools/btsnoop.py btsnoop_hci.log --extract 9064 face.bin

--extract checks and strips the CRC32 every bulk message ends with, and says so loudly
if any message did not carry one: a file rebuilt through an unchecked message comes out
the right length and wrong from that byte onwards, which is not something you notice by
looking at it.

Needs openssl on PATH, and nothing else.
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import re
import struct
import subprocess
import sys
import zlib

# Hard-coded in the watch firmware, the same on every device.
AES_BLOCK = 16

AES_IV = bytes([0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57,
                0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x5A])

CMF_MAGIC = 0xF5
FRAME_HEADER = 11
ATT_CID = 0x0004

# btsnoop timestamps count microseconds from year zero.
EPOCH_OFFSET = 0x00DCDDB30F2F8000

VENDOR = 0xFFFF
PAIR_REQUEST, PAIR_REPLY = 0x8047, 0x0048
NONCE_REPLY = 0x004C

# These four carry no session key: pairing runs before there is one, and the bulk upload
# chunks carry their own framing.
PLAINTEXT = {0x8047, 0x0048, 0x9064, 0x905F}


def packets(blob: bytes):
    """Yields (timestamp, direction, hci packet) from a btsnoop file."""
    if blob[:8] != b"btsnoop\0":
        sys.exit("not a btsnoop file — a bugreport also contains a 'btsnooz' section, "
                 "which holds no packet data at all")

    off = 16
    while off + 24 <= len(blob):
        _, included, flags, _, ts = struct.unpack(">IIIIq", blob[off:off + 24])
        off += 24
        if included > len(blob) - off:
            return
        yield ts, ("RX" if flags & 1 else "TX"), blob[off:off + included]
        off += included


def frames(blob: bytes):
    """Yields (timestamp, direction, cmd1, cmd2, body) for every CMF frame."""
    for ts, _, pkt in packets(blob):
        if len(pkt) < 9 or pkt[0] != 0x02:
            continue
        length, cid = struct.unpack("<HH", pkt[5:9])
        if cid != ATT_CID:
            continue
        att = pkt[9:9 + length]
        if len(att) < 3:
            continue
        if att[0] in (0x52, 0x12):
            direction = "TX"
        elif att[0] in (0x1B, 0x1D):
            direction = "RX"
        else:
            continue

        value = att[3:]
        if len(value) < FRAME_HEADER or value[0] != CMF_MAGIC:
            continue
        _, body_len, cmd1, _, _, cmd2 = struct.unpack(">BHHHHH", value[:FRAME_HEADER])
        yield ts, direction, cmd1, cmd2, value[FRAME_HEADER:FRAME_HEADER + body_len]


def decrypt(key: bytes, data: bytes) -> bytes | None:
    """None for a body AES cannot have produced, rather than a crash.

    A capture holds whatever went over the air, and that includes frames from another
    app's session under another key, truncated writes, and the odd body whose length is
    not a whole number of blocks. One of those must not end the run: the frames worth
    reading are usually the ones after it.
    """
    if not data or len(data) % AES_BLOCK:
        return None
    done = subprocess.run(
        ["openssl", "enc", "-aes-128-cbc", "-d", "-nopad",
         "-K", key.hex(), "-iv", AES_IV.hex()],
        input=data, capture_output=True,
    )
    return done.stdout if done.returncode == 0 else None


def plaintext(raw: bytes) -> bytes | None:
    """Strips PKCS#5 padding and checks the trailing CRC32, or gives up."""
    if not raw:
        return None
    pad = raw[-1]
    if not 1 <= pad <= 16 or len(raw) < pad + 4:
        return None
    if any(byte != pad for byte in raw[-pad:]):
        return None
    body = raw[:-pad]
    payload, crc = body[:-4], body[-4:]
    if struct.unpack("<I", crc)[0] != zlib.crc32(payload) & 0xFFFFFFFF:
        return None
    return payload


def uncrc(body: bytes) -> bytes | None:
    """Drops the CRC32 a bulk-transfer message ends with, or None if it is not one.

    Chunks of a watchface travel unencrypted, but each message still carries a
    little-endian CRC32 of what precedes it. Stripping four bytes without checking them
    is how a reassembled file comes out the right length and wrong from the middle on.
    """
    if len(body) < 5:
        return None
    payload, crc = body[:-4], body[-4:]
    if struct.unpack("<I", crc)[0] != zlib.crc32(payload) & 0xFFFFFFFF:
        return None
    return payload


def app_secret(blob: bytes) -> bytes | None:
    """The watch answers GETSECRET on its shell characteristic, in the clear."""
    found = re.search(rb"GETSECRET:([0-9a-fA-F]{32}),OK", blob)
    return bytes.fromhex(found.group(1).decode()) if found else None


def long_term_key(blob: bytes, secret: bytes | None = None) -> bytes | None:
    """K1, if this capture contains a pairing."""
    secret = secret or app_secret(blob)
    if secret is None:
        return None

    request = reply = None
    for _, _, cmd1, cmd2, body in frames(blob):
        if cmd1 == VENDOR and cmd2 == PAIR_REQUEST:
            request = body
        elif cmd1 == VENDOR and cmd2 == PAIR_REPLY:
            reply = body

    if request is None or reply is None or len(reply) < 48 or len(request) < 16:
        return None

    random2, signature = reply[:16], reply[16:48]
    if signature != hashlib.sha256(random2 + secret).digest():
        return None

    return hashlib.sha256(request[:16] + random2 + secret).digest()[:16]


def clock(ts: int) -> str:
    when = datetime.datetime(1970, 1, 1) + datetime.timedelta(microseconds=ts - EPOCH_OFFSET)
    return when.strftime("%H:%M:%S.%f")[:-3]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("capture")
    parser.add_argument("--only", help="comma-separated cmd2 values in hex, e.g. 9055,a055")
    parser.add_argument("--k1", help="a long-term key in hex, for a capture that holds no "
                                     "pairing to derive one from. Wrong keys are obvious: "
                                     "every frame comes out undecipherable.")
    parser.add_argument("--secret", help="the watch's app secret in hex, when the capture "
                                         "has a pairing but no GETSECRET in it")
    parser.add_argument("--extract", nargs=2, metavar=("CMD2", "FILE"),
                        help="append every body of this command to a file, in order")
    args = parser.parse_args()

    blob = open(args.capture, "rb").read()
    wanted = {int(x, 16) for x in args.only.split(",")} if args.only else None
    extract = int(args.extract[0], 16) if args.extract else None
    extracted = bytearray()
    unverified = 0

    secret = bytes.fromhex(args.secret) if args.secret else None
    k1 = bytes.fromhex(args.k1) if args.k1 else long_term_key(blob, secret)
    if k1 is None:
        print("Could not recover a key from this capture.", file=sys.stderr)
        print("It needs a pairing (ffff/8047 and ffff/0048) and the watch's app secret.",
              file=sys.stderr)
        print("If the pairing is here but GETSECRET is not, pass --secret: the secret is",
              file=sys.stderr)
        print("the watch's own and does not change between pairings.\n", file=sys.stderr)
    elif args.k1:
        print("# using the supplied long-term key\n", file=sys.stderr)
    elif secret is not None:
        print("# key recovered using the supplied secret\n", file=sys.stderr)
    else:
        print(f"# pairing found; long-term key recovered\n", file=sys.stderr)

    key = k1
    for ts, direction, cmd1, cmd2, body in frames(blob):
        if k1 is not None and cmd1 == VENDOR and cmd2 == NONCE_REPLY:
            nonce = plaintext(decrypt(key, body)) if key else None
            if nonce:
                key = hashlib.sha256(nonce + k1).digest()[:16]
                print(f"{clock(ts)} -- session re-keyed")
            continue

        if wanted is not None and cmd2 not in wanted:
            continue

        if cmd2 == extract:
            piece = uncrc(body)
            if piece is None:
                unverified += 1
                piece = body
            extracted += piece

        if not body:
            shown = ""
        elif cmd2 in PLAINTEXT or cmd1 != VENDOR:
            shown = body.hex()
        elif key is None:
            shown = f"[encrypted, {len(body)} B]"
        else:
            raw = decrypt(key, body)
            decrypted = plaintext(raw) if raw is not None else None
            shown = decrypted.hex() if decrypted else f"[undecipherable, {len(body)} B]"

        print(f"{clock(ts)} {direction} {cmd1:04x}/{cmd2:04x}  {shown}")

    if extract is not None:
        open(args.extract[1], "wb").write(extracted)
        print(f"\n# wrote {len(extracted)} bytes to {args.extract[1]}", file=sys.stderr)
        if unverified:
            # Worth stopping for. A file reassembled through even one unverified message
            # is wrong from that byte on, and it will still be the right length and still
            # open as a watchface — which is exactly how a corrupt copy of a known-good
            # face got sent to a watch and read as evidence about the protocol.
            print(f"# WARNING: {unverified} message(s) carried no valid CRC32 and were "
                  "copied whole. The file is not trustworthy.", file=sys.stderr)


if __name__ == "__main__":
    main()
