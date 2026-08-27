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

## Phase 3 — The rest of the health data *(next)*

Steps and heart rate reach Health Connect today. Gadgetbridge parses the rest and reCMF
does not yet: `SLEEP_DATA` with its stages, `SPO2`, `STRESS`, `HEART_RATE_RESTING`,
`WORKOUT_SUMMARY` and `WORKOUT_GPS`.

These almost certainly arrive already. `ACTIVITY_FETCH_2` means "everything you recorded",
and Gadgetbridge parses all of the above from that same fetch. reCMF has the opcodes in its
table but no branch for them in `onMessage`, so they fall through to `else -> Unit` — and
because the opcode *is* known, they do not even appear in the log as unknown. The first
step is therefore to log a known command that nothing handles, which costs nothing and
settles whether the data is there.

Order, easiest and most certain first:

1. **SpO₂** and **stress** — records of 8 bytes, little-endian, `timestamp:int` then
   `value:int`. The size is self-checking and the layout is unit-testable without a watch.
2. **Sleep** — a session header (`start:int`, `wakeup:int`, 10 metadata bytes nobody has
   identified) followed by stages of 8 bytes (`timestamp:int`, `duration:short`,
   `stage:short`). Worth doing early: it is the reason most people open a watch app in the
   morning, and Gadgetbridge's own Health Connect export does not cover it.
3. **Resting heart rate**.
4. **Workouts and their GPS tracks** — last, and the one place where a captured
   Gadgetbridge log of real bytes is genuinely needed rather than a precaution.

Health Connect has record types for sleep, SpO₂ and resting heart rate, so all three go
there as well as into the app.

**The two tabs land here**, not after: Health for the metrics, Device for connection,
watch settings, weather and the log. Splitting earlier would mean splitting one card away
from six; splitting once sleep and SpO₂ arrive is what makes the single scroll stop
working.

## Phase 4 — Alarms, contacts, find

`ALARMS_SET` / `ALARMS_GET`, `CONTACTS_SET` / `CONTACTS_GET`, `FIND_WATCH`, `FIND_PHONE`.
`FIND_PHONE` arrives *from* the watch and needs the phone to ring — the first thing here
that is a feature of the phone rather than of the watch.

## Phase 5 — Calls and music

`CALL_REMINDER` for incoming calls, and `MUSIC_INFO_SET` / `MUSIC_BUTTON` /
`MUSIC_INFO_ACK` for now-playing and wrist controls. Needs a media session listener, which
is a chunk of Android work rather than protocol work.

## Phase 6 — Transfers

Watchfaces (`DATA_TRANSFER_WATCHFACE_*`), A-GPS (`DATA_TRANSFER_AGPS_*`) and firmware. These use the second GATT service and a
chunked upload protocol, and firmware flashing is the one operation here that can brick a
watch — so it comes last and stays behind a warning.

## Not planned

Anything that would require an account, a cloud service, or telemetry. reCMF talks to the
watch and to Health Connect, and to nothing else.
