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

## Download and verification

Installable APKs and their checksums are published in
[GitHub Releases](https://github.com/shivam098/better-alarm-android/releases).
Read the release's signing and device-testing notes before installing.
A debug-signed APK is a development build, **not** a production-signed release.
Its package is `com.alarmy.app.debug`, separate from `com.alarmy.app`. Android
will only upgrade an existing installation signed by the same certificate;
an incompatible development certificate requires uninstalling (which loses
local data). Never uninstall without first considering your saved alarms.

The Android build workflow compiles the app, runs JVM/Robolectric regressions
and lint, and verifies APK metadata/signature. Core tests remain independently
runnable without an SDK. These checks do **not** prove overnight delivery,
audibility, camera matching, or OEM battery behavior; those need a device.

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

# 2. Build the app and check Android persistence regressions and lint.
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

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
3. **Java is missing or too old.** Use JDK 17 for the pinned build configuration.

---

## Testing

### The tests that exist

```bash
cd core && ./gradlew test
```

Tests over the pure logic: schedule calculation across DST boundaries,
the alarm state machine, snooze ladders, streak counting with grace days, the
anti-cheat motion validators, and the photo hasher. These are the specification,
written as assertions. Android JVM tests additionally recreate the repository,
reconcile persisted snoozes/wake checks, and verify chain cancellation.

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
leave the alarm audible, with a notification that returns you to the mission.
Opening the camera or a permission dialog must not stop the alarm or repeatedly
relaunch the mission screen.

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
| Steps | Get up and walk | Plausible cadence; mission-local sensor deltas |
| Squats | Actual squats, phone in hand | Partial dips and fast bounces do not count |
| Photo | Photograph a place you registered | Perceptual hash, so it must be *that* place |
| QR | Scan a code you placed somewhere | Payload must match the registered one |
| Combination | Several of the above in sequence | Each link seeded independently |
| None | Press and hold | Even this is not a single tap |

**The anti-cheat detail worth explaining.** Shaking a phone by hand produces
intervals with a coefficient of variation around 0.25. Taping it to a fan, or
letting a shake-bot do it, produces near-zero variance. The validator rejects
anything below 0.08 — and, importantly, *resets the count* when it does. That
check is not used for steps: normal walking and quantized sensor timestamps
can be very regular. Walking rejects implausibly fast events instead.

Walking tries the step detector, then the cumulative step counter (counting
only changes after the mission's first reading), then foreground accelerometer
peaks. A failed registration or eight seconds without counted steps advances
to the next source, without adding steps twice or erasing progress. The screen
shows which source is active. Motion counting is an approximation, not verified
hardware pedometry; keep the phone in a trouser pocket and the mission visible.
Physical activity permission is required; denying it does not silently change
Walking into Math. If no steps arrive, use **Retry sensors** (explicitly restarts
the count) or **App settings** to recover permission. Counting pauses while the
mission is not foreground; returning establishes a fresh counter baseline.

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
core/                        Pure Kotlin. No Android imports.
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

**All seven selectable tones are original synthesized sounds.** They are
generated locally as PCM, without licensed recordings or downloads. The sound
settings include a backup tone, a 5-120 second gentle ramp, and optional spoken
label/current time using an installed offline voice. If a tone cannot play,
the backup is tried; emergency alarm-stream beeps remain the final fallback.
Speech never replaces the tone, and unavailable speech is logged.

**Photo and QR missions must be registered before saving**, including each
combination child. Only a photo fingerprint is stored, never the photo.
Camera/activity permissions are requested during setup, with app-settings
recovery for denied permissions. At ring time, unavailable permissions or
missing targets substitute a real mission, not a dismiss shortcut. Mission
preview uses the actual normalized chain and custom typing sentence.

**Snooze limits survive re-firing and process restarts.** Shrinking is optional.
When mission-before-snooze is enabled, requesting snooze starts a fresh mission;
finishing it grants snooze rather than dismissing the alarm. Reconciliation
retains pending snoozes and guards. Consumed one-time alarms are not re-armed
for tomorrow unless edited or re-enabled.

**Wake check is separate from Mission Guard and off by default.** After a
completed mission, it posts a confirmation after the configured 1-30 minutes.
If not confirmed within one minute, the alarm rings again and requires the
mission again. Both events are persisted and restored after a restart/reboot.
Denied notifications prevent confirmation, so the re-alarm still fires.
Force-stop still defeats both mechanisms.

**Not full Alarmy Premium parity.** Overnight recording/snore detection, AI
or speech-recognition missions, habits, backgrounds, and sleep analysis remain
separate product work. No microphone permission or recording is introduced,
and no last-minute edit lock is imposed.

**Guarded alarms fight volume changes.** If an alarm is marked guarded and you
press volume-down while it is ringing, the app restores the alarm stream to 60%.
This is aggressive, and it is deliberately limited to alarms you explicitly
marked as guarded — overriding a person's direct input is only defensible when
they asked for it in advance. Unguarded alarms never do this.

**The app has no INTERNET permission.** Not "does not send data" — cannot. There
is no account, no analytics, no network code. Everything is in
device-protected app storage on your phone so alarms can be restored before
the first unlock after a reboot. Existing JSON files migrate on an unlocked
launch; targets and preferences are included in Android backup, while pending
delivery bookkeeping is not. `adb uninstall` removes local data.

**Force-stop defeats it.** Explained in Testing, step 8. Nothing can be done
about this from inside an app.

**`minSdk` is 26 (Android 8.0).** Below that there is no
`setShowWhenLocked`, no notification channels, and no `java.time` without
awkward workarounds.

---

## Licence

MIT. See [LICENSE](LICENSE).
