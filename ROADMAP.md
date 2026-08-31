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

Watchfaces (`DATA_TRANSFER_WATCHFACE_*`), A-GPS (`DATA_TRANSFER_AGPS_*`) and firmware. These use the second GATT service and a
chunked upload protocol, and firmware flashing is the one operation here that can brick a
watch — so it comes last and stays behind a warning.

**Switching between the faces already on the watch is a separate and much smaller
question**, and it is not in this phase. It needs one opcode, `WATCHFACE` (`009f/0001`),
and no file transfer at all. reCMF now sends `009f/0002` on every connection and logs
whatever comes back, because the read-back rule — a `<cmd1>/0x0002` GET answered under
`<cmd1>/0x0001` — has held for the serial number, both reminders, raise-to-wake, the clock
format, sports, Do Not Disturb, the goals and the alarms. If it holds here, the reply says
which face is active and probably how many exist, and a picker follows from that. If
nothing comes back, this firmware does not serve the opcode and there is nothing to build
a picker on. Either way the log answers it, and the frame costs one write per connection.

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
- **`ffff/a055`, 28 bytes.** `01 05 06 07` and then five-byte groups: 274, 273, 275, 276,
  277, 280. A stable list of something the watch supports; the numbers do not move between
  connections.
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
