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

## Phase 1 — Watch settings *(done, except read-back)*

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

**Still open: read-back.** `*_GET` exists for some of these, and reCMF uses none of it, so
the app cannot show what the watch actually holds — only what the phone last sent. That
gap is why settings are now sent only once the user has touched them: an app that has
never read a setting has no business overwriting it. Worth closing, because a setting that
silently failed to apply is worse than one that is missing.

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

**Sleep** is written but unverified: a session header (`start:int`, `wakeup:int`, 10
unidentified bytes) then 8-byte stages. The stage duration's unit is not confirmed, so
nothing is stored — the log states reCMF's reading in clock times and stage letters, which
someone who slept through the night can check at a glance. Storage follows confirmation.

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

The payload is a guess — one leading byte, zero for off — because nothing documents this
one and Gadgetbridge never sends it. The bytes are in the protocol log next to the guess.

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

## Not planned

Anything that would require an account, a cloud service, or telemetry. reCMF talks to the
watch and to Health Connect, and to nothing else.
