# LandMeasure

Native, offline-first Android implementation of the **MVP** in
`FRD-LMA-001`, version 1.0, dated November 16, 2026.

**Android 7.0 (API 24) minimum; Android 17 (API 37) target.**
Application ID: `com.mogada.landmeasure`.

## Install

Use `artifacts\LandMeasure-debug.apk` for device evaluation. It is signed with
the development debug certificate, not a production distribution certificate.
Transfer it to a phone and allow installation from the app used to open it.

The optimized release APK in `app\build\outputs\apk\release` is unsigned.
Production signing and store publication are intentionally not configured.
Keep a future production keystore outside this repository.

## Included

- Draft autosave, Ready validation, optional owner/contact/reference details,
  Kenya-oriented location fields, and a GPS anchor independent of walking.
- Precise foreground GPS readiness, automatic or manual corner capture,
  visible foreground notification, pause/resume, and immediate waypoint commits.
- Accuracy, freshness, duplicate, speed, jump, monotonic-time and point-limit
  checks; explicit manual overrides and mock-location warnings.
- Interrupted-session recovery as Paused; session timing remains ordered across
  device reboot without silently restarting tracking.
- Offline boundary canvas, numeric/GPS point edits, ordering, and 20 persisted
  undo/redo operations. Map tiles and API keys are not required.
- WGS84 ellipsoidal area/perimeter using GeographicLib's Karney algorithm.
  JTS validates the unwrapped coordinate ring, including self-intersections.
  Canonical square metres/metres remain separate from display units.
- Explicit completion review, closure acknowledgement, immutable result
  snapshots, versioned geometry revisions, and local audit history.
- Local search, combined status/usage/unit/county/date/quality filters, sorting,
  metadata-only duplication, archive/restore, and 30-day Recently Deleted.
- Previewed Android text sharing; coordinates are excluded unless selected.
  Owner contacts, identifiers and consent notes are not included.
- Configurable capture defaults, independent distance units, haptic/audio
  feedback controls, and resource-based English UI strings.

SQLite is accessed on a single background executor with foreign keys, WAL,
transactional completion/checkpoints, and explicit schema versioning. The
initial schema is version 1; an unknown upgrade fails without deleting data.
Metadata snapshots are kept with the activity; waypoints, geometry versions,
results, events, undo operations and settings have separate constrained tables.
There is no network permission, cloud account, analytics, map SDK or external
storage dependency. Android backup/device transfer are disabled.

## Build on this machine

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.12'
Set-Location 'Q:\src\mogada\LandMeasure'
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

The included Gradle wrapper downloads Gradle 9.4.1. Android Gradle Plugin
9.2.1, JDK 21, SDK platform 37.0 and Build Tools 36.0.0 were used for validation.
`local.properties` points to the SDK installed in `..\.tools`; update `sdk.dir`
when moving the project, or import it in Android Studio.
The SDK and emulator tooling live outside the app folder under
`Q:\src\mogada\.tools`.

For connected tests:

```powershell
$env:ANDROID_SERIAL = 'emulator-5556' # Choose an already-booted device.
.\gradlew.bat :app:connectedDebugAndroidTest
```

The GPS instrumentation test uses a mock provider and grants location/mock
permissions only to its test installation. Never run that test against a
production installation containing real user data: the test resets its database.

## Verification and release limits

Validation on November 16, 2026:

- 28 JVM/Robolectric tests passed: geodesic reference fixtures, equator/high
  latitude/antimeridian, invalid rings, a 10,000-vertex ring, capture filtering,
  recovery/reboot timing, transactional fault injection, foreign keys,
  immutable snapshots, revisions, retention, search and combined filters.
- Four instrumentation tests passed on **Android 7.0/API 24** and the same four
  passed on **Android 17/API 37**. They exercise draft UI autosave, review and
  completion UI, real device SQLite/recovery/filtering, foreground service GPS
  delivery, mock indication and stopping point acceptance after Pause.
- Strict debug lint passed; optimized release compilation and resource/code
  shrinking passed. JUnit XML results are retained in `verification`.

This is an evaluation build, not a claim that every FRD release gate has been
certified. Intermediate Android versions, physical GNSS performance, screen-off
behavior on vendor battery managers, force-kill/storage-exhaustion fault
injection, TalkBack/200% text walkthroughs, and low-cost-device p95 performance
still need device/field qualification. A 5,000-record p95 benchmark and legal
wording approval have not been completed.

The FRD's R1/R2 capabilities are **not implemented**: basemap drawing/dragging,
photos/documents, CSV/GeoJSON/KML/PDF, custom units, app lock/database-layer
encryption, encrypted backup, cloud sync and external GNSS. English ships first;
Kiswahili translation is not included. Diagnostic telemetry is absent rather
than collected. Consumer-GPS thresholds and the 100 m² small-parcel warning are
provisional and require field validation.

Search returns up to 500 matches per request; narrow the search/filter for
older records. Recently Deleted cleanup runs on the next app startup after
the 30-day expiry. Uninstalling removes local records; there is no backup yet.

**All results are estimates, not legal boundaries or certified surveys.**
