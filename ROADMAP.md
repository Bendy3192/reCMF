# Roadmap

Where reCMF is, and the order the rest gets built in. Every command named here already
exists in `protocol/CmfCommand.kt` — the protocol is ported, so what remains is payloads
and UI, not reverse engineering.

The ordering rule is: **the things that make the stock app unnecessary come before the
things that make reCMF impressive.** Settings are first not because they are interesting
but because without them a watch cannot be configured at all once Nothing X is uninstalled.

## Done

- Connection, pairing and the session-key handshake — **verified against a real Watch Pro 2**
  (firmware 1.0.0.73).
- Activity and battery readout, foreground service, reconnect, Health Connect writing.
- Phone notifications, with screen-off-only delivery.
- Automatic refresh on a chosen interval, and a background refresh that survives Doze.
- An in-app protocol log. Keep using it: every phase below is verified by reading it.

Verified on hardware: pairing, activity and heart rate, continuous heart-rate monitoring —
the setting that turned out to be why heart rate never arrived — and the weather push.

## Phase 1 — Watch settings *(done, read-back mostly done)*

The blocker. All of it is one command each, and most are two or three bytes.

Built: continuous heart rate, all-day SpO₂, stress monitoring, raise-to-wake, clock
format, units, daily goals, the alert thresholds, the stand and drink reminders with their
quiet hours, and the visible sport list.

Dropped rather than left open: **do not disturb** has an opcode but no known payload, and
Gadgetbridge does not implement it either — inventing bytes to send a watch is not a guess
worth making. **Watch language** is sent as a locale string that Gadgetbridge records the
watch as ignoring, and a control that does nothing is worse than no control. Both can be
set on the watch itself.

| Setting | Command | Payload |
|---|---|---|
| 24/7 heart rate | `HEART_MONITORING_ENABLED_SET` | `01 <on>` |
| All-day SpO₂ | `HEART_MONITORING_ENABLED_SET` | `02 <on>` |
| Stress monitoring | `HEART_MONITORING_ENABLED_SET` | `04 <on>` |
| Heart-rate and SpO₂ alert thresholds | `HEART_MONITORING_ALERTS` | resting/active high, low, SpO₂ low |
| Daily goals | `GOALS_SET` | steps, distance, calories, big-endian |
| Raise-to-wake | `WAKE_ON_WRIST_RAISE` | `<on>` |
| 12/24-hour clock | `TIME_FORMAT` | one byte |
| Metric / imperial | `UNIT_LENGTH`, `UNIT_TEMPERATURE` | one byte each |
| Watch language | `LANGUAGE_SET` | locale string |
| Stand reminder | `STANDING_REMINDER_SET` | interval, quiet hours |
| Drink reminder | `WATER_REMINDER_SET` | interval, quiet hours |
| Do not disturb | `DO_NOT_DISTURB` | window |
| Visible sport types | `SPORTS_SET` | ordered list |

**Read-back works, and the rule for it is the same everywhere.** Send the `0x0002` half of
a pair and the watch answers under the matching `0x0001`. That held for every setting it
was tried on — serial number, alarms, both reminders, raise-to-wake, clock format, the
sport list, Do Not Disturb, goals and the battery — so it is the rule here, not a trick
that happens to work on one command.

What comes back is *reported* always and *taken* only when the user has not configured
that group themselves. Their choice outranks the watch's current state, and a read-back
that overwrote it would be the overwriting it exists to prevent. The log says which of the
two happened, because "taken" and "already yours" look identical on the settings screen
afterwards.

Two exceptions:

- **Goals are read, not written.** Four of the six fields were confirmed against the
  watch's own screen: 10000 steps, 400 calories, 30 active minutes and — the byte after
  the numbers — 12 climbs. The fourth number, 720, is still unnamed.

  Writing them does not work on this firmware, in either shape tried — and goals are the
  only setting that behaves this way. Every other write was confirmed to land by reading
  it back: both reminders came back exactly as sent, byte for byte, including their quiet
  hours. The ten big-endian
  bytes ported from Gadgetbridge were acknowledged and ignored; so was the watch's own
  twenty-eight byte block with three fields patched in place. Both times the very next
  read reported the values the watch already held. `applied` on this command means the
  frame arrived and nothing more.

  So the app shows the goals and says where to change them. An editable field that
  silently does nothing is worse than a number with an explanation. What would settle it
  is a capture of the official app setting a goal; short of that, anything else is
  guessing at an encoding, and the wearer pays for a wrong guess with their own targets.

