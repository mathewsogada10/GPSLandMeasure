# GPSLandMeasure

Native, offline-first Android implementation of the **MVP** in
`FRD-LMA-001`, version 1.0, dated November 16, 2026.

**Android 7.0 (API 24) minimum; Android 17 (API 37) target.**
Application ID: `com.mogada.landmeasure`.
Version 1.2.0 (version code 3). The application ID and database schema are
unchanged so this build updates the existing evaluation app without losing records.

## Install

Use `artifacts\GPSLandMeasure-1.2.0-debug.apk` for device evaluation. It is signed with
the development debug certificate, not a production distribution certificate.
Transfer it to a phone and allow installation from the app used to open it.

The optimized release APK in `app\build\outputs\apk\release` is unsigned.
Production signing and store publication are intentionally not configured.
Keep a future production keystore outside this repository.

## Included

- Seven registration inputs: activity title, owner/occupant, optional phone,
  optional email, purpose/consent note, land usage and brief location description.
  Draft autosave and existing Ready validation are retained. Hidden legacy
  details remain stored and searchable when an older record is edited.
- Separate **Measurement options** for area units, capture mode and GPS anchor,
  keeping technical controls out of registration.
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
- Local search, combined status/usage/area-size/updated-date/quality filters,
  sorting, metadata-only duplication, archive/restore, and 30-day Recently Deleted.
- Database-backed activity pagination, 10 records per page, with stable sorting
  and access beyond the former 500-match UI limit. The current page and filters
  survive recreation. Clear all filters also clears search, restores All records
  and Last modified sorting, and returns to the first page.
- Compact cards show only the activity title, drafted/completed/updated/deleted
  date, status, size and GPS quality. Owner/location details remain searchable
  and available inside the record, but are not shown on activity cards.
- A fixed top-right overflow (three-dot) menu keeps **Settings & privacy**
  accessible as its first item even after scrolling a long list.
- County is no longer a filter. Area size supports **=**, **>** and **<** a
  numeric threshold in square metres, hectares, acres or square kilometres.
  Comparisons convert completed results into the selected unit and round to
  four decimal places, regardless of each record's display unit. Unmeasured
  drafts do not match size comparisons. Updated on or after/before filters
  are optional and include the whole selected day.
- Previewed Android text sharing; coordinates are excluded unless selected.
  Owner contacts, identifiers and consent notes are not included.
- Configurable capture defaults, independent distance units, haptic/audio
  feedback controls, and resource-based English UI strings.
- **Display mode** defaults to **Use phone setting**, following the phone's
  light/dark configuration automatically. Optional Light and Dark overrides
  persist across restarts. Apply a choice with **Save settings**.
- GPSLandMeasure branding with an original parcel-and-location-pin launcher
  icon, including adaptive and monochrome variants.
- Revisit a saved record and choose **Navigate to land**, then any waypoint,
  to open Google Maps directions to that selected point. A paused walk offers
  directions in each point's action menu. Confirmation shares only the chosen
  coordinates, never owner/contact details. Maps opens first, with a browser
  fallback and an explicit error if neither is available. The external service
  may require internet; measurement and saved records remain offline.

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

- 38 JVM/Robolectric tests passed: geodesic reference fixtures, equator/high
  latitude/antimeridian, invalid rings, a 10,000-vertex ring, capture filtering,
  recovery/reboot timing, transactional fault injection, foreign keys,
  immutable snapshots, revisions, retention, search and combined filters;
  phone-theme selection, persisted theme/legacy provenance, selected-waypoint
  URL precision under comma-decimal locales and Maps/browser fallback failures;
  paging through 503 equally sorted records without gaps, clamping after
  deletion, empty filtered pages, and cross-unit rounded area comparisons.
- Seven instrumentation tests passed on **Android 7.0/API 24**.
  They exercise draft UI autosave, review and
  completion UI, real device SQLite/recovery/filtering, foreground service GPS
  delivery, mock indication and stopping point acceptance after Pause. They also
  verify the seven-input form preserves legacy metadata and capture choices,
  selecting a non-first saved waypoint, and persistent light/dark overrides.
  List regressions exercise ten-item pages, recreation, overflow access after
  scrolling, compact dates/cards, cross-unit size filtering, and a complete
  reset of advanced criteria, search, view, sorting and pagination.
- On **Android 17/API 37**, three of seven instrumentation tests passed.
  Four UI tests timed out while emulator Quickstep/System UI "not responding"
  dialogs obstructed popup and filter access, including after resetting the
  disposable AVD. Full API 37 UI verification remains incomplete for 1.2.0;
  these failures are retained in the local test evidence.
- Earlier 1.1.0 validation on API 37 covered automatic phone-theme changes.
  An in-place installation of 1.1.0 over version 1.0.0 preserved a fixture
  record, legacy metadata, original app provenance and all four waypoints;
  database integrity and schema version 1 were retained.
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

Search and filters are applied before pagination, with 10 matches per page
and no 500-match UI cutoff. Recently Deleted cleanup runs on the next app startup after
the 30-day expiry. Uninstalling removes local records; there is no backup yet.

**All results are estimates, not legal boundaries or certified surveys.**
