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
- An in-app protocol log. Keep using it: every phase below is verified by reading it.

## Phase 1 — Watch settings

The blocker. All of it is one command each, and most are two or three bytes.

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

Read-back exists for some (`*_GET`), so the UI can show what the watch actually holds
rather than what the phone last sent — worth doing, because a setting that silently failed
to apply is worse than one that is missing.

## Phase 2 — The rest of the health data

Steps and heart rate reach Health Connect today. Gadgetbridge parses the rest and reCMF
does not yet: `SLEEP_DATA` with its stages, `SPO2`, `STRESS`, `HEART_RATE_RESTING`,
`WORKOUT_SUMMARY` and `WORKOUT_GPS`.

Sleep is the one worth doing first: it is the reason most people open a watch app in the
morning, and Gadgetbridge's own Health Connect export does not cover it.

## Phase 3 — Alarms, contacts, find

`ALARMS_SET` / `ALARMS_GET`, `CONTACTS_SET` / `CONTACTS_GET`, `FIND_WATCH`, `FIND_PHONE`.
`FIND_PHONE` arrives *from* the watch and needs the phone to ring — the first thing here
that is a feature of the phone rather than of the watch.

## Phase 4 — Calls and music

`CALL_REMINDER` for incoming calls, and `MUSIC_INFO_SET` / `MUSIC_BUTTON` /
`MUSIC_INFO_ACK` for now-playing and wrist controls. Needs a media session listener, which
is a chunk of Android work rather than protocol work.

## Phase 5 — Transfers

Watchfaces (`DATA_TRANSFER_WATCHFACE_*`), A-GPS (`DATA_TRANSFER_AGPS_*`), weather
(`WEATHER_SET_1` / `WEATHER_SET_2`) and firmware. These use the second GATT service and a
chunked upload protocol, and firmware flashing is the one operation here that can brick a
watch — so it comes last and stays behind a warning.

## Not planned

Anything that would require an account, a cloud service, or telemetry. reCMF talks to the
watch and to Health Connect, and to nothing else.