- **Do Not Disturb** is read and reported only. reCMF does not write it, so there is no
  preference for it to disagree with.

## Phase 2 — Weather *(done)*

Moved forward from the transfers phase, because it does not belong there: `WEATHER_SET_1`
is a single command with a fixed 199-byte payload, not a chunked upload. The payload
builder is written and tested — today plus six days, twenty-four hours, a place name and a
week of sun times.

The source is Open-Meteo, chosen on two rules that held:

- **A place name, not a location permission.** The user types a city, it is resolved once
  to coordinates, and those are stored. No GPS, no background location, and the app keeps
  the `neverForLocation` flag it declares on Bluetooth scanning.
- **A provider with no account and no key**, queried for one city at a time.

Two intervals, deliberately separate: the provider is asked at most every thirty minutes,
and the watch is handed whatever snapshot we hold on every refresh and every connect. They
used to be one, which meant reconnecting inside that half hour sent the watch nothing.

Gadgetbridge takes weather from a companion app over a broadcast instead, and reCMF could
register the same receiver to accept BreezyWeather's. Not built: it is a second source for
a problem the first one solves, and the failure it was proposed to work around turned out
to be scheduling, not parsing.

## Phase 3 — The rest of the health data *(mostly done)*

The data was arriving all along. `ACTIVITY_FETCH_2` means "everything you recorded", and
every one of these came with it — reCMF had the opcodes but no branch for them, so they
fell through `else -> Unit` and, because the opcode *was* known, never even appeared in
the log as unknown.

Done and confirmed against a real watch, byte for byte:

- **SpO₂** and **stress** — 8-byte records, `timestamp:int` then `value:int`. SpO₂ reaches
  Health Connect; stress stays on screen, because Health Connect has no record type for it.
- **Resting heart rate** — 5 bytes, not 8: a timestamp and a *single byte* of bpm. Not
  ported, because Gadgetbridge leaves this payload as a TODO. Read out of a capture.

**Sleep works, and the window theory was wrong.**

It arrives in the `ACTIVITY_FETCH_2` stream, as Gadgetbridge always said it would — pushed
once, about twenty minutes after the wearer gets up, not fetched. `SLEEP_DATA_GET` exists
and answers, always with nothing; it is not the road to a night and never was.

The theory that a five-minute fetch window was too narrow to contain a session is
**false**, and a capture killed it: the fetch that carried the night opened with *"activity
since 05:25"* and the night had begun at 22:06 the evening before. What had actually been
happening was far duller — the protocol log held two hundred entries, about two hours on a
phone syncing every five minutes, so a frame that arrived at half past five was gone before
anyone looked at it. Raising the log to six hundred is what made it visible.

The parse is confirmed, and not by eye. A session read 22:06 → 05:09, which is 25380
seconds; its thirty-three stage durations, parsed independently, summed to 25380. Thirty
three numbers do not agree with a separate total to the second by accident. That also
settles the unit Gadgetbridge never documents: **seconds**. The night broke down as 4h01
light, 1h59 deep, 1h03 REM.

Nights now go to Health Connect as a `SleepSessionRecord` with its stages, keyed on when
the night began so a re-delivery replaces rather than stacks. It needs the sleep
permission, which is new, so Health Connect will ask once more.

**The stage codes are 1 deep, 2 light, 3 REM**, and that was in doubt for a while. A
Fitbit Air, read through Google Health, put REM above deep on a night the CMF was also on
the wrist — the opposite of what reCMF reported, and exactly what a swapped mapping would
look like. Nothing X, the CMF's own app, settled it. reCMF and Nothing X have not yet
covered the same night, so their comparison is of shares rather than minutes:

