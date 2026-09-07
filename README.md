# Better Alarm — Android

A mission-based alarm clock for Android. It will not turn off until you have
proved you are awake: solve maths, repeat a flashed pattern, shake the phone,
walk, do squats, type a phrase, photograph a place in your home, or scan a QR
code you stuck somewhere inconvenient.

This is the Android counterpart to
[better-alarm-ios](https://github.com/shivam098/better-alarm-ios). Both are
built from the same product spec, but they are **not** the same program, for a
reason worth reading before you look at the code.

---

## Read this first: what is verified and what is not

I want to be exact about this, because "it builds" is a claim I cannot make.

| Part | State |
|---|---|
| `core/` — all scheduling, state, mission and statistics logic | **Compiled and tested. 198 tests, 0 failures, run on the authoring machine.** |
| `app/` — the Android layer (UI, alarms, services, receivers) | **Written, reviewed, never compiled.** There was no Android SDK available where this was written. |

So: the part where the *thinking* lives is proven. The part where the *wiring*
lives is not. Expect to fix some import and Compose API errors on your first
build — most likely in the Compose UI files, which use a few Material 3 APIs
whose exact signatures move between versions. The logic underneath them will
not be the problem.

Run `cd core && ./gradlew test` before anything else. If that is green, the
brain of the app is intact and everything else is plumbing.

---

## Why Android is a different program from iOS

The iOS version and this one solve *opposite* problems.

| | iOS (AlarmKit) | Android |
|---|---|---|
| Will the alarm fire? | **Guaranteed.** Survives reboot, force-quit, Silent Mode and Focus. | **Best-effort.** Doze, OEM battery managers, force-stop and reboot can all stop it. |
| Who draws the ringing screen? | **The system.** A Stop button is always there and cannot be removed. | **You do.** A full-screen intent means every pixel is ours. |
| So the hard problem is… | Making the mission matter, given a Stop button you cannot delete. | Making the alarm *happen at all*. |
| Can the app react while ringing? | No — a terminated app never learns it was dismissed, so the watchdog chain must be scheduled in advance. | Yes — a foreground service is alive and executing. |

The consequence runs through the whole codebase. On iOS, most of the effort goes
into surviving a Stop button. Here, most of it goes into the
[Reliability](#reliability-the-screen-with-no-ios-equivalent) screen and the
boot receiver — because on Android, an alarm app is only as good as the settings
the user was never told about.

---

## Getting it running

### Requirements

- **Android Studio Ladybug (2024.2.1) or newer**
- **JDK 17** — Android Studio bundles one; `File → Project Structure → SDK Location → Gradle JDK`
- **Android SDK 35** with build-tools 35
- A **physical device** for real testing. See [Testing](#testing) — an emulator cannot tell you what you need to know.

### Build

```bash
git clone https://github.com/shivam098/better-alarm-android.git
cd better-alarm-android

# 1. Prove the logic is sound. Needs only a JDK, no Android SDK.
cd core && ./gradlew test && cd ..

# 2. Build the app.
./gradlew assembleDebug

# 3. Install on a connected device.
./gradlew installDebug
```

Or just open the project folder in Android Studio and press Run. It will pick up
`settings.gradle.kts`, including the `core` build.

### If the build fails

Most likely causes, in order:

1. **Compose API drift.** Material 3 renames things between versions. The pinned
   BOM is `2024.11.00`; if you change it, `menuAnchor`, `HorizontalDivider` and
   `LinearProgressIndicator`'s lambda-based `progress` parameter are the usual
   casualties.
2. **`Android SDK not found`.** Add a `local.properties` with
   `sdk.dir=/Users/you/Library/Android/sdk`. It is gitignored on purpose.
3. **Gradle JDK is 21.** AGP 8.7 wants 17. Set it in Project Structure.

---

## Testing

### The tests that exist

```bash
cd core && ./gradlew test
```

198 tests over the pure logic: schedule calculation across DST boundaries,
the alarm state machine, snooze ladders, streak counting with grace days, the
anti-cheat motion validators, and the photo hasher. These are the specification,
written as assertions.

There are **no instrumented tests**. Not an oversight — an Android test runner
cannot meaningfully simulate "the phone was in a drawer for six hours and MIUI
decided to freeze the app". The things worth testing on Android are things only
a real device can tell you, so they are written up below as a manual list.

### What to check on a real device

Do these in order. Each one is a real failure mode that shipped in real alarm
apps.

**1. It rings at all**
Set an alarm for two minutes out. Lock the phone. Wait.

**2. It rings with the screen off and the phone idle**
Set one for 30 minutes out, then leave the phone untouched, unplugged and face
down. This is the Doze test, and it is the one that matters most.

**3. It rings after a reboot**
Set an alarm for 15 minutes out. Reboot. Do not open the app. Wait.
*If this fails, `BootReceiver` is the first place to look — it is the single
most important component in the app.*

**4. It survives a swipe from Recents**
Start the alarm ringing, then swipe the app away from Recents. The alarm should
keep ringing. (`stopWithTask="false"` on the service.)

**5. It survives the notification being dismissed**
While ringing, swipe the notification or hit "Clear all". Nothing should change.

**6. Mission Guard actually guards**
With a guarded alarm ringing, press Home mid-mission. The ringing screen should
come straight back.

**7. Volume cannot silence it**
While a guarded alarm rings, press volume-down to zero. The alarm volume should
be restored. *(This is deliberately only done for alarms you marked as guarded —
see [Things you should know](#things-you-should-know).)*

**8. Force-stop, and be honest about the result**
Settings → Apps → Better Alarm → Force stop. Then set an alarm.
**It will not ring.** Android cancels every `AlarmManager` alarm belonging to a
force-stopped app, and no app can prevent this. Reopening the app re-arms
everything. This is documented rather than hidden because pretending otherwise
would be the actual harm.

**9. The OEM test**
On a Xiaomi, Huawei, OPPO, vivo or Samsung device, run test 2 *before*
following the Reliability screen's advice, then again after. The difference is
the entire reason that screen exists.

---

## Reliability: the screen with no iOS equivalent

Android will happily let an alarm app fail silently. Six things can break it,
and the app checks all six on every visit to the Reliability tab:

- notification permission
- exact alarm permission
- full-screen intent permission (restricted since Android 14)
- battery optimisation exemption
- notification channel not muted by the user
- whether alarms have actually been firing late

Each is phrased as **what will happen**, not what is misconfigured. "Your alarm
may not ring" is actionable; "battery optimisation is enabled" is trivia.

Below those sits per-manufacturer guidance for Xiaomi/Redmi/POCO, Huawei/Honor,
Samsung, OPPO/realme, vivo/iQOO, OnePlus and ASUS. These are written out as
instructions rather than checked in code because **no API exists to read them**.
An app cannot tell whether MIUI has put it to sleep. It can only tell you where
to look.

---

## The missions

Eleven of them, all with the same anti-cheat principle: the mission must be
harder to fake than to do.

| Mission | What it asks | Anti-cheat |
|---|---|---|
| Maths | Arithmetic, four difficulty tiers | Recent questions are never repeated |
| Missing symbol | Spot the gap in a sequence | Distractors drawn from the same symbol set |
| Memory | Repeat a flashed grid pattern | Wrong tap restarts the whole sequence |
| Typing | Retype a phrase exactly | Autocorrect disabled at the IME level |
| Shake | Shake the phone N times | Rejects mechanically regular motion |
| Steps | Get up and walk | Same, on step intervals |
| Squats | Actual squats, phone in hand | Partial dips and fast bounces do not count |
| Photo | Photograph a place you registered | Perceptual hash, so it must be *that* place |
| QR | Scan a code you placed somewhere | Payload must match the registered one |
| Combination | Several of the above in sequence | Each link seeded independently |
| None | Press and hold | Even this is not a single tap |

**The anti-cheat detail worth explaining.** Shaking a phone by hand produces
intervals with a coefficient of variation around 0.25. Taping it to a fan, or
letting a shake-bot do it, produces near-zero variance. The validator rejects
anything below 0.08 — and, importantly, *resets the count* when it does. Same
idea on steps, with a tighter threshold because walking is more regular than
shaking.

**How the photo mission works.** When you set the alarm you photograph a place —
the bathroom sink, the kettle. Only a 64-bit *difference hash* is stored, never
the image. The hash records whether each pixel is brighter than its right-hand
neighbour, which makes it survive a lighting change but not a change of room. A
64-bit hash cannot be inverted into a picture, so registering the inside of your
home leaves no picture of it on disk.

Its one real weakness is documented and unit-tested: two featureless surfaces —
two blank white walls — hash to the same value and will match each other. Do not
register a blank wall.

---

## Architecture

Full detail in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). The short version:

```
core/                        Pure Kotlin. No Android imports. 198 tests.
  model/                     Alarm, MissionConfig, AlarmOccurrence, Weekday
  schedule/                  Next-fire calculation, watchdogs, DST, overlaps
  state/                     Alarm state machine, wake statistics
  mission/                   Challenge generation, motion validators, photo hash
  support/                   Seeded RNG, injectable clock

app/                         Everything that needs Android.
  alarm/                     AlarmManager, receivers, foreground service, audio
  data/                      Atomic JSON persistence
  reliability/               The six checks and the OEM guidance
  sensor/                    Accelerometer and step counter plumbing
  ui/                        Compose screens
```

`core` is a **separate Gradle build**, not a subproject, included via
`includeBuild("core")`. That is deliberate: if the root build applied the Android
plugin, Gradle would demand an Android SDK just to *configure* the project — even
to run pure JVM tests. As a standalone build, `cd core && ./gradlew test` needs
nothing but a JDK. That is what made it possible to genuinely verify the logic
on a machine with no Android tooling, and it is what makes CI cheap.

---

## Things you should know

**No alarm sounds ship with this repo.** Bundling audio means bundling someone's
copyright. The app falls back to your device's default alarm ringtone. To add
your own, drop `.ogg` files into `app/src/main/res/raw/` named after the entries
in `SoundLibrary`.

**Guarded alarms fight volume changes.** If an alarm is marked guarded and you
press volume-down while it is ringing, the app restores the alarm stream to 60%.
This is aggressive, and it is deliberately limited to alarms you explicitly
marked as guarded — overriding a person's direct input is only defensible when
they asked for it in advance. Unguarded alarms never do this.

**The app has no INTERNET permission.** Not "does not send data" — cannot. There
is no account, no analytics, no network code. Everything is in
`getFilesDir()` on your phone. `adb uninstall` removes all of it.

**Force-stop defeats it.** Explained in Testing, step 8. Nothing can be done
about this from inside an app.

**`minSdk` is 26 (Android 8.0).** Below that there is no
`setShowWhenLocked`, no notification channels, and no `java.time` without
awkward workarounds.

---

## Licence

MIT. See [LICENSE](LICENSE).
