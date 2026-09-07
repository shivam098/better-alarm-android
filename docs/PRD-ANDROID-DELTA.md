# PRD delta: Android

The product spec is shared with
[better-alarm-ios](https://github.com/shivam098/better-alarm-ios) — see
`docs/PRD.md` there. This document records only where the Android build
**differs**, and why.

Requirement IDs below refer to that document.

---

## 1. Delivery guarantees (FR-1)

**iOS:** AlarmKit guarantees delivery. The PRD can simply assert "the alarm
rings".

**Android:** it cannot be asserted. The revised requirement is:

> The app must use the highest-priority delivery mechanism the platform offers,
> must restore all pending alarms after a reboot, must detect and report the
> conditions under which delivery will fail, and must record actual lateness so
> failures are visible rather than inferred.

Concretely:

| | Implementation |
|---|---|
| Highest-priority tier | `AlarmManager.setAlarmClock()` — the only Doze-exempt tier |
| Reboot | `BootReceiver` on `BOOT_COMPLETED` **and** `LOCKED_BOOT_COMPLETED` |
| Cold start | `rescheduleAll()` in `Application.onCreate()` |
| Detection | Six checks in `ReliabilityChecker` |
| Reporting | `AlarmOccurrence.latenessSeconds`, surfaced in History |

### Accepted failure: force-stop

If the user force-stops the app from system settings, Android cancels every
`AlarmManager` alarm it owns. **No app can prevent or detect this.** Alarms are
restored on next launch.

This is documented in the README rather than hidden. An alarm app that quietly
fails is worse than one that tells you it can fail.

---

## 2. Mission Guard (FR-4)

The feature survives; the threat model changes completely.

**iOS threat:** the system's own Stop button, which cannot be removed. Guard
works by scheduling a chain of follow-up alarms in advance, because a terminated
app never learns the button was pressed.

**Android threat:** the app draws the entire screen, so there is no Stop button
to defeat. What the user can do instead:

| Evasion | Defence |
|---|---|
| Swipe away the notification | Foreground service; notification is ongoing |
| Press Home / switch apps | `onStop` reports `surfaceLost`, surface is re-shown |
| Press Back | Intercepted while guarded |
| Swipe the app from Recents | `stopWithTask="false"` |
| Turn the volume down | Alarm stream floor restored (guarded alarms only) |
| Kill the process | Watchdog chain re-fires from `AlarmManager` |
| **Force-stop from settings** | **Not defensible.** See above. |

Because the app is alive while ringing, Android can respond *reactively*. iOS
cannot, and must pre-schedule. The watchdog chain is retained here anyway, as the
backstop for whole-process death.

---

## 3. New requirement: Reliability (FR-11, Android only)

Has no iOS equivalent and no iOS need.

> The app must check every condition under which Android could prevent an alarm
> from ringing, present each as a plain-language consequence rather than a
> setting name, offer a direct route to fix it where the platform provides one,
> and supply written instructions where it does not.

Six programmatic checks: notification permission, exact alarm permission,
full-screen intent permission, battery optimisation, channel not muted, and
observed lateness.

Plus written OEM guidance for Xiaomi/Redmi/POCO, Huawei/Honor, Samsung,
OPPO/realme, vivo/iQOO, OnePlus and ASUS — written rather than checked because
**no API can read those settings.**

**Wording rule:** every item states what will *happen*. "Your alarm may not ring"
is actionable; "battery optimisation is enabled" is trivia.

---

## 4. Missions (FR-3)

All eleven types are present on both platforms. Two are implemented differently.

### Photo mission

| | iOS | Android |
|---|---|---|
| Capture | `PhotosPicker` / camera | `ActivityResultContracts.TakePicturePreview()` |
| Matching | Perceptual hash | Perceptual hash (`core.mission.PhotoHasher`) |
| Stored | Hash only | Hash only |

`TakePicturePreview` returns a thumbnail with no `FileProvider`, no
`WRITE_EXTERNAL_STORAGE` and no temp file to clean up. A thumbnail is ample for a
9x8 hash, so the cheapest option is also the most private one.

### QR mission

| | iOS | Android |
|---|---|---|
| Scanner | `AVCaptureMetadataOutput` | `com.journeyapps:zxing-android-embedded` |

ZXing was chosen over ML Kit because it is a single artifact, works fully
offline, and needs no Google Play Services. An alarm must work on a phone in
flight mode.

---

## 5. Permissions

| Purpose | iOS | Android |
|---|---|---|
| Alarms | AlarmKit authorisation | `USE_EXACT_ALARM` (33+), `SCHEDULE_EXACT_ALARM` (≤32) |
| Notifications | `UNUserNotificationCenter` | `POST_NOTIFICATIONS` (33+) |
| Full-screen | n/a | `USE_FULL_SCREEN_INTENT` |
| Reboot | n/a | `RECEIVE_BOOT_COMPLETED` |
| Foreground service | n/a | `FOREGROUND_SERVICE`, `..._MEDIA_PLAYBACK` |
| Motion | `CMMotionActivity` | `ACTIVITY_RECOGNITION` (29+) |
| Camera | `NSCameraUsageDescription` | `CAMERA` |
| Network | none | **none — `INTERNET` is not declared** |

`USE_EXACT_ALARM` is a normal permission, granted automatically, and Google Play
restricts it to genuine alarm and calendar apps. That is the correct category
here. `SCHEDULE_EXACT_ALARM` is kept with `maxSdkVersion="32"` for older devices.

---

## 6. Mission substitution (FR-3.4)

Unchanged in intent, wider in scope. Android hardware varies far more, so a
mission may be unavailable because:

- the sensor does not exist (many tablets have no step counter)
- the permission was denied
- there is no camera

In all three cases the mission is substituted at ring time and the ringing screen
**says so**. Silently swapping a squat mission for maths would look like a bug,
and a user who cannot explain what their alarm did will stop trusting it.

`MissionPermission.MOTION` maps to no Android permission, but is modelled anyway
so that a missing sensor takes the same code path as a denied permission.

---

## 7. Out of scope for v1

Same as iOS, plus:

- **Wear OS companion** — meaningful on Android, but doubles the delivery
  surface.
- **Widgets / Glance** — the status-bar alarm icon from `setAlarmClock()` already
  covers the main need.
- **Backup/restore** — `dataExtractionRules` deliberately excludes alarm data.
  Restoring alarms onto a different device would arm alarms the user did not set
  there.