| | deep | light | REM |
| --- | --- | --- | --- |
| reCMF, 7h03 night | 28% | 57% | 15% |
| Nothing X, 8h01 night | 31% | 57% | 13% |
| Fitbit Air, the same night as the Nothing X row | 22% | 58% | 21% |

reCMF and the vendor's own app agree on the light share to the point and order deep well
above REM the same way. The odd row out is a *different device*: the Fitbit sat on the
same night as the CMF and the two agree closely on what matters least here — the night
ran 22:18-06:17 against 22:17-06:18, and light came out 4h33 against 4h32 — then split
the rest differently, which is the ordinary disagreement between two vendors' staging
algorithms, and also where the Fitbit's six waking and twelve restless minutes come from.
It was never evidence about the CMF's codes. The mapping ported from Gadgetbridge is
right.

**Workouts and their GPS tracks** are the one thing left here, and the one place where a
captured log of real bytes is needed rather than a precaution.

## Phase 4 — Alarms, contacts, find *(alarms and find done)*

**Alarms** read and write. `ALARMS_GET` answers under the `ALARMS_SET` opcode, and an
empty reply means no alarms rather than a failed read — confirmed against a watch with
none set. The list is adopted on read but not marked as configured, so a connection never
writes back what it just read. Labels are not modelled: the watch does not display them
and they cannot be read back.

**Find, both ways.** `FIND_WATCH` makes the watch ring; `FIND_PHONE` arrives *from* the
watch and now rings the phone — the first thing here that is a feature of the phone rather
than of the watch, and it shows: the tone plays on the alarm stream, because the phone
being on silent is the case the feature exists for. The volume is left alone and Do Not
Disturb is left to refuse; a find-phone that overrides both is a fright waiting for a
mis-press. It stops after thirty seconds, on the notification, or when the watch says so.

The payload was a guess — one leading byte, zero for off — because nothing documents this
one and Gadgetbridge never sends it. A capture settled it: `01` on the press, `00` when the
wearer ends the search from the watch. The guess was right, and it is now a reading.

**Contacts** are untouched: `CONTACTS_GET` is in the table, Gadgetbridge never calls it,
and the reply layout is unknown.

## Phase 5 — Calls and music *(music done)*

**Music is done.** `MUSIC_INFO_SET` carries the state, the volume and its maximum, then
the track and artist in 64 bytes each; `MUSIC_BUTTON` comes back as two little-endian
bytes — an action and a direction, which read as one number in Gadgetbridge and hide that
structure.

It needed `MediaSessionManager`, which is gated on notification-listener access, so it
cost no new permission. Changes are pushed on the callback rather than polled: a track
lasts minutes and the refresh timer is five.

**Volume** goes to whichever thing is actually making the sound: a session casting to a
speaker carries its own level and ignores the phone's music stream, so it is moved and
read through the session; anything else moves the stream. Android has no callback for the
local stream below API 34 — and the one it gained there is for system apps — so a level
changed on the phone is noticed by watching `Settings.System`, where the audio service
writes it under a per-route key. Do Not Disturb refuses volume changes to apps without
notification-policy access, which reCMF does not ask for; the press is dropped rather than
taking the service down with it.

**Calls are the worse-understood half.** `CALL_REMINDER` has an opcode and nothing else;
Gadgetbridge's handler for it is an empty override. Meanwhile the *useful* part is already
done without it: an incoming call reaches the watch as a notification with the caller's
name, because Android has resolved the number against the address book before the dialer
posts it. A call screen with answer and reject buttons is what remains, and it needs the
payload discovered from scratch.

## Phase 6 — Transfers

**Watchface install works.** The whole sequence — `8052`, `9075` with its four decrypted
fields, the chunks the watch asks for by offset, `a065` to close — runs against a real Watch
Pro 2. Confirmed on two files of different origin: one read byte for byte out of a capture of
the official app, and one downloaded from a watchface site and repaired here. Both are on the
watch and both draw.

**Two faults were in the way at once, and each hid the other.**

