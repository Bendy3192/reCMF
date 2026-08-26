# reCMF

A third-party companion app for the **CMF Watch Pro 2**, built to do the two things the
stock app does badly: stay connected without being killed, and put the watch's data into
**Health Connect** where anything else on the phone can read it.

> **Status: it builds and its tests pass, but it has never been run against a watch.**
> See [What is verified](#what-is-verified) before trusting it with anything.

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
| Sync | Steps, distance, calories, heart rate, battery |
| Health Connect | Writes `StepsRecord` and `HeartRateRecord`, deduplicated |
| Background | Foreground service + WorkManager watchdog + reconnect with backoff |
| UI | Compose, Material 3, dynamic colour |

Sleep, SpO₂, stress, workouts and notifications are parsed by the protocol in
Gadgetbridge but are not wired up here yet.

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

**Not verified at all:** anything that depends on how a real watch behaves — whether the
handshake completes end to end, what the unidentified 16 bytes in each activity record
mean, whether `distance` is metres, and what `calories` is a unit of. These are marked in
the code where they are assumed.

If you have a Watch Pro 2, the first useful thing is to run it and read the logs.

## Credit

Everything about how this watch speaks was worked out by the Gadgetbridge project, and by
José Rebelo in particular. Bug reports about the *protocol* belong upstream; bug reports
about this app belong here.
