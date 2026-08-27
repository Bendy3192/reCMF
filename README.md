# reCMF

A third-party companion app for the **CMF Watch Pro 2**, built to do the two things the
stock app does badly: stay connected without being killed, and put the watch's data into
**Health Connect** where anything else on the phone can read it.

> **Unofficial.** Not made by, endorsed by, or connected to Nothing Technology or CMF by
> Nothing. "CMF" and "Nothing" are their trademarks and are used here only to say which
> watch this talks to. Nothing's own app is not required, modified or redistributed.

> **Status: in daily use against a real Watch Pro 2 on firmware 1.0.0.73.** Pairing,
> steps, heart rate, resting heart rate, blood oxygen, stress, weather, notifications,
> incoming calls, alarms and find-watch are confirmed on hardware. Sleep is parsed but has
> not yet met a real night, and is not stored until it has. Only one watch on one firmware
> has ever run it — see [What is verified](#what-is-verified) before trusting it with
> anything.

## Installing

Download the newest `recmf.apk` from
[Releases](../../releases/latest) and open it. Android will ask permission to install from
this source the first time; that setting is per-app and can be turned off again afterwards.

Once installed, reCMF updates itself: **Watch → Updates → Check for updates**. It reads
this repository's releases, downloads the APK and hands it to Android's installer. The
install is never silent — Android confirms every sideloaded package itself — but there is
no browser or file manager in the way.

Every build is signed with the same key, so an update installs over the last one and keeps
your settings, your paired watch and its data.

### What it needs from you

| Permission | Why | Optional? |
|---|---|---|
| Nearby devices | To find and talk to the watch. Declared `neverForLocation` — reCMF never asks where you are | no |
| Notifications | To show the connection notice Android requires of a foreground service | no |
| Notification access | To forward notifications, SMS and incoming callers' names to the watch | yes |
| Health Connect | To write steps, heart rate, resting rate and blood oxygen | yes |
| Unrestricted background use | So the background refresh runs on a schedule instead of when Android gets round to it | strongly recommended |
| Install unknown apps | Only for the in-app updater | yes |

### What leaves your phone

Two things, and nothing else. There is no account, no analytics, and no server of ours.

- **A city name and its coordinates go to [Open-Meteo](https://open-meteo.com)** when you
  turn weather on, at most once every thirty minutes. No key, no account, no device
  identifier. Leave weather off and nothing is sent.
- **A version check goes to GitHub** when you press Check for updates, and the APK is
  downloaded from there if you accept.

Your watch's data goes to your watch, to this app's own database, and to Health Connect if
you enable it. Nowhere else.

## Using it with your own fork

Two things are specific to this repository:

- `UpdateCheck.REPOSITORY` names this repository, and the updater reads its releases.
  Change it, or your fork's app will offer builds from here — which are signed with a
  different key and will not install over yours.
- The signing key in `app/recmf-debug.keystore` is public, which means anyone can build an
  APK that Android will accept as an update to an install signed with it. That is the price
  of a fresh clone building and installing with no setup. To use your own instead, put a
  base64 keystore in the `RECMF_KEYSTORE_BASE64` Actions secret along with
  `RECMF_KEYSTORE_PASSWORD`, `RECMF_KEY_ALIAS` and `RECMF_KEY_PASSWORD`; the build prefers
  them and falls back to the checked-in key when they are absent. Locally, the same four
  as environment variables.


## Why

The CMF Watch Pro 2's protocol was reverse engineered by
[Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) — the device is
supported there since issue #3899, and since PR #4004 it pairs without the user having to
supply an auth key by hand. Gadgetbridge is excellent at talking to the watch. What it
does not do is write sleep, SpO₂ or anything beyond steps and heart rate into Health
Connect, and its Health Connect export is manual.

reCMF is the other half: the same protocol, a connection service built to survive, and
Health Connect as the destination rather than an afterthought.

## Licence

**AGPL-3.0-or-later.** The `protocol/` module is a Kotlin port of Gadgetbridge's
`cmfwatchpro` sources, which are AGPL-3.0, so this project is too. See [`NOTICE`](NOTICE)
for exactly which files it derives from. The protocol work is Gadgetbridge's; reCMF only
exists because of it.

## What it does today

| | |
|---|---|
| Pairing | Negotiates its own key over the watch's shell characteristic — no auth key to find |
| Health | Steps, distance, calories, heart rate, resting heart rate, blood oxygen, stress, battery |
| Health Connect | Steps, heart rate, resting rate and blood oxygen, deduplicated by client record id |
| Watch settings | 24/7 heart rate, all-day SpO₂, stress, raise-to-wake, clock format, units, daily goals, alert thresholds, stand and drink reminders with quiet hours, the visible sport list |
| Alarms | Read from the watch and edited here, with repeat days |
| Notifications | Forwarded with icons, optionally only while the phone's screen is off; SMS included; incoming calls show the caller's name |
| Weather | Fetched from Open-Meteo for a typed city and pushed to the watch face |
| Music | Now playing on the watch face, and play, pause, track and volume from the wrist |
| Find watch | Makes it ring |
| Find phone | The watch's own button rings the phone, on the alarm stream so a silent phone still answers |
| Updates | Checks this repository's releases and installs them |
| Background | Foreground service + WorkManager watchdog + reconnect with backoff |
| UI | Compose, Material 3, dynamic colour, two tabs |

**Not done:** sleep (parsed, unverified, not stored), workouts and their GPS tracks,
contacts, a call screen with answer and reject, watchfaces, firmware.

### About Material 3 Expressive

It is not used, and not by choice. `MaterialExpressiveTheme`, `MotionScheme.expressive()`
and the wavy progress indicators are still `internal` in `compose-material3` 1.4.0. They
become public in the 1.5.x line, which pulls Compose 1.12, which requires `compileSdk 37`
and AGP 9.1 — and the Android 17 platform is not published to any installable SDK channel
yet (the `versions` CI job checks all four channels on every run and says so).

Until it is, the theme is plain Material 3 with dynamic colour. Switching over is a change
to `ui/theme/Theme.kt` and two call sites in `ui/HomeScreen.kt`.

## Architecture

```
protocol/   plain Kotlin/JVM — framing, AES, handshake, payload parsers. No Android.
app/
  ble/      GATT client, operation queue, scanner, reconnect policy
  service/  foreground service, sample ingest, watchdog, boot receiver
  data/     Room staging tables, settings, Keystore-sealed pairing key
  health/   Health Connect writer
  ui/       Compose screens
```

`protocol/` holds no Android types on purpose. It is the part that is hard to get right
and impossible to debug on a device, so it is the part that is unit-tested on the JVM.

### How the watch is talked to

Frames are an 11-byte big-endian header (`f5`, length, `cmd1`, chunk count, chunk index,
`cmd2`) followed by a body. Bodies are AES-128-CBC under a session key, with a
little-endian CRC32 appended before encryption; four commands — the two pairing messages
and the two bulk uploads — go out in the clear.

The handshake has two entry points:

- **First pairing.** Write `AT GETSECRET` to the shell characteristic, get the watch's
  app secret back, exchange nonces, and derive `K1 = SHA-256(random1 ‖ random2 ‖ secret)`
  truncated to 16 bytes. That is the long-term key.
- **Every connection after.** Load `K1`, ask for a nonce, and derive the session key as
  `SHA-256(nonce ‖ K1)` truncated to 16 bytes. `K1` never encrypts traffic itself.

Activity arrives as 32-byte per-minute records; heart rate as 8-byte pairs.

### Why it should not get killed

This is the part the stock app gets wrong, so it is worth being explicit about:

- **A `connectedDevice` foreground service.** The only category Android lets hold a
  Bluetooth link indefinitely.
- **`START_STICKY` plus a WorkManager watchdog.** Sticky restart covers ordinary kills;
  WorkManager is backed by the job scheduler and survives the ones it does not — force
  stop, crash loop, OOM. The watchdog cancels itself when nothing is paired.
- **`onTaskRemoved` restarts the service.** Swiping the app from Recents is not a request
  to stop syncing.
- **Every GATT operation is queued, awaited and timed out.** Android silently drops a
  second operation issued while the first is outstanding; a dropped write mid-handshake
  leaves the link half-open forever, and a watch that goes out of range mid-write never
  delivers its callback at all.
- **`BluetoothGatt.close()` on every teardown path.** `disconnect()` alone leaks a binder
  registration and a native connection per reconnect. A day of range flapping is what
  turns that leak into a kill.
- **Nothing unbounded.** Chunk reassembly is capped, the operation queue and message flow
  are bounded, samples go into Room in batches as they arrive rather than accumulating,
  and rows are pruned once they reach Health Connect.
- **Reconnects back off** to a five-minute ceiling with downward jitter, so a watch left
  on the nightstand does not keep the radio busy all day.

### Why data does not duplicate

The watch resends backlog freely. Two things absorb that: the local tables are keyed by
timestamp, so a resend overwrites; and every Health Connect record carries a
`clientRecordId` derived from its timestamp, so a re-upload replaces rather than adds.

## Building

```bash
./gradlew :protocol:test      # works on a bare JDK 17+
./gradlew :app:assembleDebug  # needs an Android SDK
```

The toolchain is pinned tightly, and the pins are load-bearing rather than arbitrary:

| | | why |
|---|---|---|
| Gradle | 9.5.1 | AGP 8.x uses a Gradle internal API that 9.6 removed |
| AGP | 8.13.2 | AGP 9 supplies its own Kotlin support and refuses the Kotlin Android plugin |
| compileSdk | 36 | the Android 17 platform will not install from any SDK channel |
| Compose | 1.11.4 | 1.12 requires compileSdk 37 and AGP 9.1 |

Raising any one of them alone breaks the build. They move together, once Android 17's SDK
is published.

`:app` is only included in the build when an Android SDK is present — `ANDROID_HOME`,
`ANDROID_SDK_ROOT`, or a `local.properties` with `sdk.dir`. Android Studio writes that
file itself, so opening the project just works; CI and bare JDK images get the protocol
module and nothing else, which is deliberate.

## What is verified

**Tested, on the JVM:** frame encode/decode round trips, multi-chunk reassembly including
the loss and restart cases, the reassembly ceiling, CRC and wrong-key rejection, MTU
sizing, the full pairing handshake and both session-key derivations, every payload parser
including the post-2038 timestamp case, and the reconnect schedule.

**Built and linted in CI:** the whole `app/` module — every push assembles a debug APK,
runs the app module's unit tests and passes lint with warnings as errors. The reconnect
schedule is unit-tested there.

**Confirmed against a real Watch Pro 2** (firmware 1.0.0.73), byte for byte from captured
frames: the pairing handshake, activity records — 2085 steps against 1620 m and 116 kcal,
mutually coherent — heart rate, resting heart rate (5 bytes, not the 8 the other paired
records use), blood oxygen, stress, the reminder read-back, the alarm list including the
empty case, weather, notifications, incoming calls with the caller's name, and find-watch.
The `TIME` frame settles the clock question too: the watch takes its UTC offset in
milliseconds and keeps correct time.

**Written but not yet verified:** sleep. The layout is ported from Gadgetbridge and the
stage duration's unit is unconfirmed, so nothing is stored — the app states its reading in
the log as clock times and stage letters, for someone who slept through the night to
check. Workouts and their GPS tracks are not started.

**Still unidentified:** the 16 bytes at the tail of each activity record; the eight bytes
`HEART_MONITORING_ENABLED_GET` answers with, which differ on every connection and so are
not the monitoring state; and `ffff/a055`, a stable list of six identifiers the watch
volunteers at connection time.

## Contributing and reporting

Everything about how this watch speaks was worked out by the
[Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) project, and by José
Rebelo in particular. reCMF only exists because that work was published.

Bug reports about the **protocol** belong upstream. Bug reports about **this app** belong
here — and the single most useful thing you can attach is the in-app protocol log:
**Watch → Protocol log → Show → Copy**. It carries the frames as hex, which is how every
undecoded field in this project has been worked out so far.

If your watch reports something reCMF does not understand, the log marks it — `unknown`
for an opcode not in the table, `no handler yet` for one that is. Both are worth sending.

A second watch on a second firmware would be the most valuable contribution of all: every
byte layout here has been confirmed against exactly one device.

## Credit

- [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) — the protocol, and the
  AGPL sources the `protocol/` module is ported from. See [`NOTICE`](NOTICE).
- [Open-Meteo](https://open-meteo.com) — the forecast, free and without an account.