The first is the id. `9075` names the face being replaced *and* the id the new one gets, and
**the second must be a number the watch is not already holding**. Reusing the displaced
face's own id is refused with `0a` even when the file is one the watch has accepted before;
an unused id with the same file is accepted. So the watch is not overwriting a slot so much
as adding a face and dropping the one named as replaced, and the two cannot be the same
number. reCMF sends one above everything on the watch.

The second is the file. A face downloaded from a watchface site is a device file with four
bytes stuck on the end and two stale lengths in its header — see the layout below. Sent as
it came it is refused with the same `0a`, which is why the first attempt at an unused id
proved nothing: it carried a broken file.

Both had to be right at once, and the order in which they were tried meant every single
attempt failed until the last. What made the last one possible was a control run that was
finally trustworthy, and that took its own detour:

**A control run was set up and then wasted by a bad file.** The idea was sound: send back the
bytes the watch had already accepted, rebuilt from the capture of the official app installing
them. The face came back refused, which read as proof that the protocol was at fault.

It was not. The reassembly had copied one message without stripping its CRC32, so the file
was the right length, opened as a watchface, named itself correctly — and was wrong from byte
224 onwards. `tools/btsnoop.py --extract` now checks every message's CRC32 before stripping it
and says loudly when one does not verify. The correctly rebuilt file differs from the one that
was sent in 65403 of its 76104 bytes.

**The official install is decrypted end to end**, using a long-term key carried over from
another capture of the same app — `tools/btsnoop.py --k1` takes one, for a capture that holds
no pairing of its own. Laid against reCMF's own log the two are the same frames in the same
order with the same payloads, down to the watch asking for the file in identical steps:

```
TX 8052  a5                                RX 0052  01
TX 9075  03 6e010000 43010000 48290100     RX a075  01
RX a064  0000000000000c0000  ... twenty-five asks, identical offsets, lengths and percents
RX a065  01
```

That comparison is what narrowed the whole question down to `9075`'s pair of ids.

**What the correct rebuild showed is the layout of the whole file:**

```
header 36 | name block | element table | resources | header again, 36
```

A file **ends with its own first 36 bytes repeated**. The length at offset 24 is everything
before that closing copy and the length at offset 28 is the resources alone, so their
difference is the element table — and the first element's offset is that same number. On the
accepted file all of it agrees exactly: 76068 and 74796 in a file of 76104, closing copy at
76068, first element at 1272.

**The downloaded face was a bad rebuild of a capture, and now that is provable.** It ran
1044 bytes over what its header accounts for, and those bytes were not on the end: they were
**the CRC32 that every message of a Bluetooth transfer ends with**, left in when somebody
reassembled the file out of a capture. 261 messages, four bytes each. Pulled back out, the
file is 57173 bytes, its closing copy lands exactly where offset 24 says, and its element
table chains from the first resource to the last without a gap — the same checks the accepted
file passes.

That is the same mistake reCMF made rebuilding a file of its own, which is the only reason it
was recognisable at a glance. The repaired file installs and draws, which is what turns the
reading from an argument into a fact.

The message lengths are not fixed, so the repair does not model them: it extends a CRC32 a
byte at a time and watches for the four bytes that follow to be it, taking the longest length
that matches. Run lengths of 224, 160 and 85 came out of the real file, which is exactly the
watch asking for 3072-byte stretches that do not divide by 224. The repaired bytes are only
used if the file then accounts for itself exactly; otherwise the original is sent unchanged.

An earlier version of this cut the file at its closing copy and rewrote the two lengths. It
happened to produce the right *length* and the wrong bytes, because the surplus was spread
through the file rather than sitting at the end.

**The mode byte** is always `03`. `02` is reported elsewhere as "add rather than replace",
but this watch holds six slots and no seventh, and an untried mode is not worth sending to a
device that has to be re-paired when it sulks.

**Worth telling Gadgetbridge.** Its issue #4581 is this exact wall, and three things it has
wrong are now known: the opcode is `9075` and not `9063`, the fields are little-endian, and
the one it fills with `new Random().nextInt()` under `FIXME watchface ID?` is the id of the
face being *replaced* — with the new face's id in the field beside it, which must be unused.

### What is inside a watchface

