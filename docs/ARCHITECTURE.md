# Architecture

## The inversion

Everything here follows from one observation: **Android and iOS pose opposite
problems for an alarm app.**

| | iOS (AlarmKit) | Android |
|---|---|---|
| Delivery | Guaranteed by the OS | Best-effort |
| Survives reboot | Yes, automatically | Only if you re-arm in `BOOT_COMPLETED` |
| Survives force-quit | Yes | **No.** Alarms are cancelled by the system |
| Silent Mode / DND | Rings anyway | Rings only via the alarm stream + channel setup |
| Ringing UI | System-drawn, Stop button mandatory | Entirely app-drawn |
| App alive while ringing? | No | Yes, via a foreground service |
| Hardest problem | Making a mission matter next to an undeletable Stop button | **Making the alarm fire at all** |

On iOS the app must pre-schedule a watchdog chain, because a terminated app never
learns it was dismissed and gets no chance to react. On Android the app *is*
running while the alarm rings, so it can react live — but it may never get to
ring in the first place.

Both codebases share a domain model and share nothing else.

## Module layout

```
core/          Standalone Gradle build. Pure Kotlin/JVM. Zero Android imports.
app/           Android application. Includes core via includeBuild().
```

### Why `core` is a separate build, not a subproject

If the root build applied the Android Gradle Plugin, Gradle's configuration phase
would require an Android SDK **even to run pure JVM tests**. Making `core` a
standalone build means:

```bash
cd core && ./gradlew test    # needs a JDK and nothing else
```

That is what allowed the logic to be genuinely compiled and tested on a machine
with no Android tooling, and it keeps CI to a plain Ubuntu runner with no SDK
download.

The app depends on it through automatic `group:name` substitution:

```kotlin
// settings.gradle.kts
includeBuild("core")

// app/build.gradle.kts
implementation("com.alarmy:core:1.0.0")
```

One caveat: in a composite build the included build runs under the *root* build's
Gradle version, so `core/build.gradle.kts` must stay Gradle-8 compatible.

## `core` — the part that is proven

No Android types, no clock reads, no randomness that isn't seeded. Every decision
is a function of its arguments, which is why 198 tests can cover it.

### `model/`
`Alarm`, `MissionConfig`, `AlarmOccurrence`, `Weekday`. All `@Serializable`.

`OccurrenceKey` deserves a note. It is a stable identity for *one firing of one
alarm*:

```
<alarmId>-<yyyyMMdd>-<HHmm>[#watchdog-<n>]
```

Any reconciliation, timezone change, DST shift or reboot re-arm that produces the
same key is a no-op. This matters far more on Android than iOS, because
`BOOT_COMPLETED` forces a **full re-arm on every restart** — a naive
implementation would duplicate every pending alarm each time the phone rebooted.

`OccurrenceKey.requestCode(key)` is an FNV-1a hash masked to 31 bits.
`PendingIntent` identity is `(requestCode, Intent.filterEquals)` and has nowhere
to put a UUID, so deriving the request code from the key is precisely what lets
the app cancel an alarm it scheduled *before a reboot it never saw*. The key also
goes in the intent's data URI (`betteralarm://occurrence/<key>`), because extras
are ignored by `filterEquals`.

### `schedule/`
`AlarmScheduleCalculator` computes the next fire instant. All arithmetic is
delegated to `java.time` — never epoch maths — so DST is handled by rules rather
than by hope.

- **Spring forward:** an alarm at 02:30 on a day where 02:00–03:00 does not exist
  shifts to 03:30. An alarm that doesn't ring is worse than one that rings
  shifted.
- **Fall back:** the earlier of the two offsets wins, so it fires once.

Verified against `America/New_York` on 2026-03-08 and 2026-11-01.

`SchedulingBudget(capacity = 100)` bounds the number of pending alarms. Android
has no hard cap (unlike the old 64-notification iOS limit), but an unbounded
watchdog chain would still be antisocial, and making the trimming order explicit
means it is testable.

### `state/`
`AlarmStateMachine` is a pure reducer: `(State, Event) -> (State, List<Effect>)`.
Nothing in it touches Android. `RingingController` in the app layer is the only
thing that turns an `AlarmEffect` into an Android call, so a bug there is a
wiring bug, not a logic bug.

`WakeStatistics` computes streaks with grace days. The grace accrues *during the
backward walk*, which forgives old gaps in a long run rather than recent ones —
that asymmetry is intentional and is pinned by tests.

### `mission/`
Challenge generators, all seeded. `MotionValidators` holds the anti-cheat:

- `ShakeCounter` rejects motion whose interval coefficient of variation is below
  0.08 and **resets the count** when it does. Human shaking sits around 0.25.
- `StepValidator` uses a tighter 0.04, because walking is more regular.
- `SquatRepCounter` requires a real range of motion and rejects sub-second
  bounces.

`PhotoHasher` is a difference hash: reduce to a 9x8 grayscale grid, record
whether each pixel is brighter than its right neighbour, giving 64 bits.
Brightness-invariant, dependency-free, and pure arithmetic — so it is unit-tested
rather than trusted. Match threshold is 12 of 64 bits. Leniency is the safer
error: the user still had to walk to the right room.

## `app` — the part that needs a device

### Delivery: `alarm/AlarmScheduler`

The heart of the Android version. Three defensive properties:

1. **Uses `setAlarmClock()`** — the only tier Doze does not defer. It also
   surfaces the alarm in the system status bar, which is an honesty feature as
   much as a technical one.
2. **Records what it scheduled**, so a cold start can tell what should be armed.
3. **Is re-runnable from nothing.** `rescheduleAll()` is the single scheduling
   entry point and is idempotent, so `BOOT_COMPLETED`, app launch and
   post-outcome cleanup can all call it freely.

### Ringing: activity + service

`RingingService` is a foreground service using type **`mediaPlayback`**.

There is no `alarm` foreground service type on Android 14/15 — this was verified
against the platform documentation rather than assumed. The three plausible
alternatives were rejected:

| Type | Why not |
|---|---|
| `shortService` | ~3 minute hard timeout, then ANR. A mission can take longer. |
| `systemExempted` | Privileged roles only; throws `InvalidForegroundServiceTypeException`. |
| `specialUse` | Requires per-app Google Play review. |

`mediaPlayback` is used because the service genuinely plays audio. Starting it
from the background is permitted here because it happens while handling an exact
alarm broadcast, which grants a temporary allowlist exemption.

`RingingActivity` is launched by the full-screen intent and draws everything.
Manifest settings that matter:

- `excludeFromRecents` + `taskAffinity=""` — keeps it out of the main task, so
  dismissing the app from Recents does not take the ringing screen with it.
- `showWhenLocked` + `turnScreenOn` — appear over the lock screen with the
  display on. `requestDismissKeyguard` only dismisses an *insecure* keyguard; a
  PIN or biometric lock is never bypassed.

`RingingController` is a **process-wide singleton** shared by both. State passed
through Intents would be lost exactly when memory pressure makes losing it fatal.

### Reboot: `alarm/BootReceiver`

The single most important component in the app. It listens for both
`BOOT_COMPLETED` and `LOCKED_BOOT_COMPLETED` — on file-based-encryption devices,
`BOOT_COMPLETED` is not delivered until the user unlocks, which could be hours.

### Persistence: `data/JsonStore`

JSON files, not Room. The domain objects are already `@Serializable`, the data
set is small, and every read is a whole-collection read — Room would add KSP and
a schema migration burden for no benefit.

Writes are atomic (temp file, then rename) because an alarm clock is a program
that gets killed at arbitrary moments, and a half-written alarm list is a
missed alarm.

### DI: `AppGraph`

Manual, not Hilt. Six process-scoped singletons, and receivers and services are
constructed by the system anyway — each would need an entry point regardless.

## Deliberate deviations from the Swift codebase

| Swift | Kotlin | Why |
|---|---|---|
| `Weekday` 1 = Sunday | ISO 1 = Monday | Matches `java.time.DayOfWeek` |
| `UUID` | `String` | Serialises cleanly, works as a map key |
| `Date` | epoch-millis `Long` | Stable JSON, no timezone ambiguity at rest |
| `case shakeMission` | `SHAKE` | Kotlin convention |
| `motionFitness` permission | `ACTIVITY_RECOGNITION` | Android equivalent |
| — | `DeliveryTier` | Records *how* an alarm was scheduled. Meaningless on iOS |
| — | `latenessSeconds` | Records how late it actually fired. Meaningless on iOS |

The last two exist only because Android delivery is unreliable. They are what let
the history screen say "your alarms have been late four times this week" instead
of leaving the user to conclude they are simply bad at waking up.

## Serialization gotcha

A cross-module enum used as a property type **must** be annotated
`@Serializable`, or the plugin cannot generate a serializer for the enclosing
class. Hit with `MissedWindowRule` (declared in `core.schedule`, used by
`AppSettings` in `app`).