Sending somebody else's file is a stopgap. The point of knowing the format is to build one,
which is also the only version of this with no copyright question in it at all. Two files
were enough to take the container apart; what follows was read out of both and holds in both.

```
header      36 bytes   4 unexplained | version u32 | name in 12 | 0a | content u32 | resources u32 | 3 words
name block  84 bytes   the name again at offset 45, then zeroes
elements    variable   the table below, running until the resource area
resources   the rest   images, concatenated, no separators
```

`content` at offset 24 is everything after the header. `resources` at offset 28 is the image
blob alone, so the element table is the difference between them — 1272 bytes in one file, 923
in the other, and in both the first element points at exactly that offset.

An element is:

```
61 | count:u8 | 00 | start:u32 | count × size:u16 | placement
```

`count` is how many pictures the element cycles through, and it reads as plainly as anything
in this protocol: **1** is a static image, **10** a digit place, **7** the days of the week,
**2** a two-state icon. `start` is where its first picture begins and the sizes are the
pictures' byte lengths in order — they sum to exactly the gap to the next element's `start`,
in every element of both files, which is what confirms the whole reading.

`placement` is 24 bytes for a static element and longer for the rest. Recurring shapes like
`30 1e 00 01 1b 00` sit where coordinates would.

**An image has an eight-byte header, and it is now read.**

```
tag:u8 | dimensions:u24 | length:u32 | compressed data
```

`length` is the bytes after the header — it agrees with the element table's own size for
that image, minus eight, on every image in both files. `tag` is `04` or `05`; what it selects
is not yet known.

The dimensions took a detour worth recording. Read as twelve bits and twelve bits they give
numbers like 1864 by 932, which is nonsense on a 466-pixel screen — until you notice that
1864 is four times 466 and 932 is twice it. **The low field is the width times four and the
high field is the height times two.** Across 113 images in the two files, every low field
divides by four and every high field by two, without a single exception; chance would put
that at one in 8^113.

What comes out is a face's worth of sensible pictures. The largest image in *both* files is
exactly 466 by 466 — the screen. Others land on 270 by 270, 136 by 136, 50 by 50: square,
which is what a dial ring or a round icon is. And the compression ratio, which read as an
absurd 200 pixels per byte before, now sits between 1.6 and 28.6 across every distinct size
in both files, which is the range an RLE over small images actually lives in.

**The compressed data itself is what remains.** It is not PNG, JPEG, WebP, GIF, BMP, gzip or
zlib — no magic for any of them appears anywhere. Three images in one file are the same
picture at three heights (10 by 28, 10 by 44, 10 by 140) and differ *only* in the length of a
run of `ff` bytes — two, four and fifteen of them — which says runs are in there and that
`ff` is how they are spelled. Every image's data opens with `1f 00 01 00`.

The best cribs are the digit sets. A ten-image element is the digits nought to nine in order,
and it reads like it: in one set the smallest image by far is index 1 and the second smallest
is index 7, which is exactly the order of how much ink a digit takes. Ten pictures whose
content is known in advance is as good a starting point as a codec ever offers.

The feedback loop for cracking it is slow but real: build a file, send it, look at the watch.
A minute an attempt, and the answer is on the screen.


Watchfaces (`DATA_TRANSFER_WATCHFACE_*`), A-GPS (`DATA_TRANSFER_AGPS_*`) and firmware. These use the second GATT service and a
chunked upload protocol, and firmware flashing is the one operation here that can brick a
watch — so it comes last and stays behind a warning.

**The upload sequence is already known**, read out of Gadgetbridge's `CmfDataUploader`
(GitHub mirror, December 2024 — its live development is on Codeberg and is further along
than this):

1. `DATA_TRANSFER_WATCHFACE_INIT_1_REQUEST` (`ffff/8052`) carrying one byte, `a5`.
2. The watch replies `ffff/0052` with `01`.
3. `DATA_TRANSFER_WATCHFACE_INIT_2_REQUEST` (`ffff/9063`), nine **big-endian** bytes:
   `a5`, the file length as u32, and a u32 watchface id.
4. The watch replies `ffff/a063` with `01`.
5. The watch then asks for the file in pieces: `ffff/a064` carries offset u32, length u32
   and a progress byte, and each piece goes back as `ffff/9064` — unencrypted, like the
   A-GPS chunks.
6. `ffff/a065` closes it and is answered with `ffff/9065`.

The file itself starts `01 00 00 02`, carries a null-terminated name at offset 8, and
repeats that name 28 bytes from the end — Gadgetbridge checks both and refuses the file if
they disagree. Reports elsewhere say faces built for the **Pro 2** start `01 00 00 00`
instead, which that December snapshot does not know about.

**The official app does not send `9063` at all.** A capture of Nothing X installing a
face — HCI snoop log, no root — shows the sequence as:

```
TX ffff/8052   16 B   init 1
RX ffff/0052   16 B
TX ffff/9075   32 B   init 2, and this is NOT 9063
RX ffff/a075   16 B
TX ffff/9064   x261   the file, unencrypted, in 19 messages the watch asks for by offset
RX ffff/a064   x19    each request encrypted
RX ffff/a065   16 B   finished
```

**And `9075` has been decrypted.** Thirteen little-endian bytes:

```
mode:u8 | replacedId:u32 | newId:u32 | size:u32
```

One capture reads `03 6e010000 43010000 48290100` — mode 3, replacing id 366, installing
id 323, 76104 bytes. Every field checks out independently: 366 was what the list held in
that slot, 323 is what the list held afterwards, and the watch's own chunk requests run to
offset 76104 exactly.

So Gadgetbridge's issue #4581 has three causes at once, and the id was never the hard part.
It sends the wrong opcode (`9063`), in the wrong byte order (big-endian), with a field it
fills using `new Random().nextInt()` under a comment reading `FIXME watchface ID?` — when
that field is not a random identifier at all. **It names the face being replaced.** The
watch holds a fixed six, so an install is always a replacement, and the frame has to say
which one goes.

**The watch's app secret does not change between pairings.** It belongs to the watch, not
to the app that asks for it, so a secret read once out of a `GETSECRET` reply decrypts any
later pairing — by any app — on that watch. That is what turned a capture with the nonces
but no `GETSECRET` in it, which is what a ring buffer usually leaves you, into a readable
one. `tools/btsnoop.py --secret` takes it.

**A capture that contains a pairing can be read in full**, which is what `tools/btsnoop.py`
does. Nothing is broken to do it: the watch hands its app secret over the shell
characteristic in plain text, both nonces travel unencrypted, and every key after that is a
SHA-256 of material already on the wire — `K1 = SHA-256(nonce1 || random2 || secret)[:16]`,
then `SHA-256(nonce || K1)[:16]` re-derived on each reconnect. Confirmed by reading reCMF's
own `a055` frames out of a capture and getting the list byte for byte.

**Each app that pairs negotiates its own `K1`**, so a capture of the official app needs
that app's own pairing in it — but with the watch's secret in hand, that pairing is enough
on its own.

**The file itself came out of that capture**, because the chunks are not encrypted. 58217
bytes, and its first sixteen read:

```
d3 87 9f b9 | 01 00 00 00 | "Combo\0" ...
```

`01 00 00 00` at offset 4 is the Pro 2 version marker, against the `01 00 00 02`
Gadgetbridge checks for at offset 0 — a second reason it refuses these files. The name sits
at offset 8 as Gadgetbridge expects, but is *not* repeated 28 bytes from the end, which is
a third. The first four bytes are not a CRC32 of anything else in the file.

**Step 3 is where it stalls.** Gadgetbridge writes `new Random().nextInt()` there, under a
comment reading `FIXME watchface ID?` — nobody knew what the field wanted. Which is
interesting, because `ffff/a055` — the frame this watch answers `WATCHFACE_GET` with, and
which appears nowhere in Gadgetbridge's source — is six 32-bit numbers: 273, 274, 275,
276, 277, 280.
Six ids, in the field width that step 3 wants. It is now confirmed as the watch's reply to
`WATCHFACE_GET`, which makes it a list of watchfaces rather than a list of anything else —
so the id that step 3 wants being one of these six is a good deal more than a guess,
though still short of a test.

Getting a face file to try needs the official app's copy of one: they land in
`/data/data/com.nothing.smartcenter/app_flutter/dial/market/dial_file/` as
`watchface_<hash>.bin`, which is a private directory and needs root to reach.

**Somebody has already put third-party faces on this watch, and not over BLE.** An
r/CMFTech post from five months ago (u/WaltzExisting, "Custom Watchfaces for Watch Pro 2")
reports finding the ODM behind the watch, which ships its own companion app with its own
catalogue of faces, and sideloading those onto a Pro 2 — most work, some crash. How it was
done is the part worth reading: *"gadgetbridge upload didn't work for me so I just wrote a
small mitmproxy script that would serve the other app's watchface bin instead of nothing's
when downloading via the CMF Watch app."* Nothing X is out because of certificate pinning;
the older CMF Watch app is not.

So the BLE upload is still unsolved in public — it was **bypassed**, not fixed. The
official app did the transfer over its own working protocol and only the bytes it fetched
were swapped. Which leaves the id field at step 3 exactly where Gadgetbridge left it, and
leaves `ffff/a055` still the only list of ids either project has.

That also points at the cheapest way to finish this, and the certificate pinning that
stopped the mitmproxy trick does not stand in its way. Pinning is about the network; the
transfer is Bluetooth. **Install a face from Nothing X's own catalogue with the Bluetooth
HCI snoop log running, then pull `btsnoop_hci.log` out of `adb bugreport`.** The
`ffff/9063` frame carries the length and the id the app really sent, and the `ffff/9064`
chunks after it are the file itself — the id question and the file format from one
capture, with no root and no proxy. The older CMF Watch app is not needed and appears to
be discontinued anyway.

**Switching between the faces already on the watch is a separate and much smaller
question**, and it is not in this phase. It needs no file transfer at all.

`WATCHFACE_GET` (`009f/0002`) is answered — but not under `009f/0001`, which is what the
read-back rule would predict and which this watch has never sent. It is answered by
`ffff/a055`, and that identification is the one thing here that is settled rather than
guessed. The frame had been arriving unattributed for months. The request was added at the
end of the read-back batch, where an unattributed frame lands anyway, and a055 followed it
— suggestive, worthless as proof. So the request was moved to the *front* of the batch,
and a055 moved with it, arriving ahead of the heart-rate, reminder, goal, clock, sports,
Do Not Disturb and alarm replies that all used to precede it. It follows the request
because it answers the request.

**The watch also sends this list unprompted when the face is changed on the wrist.** One
arrived at 12:57:27 with no request ahead of it, reporting the second face where the
previous read had reported the first — the wearer had just changed it by hand. So the list
is both a reply and a notification, and a picker built on it stays correct without polling.

Its content is a four-byte header, `01 05 06 07`, then six little-endian 32-bit ids: 274,
273, 275, 276, 277, 280. The header's third byte is 6, which is how many ids follow, and
that agreement is the only structural check the frame offers — reCMF refuses a frame where
the two disagree rather than reading invented numbers out of it.

**The second header byte reads as the active face.** The watch was showing the sixth of
its six, and that byte is 5 — the position of the sixth when counting from zero. reCMF
offers that reading, and only when the byte lands inside the list: a value that cannot be
a position is evidence it was never a position, so the reading is withdrawn rather than
clamped to something plausible.

One capture cannot tell an index from any other number that happens to equal five, so this
is a prediction rather than a fact, and it is a falsifiable one: **select the first face on
the watch and the byte should read `00`.** Until that is done the log prints the raw header
beside the reading of it, so the moment the two stop agreeing is visible.

`07` is unexplained and is not an index into six entries. `01` is unexplained.

**`ffff/9055` is how the official app asks for the list**, and reads as a request rather
than a selection: identical ciphertext both times it was sent, sixteen bytes, which is what
a bare `a5` marker plus its CRC comes to. It is the vendor twin of `009f/0002`, not the
write half. **The capture does not contain a face being selected** — only one being
installed — so the select command is still unfound, and the new face becoming active is
explained by the install rather than by any command.

**Selecting a face: `ffff/9055`, carrying the whole list.** A capture of the official app
switching between installed faces settled it, and the frame is the same one that reads the
list — what it carries decides which it is:

```
TX ffff/9055  00                              → a request; a055 answers with the list
TX ffff/9055  01 03 06 07 <the six ids>       → a selection: active byte moved to 3
RX ffff/a055  00                              → accepted
RX ffff/a055  01 03 06 07 <the six ids>       → and the list comes back with it active
```

The app names no face. It returns the twenty-eight plaintext bytes it was given with the
active byte pointing somewhere else, and the watch echoes exactly those bytes. Five
attempts at `009f/0001` — an index byte, a big-endian id, a big-endian index, a
count-then-index, a two-byte id — were acknowledged and ignored, because the watch does
not take an argument here at all. It takes its list.

reCMF sends everything except the active byte back untouched, including the two header
bytes nobody has explained: the one thing known about this frame is that the watch accepts
its own list, and a byte improved on the way out is a byte no capture supports.

**Confirmed on hardware.** The picker switches faces on a real Watch Pro 2, firmware
1.0.0.73.

**Installing a face replaces a slot rather than adding one.** Before: 274, **273**, 275,
276, 277, 280. After installing "Combo": 274, **366**, 275, 276, 277, 280 — still six, with
the new id in the second slot, and the active byte pointing at it. So the watch holds a
fixed six and the new face becoming active is a consequence of the install.

What is still missing is a capture of a face being **selected** in the official app rather
than installed. That is one unfamiliar frame, and it ends this.

## Known and fixed, worth not repeating

**Steps landing on the wrong day.** The watch counts cumulatively and zeroes at local
midnight, so reCMF stores the difference between readings. The interval for the first
reading after a reset used to start at the *previous* reading — which is the night
before — and Health Connect splits a record across the hours it spans, so a morning's
steps were divided between two days and a stretch of sleep. A day showing 1421 of the
watch's 3517 is what that looks like. The interval now starts at the reset, and at the
previous reading when the counter dropped for some other reason, because a reboot at two
in the afternoon does not mean the morning is up for grabs.

**The first reading after a fresh install used to be dropped**, and with it every step
since midnight. Its total covers a period an earlier install may already have written, and
Health Connect keeps those records across a reinstall even though the staging table does
not — so counting it would have doubled them.

Health Connect is asked instead, since Health Connect is the thing that would be
double-counted. The sum of reCMF's own records for that day, ending where the last one
ends, *is* a cumulative reading, so it slots in as a baseline with no special case
anywhere else. Only reCMF's records are counted: the phone counts steps too, and adding
those in would subtract them from what the watch is owed.

One case does need telling apart. A counter below its own last value has been reset; a
counter below what is already *recorded* means the recording is ahead of the watch, and
writing the difference there would count those steps twice. So the baseline knows which
of the two it is, and only the first reading of a batch is measured against a recorded
total — every reading after it is a counter against a counter.

## Unidentified

Bytes seen on a real watch that nothing here explains yet. Written down so the next capture
can be compared against them rather than starting over.

- **`ffff/0051`, one byte.** Reads `0x38` in every capture of it, once per connection.
  The guess was that it was the watch refusing the pointless `BATTERY` request reCMF used
  to send; that request is gone and the frame still arrives, so the guess was wrong. It is
  unsolicited, always the same value, and 56 matches nothing on the watch's screen.
- **The reply to `HEART_MONITORING_ENABLED_GET`, 8 bytes.** High-entropy and *different on
  every connection*, which rules out the obvious reading — it is not the monitoring state.
- **The last 12 bytes of every activity record.** The tail used to be sixteen. Its first
  four bytes are a little-endian number that tracks the day — `09` in the morning, `0b`
  by evening, `01` after midnight — so it counts something that accumulates and resets
  with the date. The goal block counts climbs beside steps and calories, in a number of
  the same size, and the app now reads that field as climbs on that basis and nothing
  stronger. The remaining twelve bytes have been zero in every capture.

## Not planned

Anything that would require an account, a cloud service, or telemetry. reCMF talks to the
watch and to Health Connect, and to nothing else.
