# SmartHelm — Technical Requirements Document (TRD)

**Version:** 1.0 · **Date:** 10 June 2026
**Implements:** [PRD.md](PRD.md) features F1–F7 · **System state:** [context.md](context.md)
**Scope:** Android driver app + Firebase fleet dashboard (helmet/Pi track paused; HRV deferred).

This document maps each PRD feature to concrete files, data-model changes, algorithms, and the
phase it ships in. File paths are relative to `HelmNet/`.

---

## 1. Data model changes (Firestore `riders/{deviceId}`)

New/changed fields (additive — dashboard and app both tolerate missing fields):

| Field | Type | Writer | Feature |
|---|---|---|---|
| `location` | GeoPoint | app | F1 — **already written** by `FirestoreReporter.onFrame()` |
| `speedKmph` | number | app | F1/F4 — from FusedLocation `Location.speed` |
| `heading` | number | app | F1 — optional marker bearing |
| `tripActive` | bool | app | F2 |
| `tripId` | string | app | F2 — uuid per trip |
| `tripStartedAt` | timestamp | app | F2 |
| `appState` | string | app | F2 — FOREGROUND / BACKGROUND / ENDED |
| `lastHeartbeatAt` | timestamp | app | F2 — distinct from `updatedAt` |
| `safetyConcern` | map `{active,reason,since}` | Cloud Fn or dashboard-derived | F2 |
| `fatigueScore` | number (0–100) | app | F4 |
| `fatigueBreakdown` | map `{ear,perclos,yawn,headPose}` | app | F4 |
| `yawnCount` | number | app | F4 |
| `blinkRate` | number (/min) | app | F4 |
| `dashcamClipUrl` | string | app | F3 — Firebase Storage URL |
| `ridingScore` | number (0–100) | app/Cloud Fn | F7 |
| `streakDays` | number | app/Cloud Fn | F7 |

New subcollection (F1 trail / F2 forensics): `riders/{id}/track/{autoId}` = `{location, speedKmph, ts}`
written at a low rate (e.g. every 10 s while `tripActive`), TTL-pruned. Keeps the main doc small.

Security rules (`fleet-dashboard/firestore.rules`): extend `isValidRiderDoc()` to allow the new fields,
keep device-scoped writes, and make `track/` append-only by the owning device. `safetyConcern.active`
should only be settable by the Cloud Function / trusted path, not spoofable by a device.

---

## 2. F1 — Live location on a fleet map  (Phase 2)

**Backend:** none new — `location` already syncs. Add `speedKmph`/`heading` in
`mobile/.../cloud/FirestoreReporter.kt` `onFrame()` status map (read from the existing
`FusedLocationProviderClient` callback in `service/DetectionService.kt`; extend `setLastLocation()`
to `setLastLocation(lat, lng, speed, bearing)`).

**Frontend (`fleet-dashboard/index.html`):**
- Add **Leaflet** (`cdnjs`) + OpenStreetMap tiles — **no API key, no billing**.
- New map panel; maintain a `Map<deviceId, L.marker>`; in the existing `injectLiveData()` /
  `onSnapshot` handler, upsert each rider's marker from `data.location` (`GeoPoint{latitude,longitude}`).
- Marker icon colour from the same state logic already used for cards (`alertStage`/`alertActive`):
  green normal, amber caution, red alert, grey stale (`connected===false || now-updatedAt>30s`).
- Popup: name, eyeState, PERCLOS, `speedKmph`, last-update age; `marker.on('click')` → scroll/focus
  `#card-${slot}` (reuse `getOrAssignSlot`).
- Breadcrumb: on rider select, query `track/` (last ~30 pts) → `L.polyline`.
- Fit bounds to active markers; debounce so manual pan isn't fought.

**Why Leaflet/OSM:** zero credentials, works offline-ish, no Google Maps billing risk for a student project.

---

## 3. F2 — App-off-mid-trip safety concern  (Phase 2)

**Concept:** separate *graceful end* from *disappearance*. Three liveness signals already/now exist:
`connected` (set false by `FirestoreReporter.flush()` on graceful stop), `updatedAt` (server ts on
each push), and new `lastHeartbeatAt`.

**App (`mobile/...`):**
- New `trip/TripManager.kt`: `startTrip()` (uuid, `tripActive=true`, `tripStartedAt`), `endTrip()`
  (graceful: `appState=ENDED`, `tripActive=false`, then `flush()`).
- Heartbeat: while `tripActive`, `DetectionService` writes `lastHeartbeatAt`/`appState` on a fixed
  cadence (e.g. every 15 s) even if detection is idle.
- `appState` via `ProcessLifecycleOwner` (FOREGROUND/BACKGROUND). A user *ending the trip* is graceful;
  a *force-stop/kill/dead battery* simply stops heartbeats with `tripActive` still true and **no** `ENDED`.
- Trip start/stop UI: a button in `MainActivity` (and auto-start option when detection starts).

**Detection of concern (server-side, robust):** add to `fleet-dashboard/functions/index.js` a scheduled
function (Cloud Scheduler, every ~60 s) that scans riders where
`tripActive==true && appState!='ENDED' && now-lastHeartbeatAt > THRESHOLD(90s)` and sets
`safetyConcern = {active:true, reason:'app_silent_mid_trip', since:now}`. Clears it when heartbeats resume.
*(Fallback without Blaze: derive the concern client-side in the dashboard from the same fields so the
demo works without a deployed function; the Cloud Function is the production path.)*

**Dashboard (`index.html`):**
- New **distinct state**: "Safety concern" (amber, pulsing, icon) — separate from drowsiness-red and
  offline-grey. Render on the card + a top banner + map marker halo at last known location.
- Manager notification (browser Notification + optional FCM) on concern edge false→true.
- Optional SMS to emergency contact on sustained concern (reuse MSG91 path, longer cooldown).

**False-positive control:** concern is driven by **heartbeat silence**, not GPS loss — a rider parked
under a flyover still heartbeats, so no concern. Threshold + require `tripActive`.

---

## 4. F3 — Rear camera dashcam  (Phase 3, stretch)

**Constraint:** Android concurrent front+back camera is device-limited
(`CameraManager.getConcurrentCameraIds()` on Camera2; CameraX concurrent-camera API is restricted).
Detection (front) must keep priority.

**Design (`mobile/.../dashcam/DashcamRecorder.kt`):**
- CameraX `VideoCapture` on the back lens with a **circular/rolling buffer** (segment recording, keep
  last ~30 s). Prefer a segmented `Recorder` that rotates files and discards old segments.
- Triggered flush on: `AlertManager.trigger()` (drowsiness), manual SOS button, or hard-motion
  (optional, via `SensorManager` accelerometer) → finalize clip [t-15s, t+15s].
- Upload **only the triggered clip** to Firebase Storage; write `dashcamClipUrl` on the rider doc.
- Dashboard `rider.html`: show clip link in alert history.
- Device gate: if concurrent cameras unsupported, offer a "dashcam mode" that uses back camera while
  detection pauses, OR mark dashcam unavailable — never break detection. Manifest: relax
  `camera.front required=true` → `required=false` and add back-camera handling.

---

## 5. F4 — Robust multimodal drowsiness  (Phase 1, core)

The heart of the hackathon build. Replace the binary trigger with a fused, debounced score.

**New `detection/FatigueScorer.kt`** consumes per-frame signals and outputs `fatigueScore` 0–100 +
breakdown + a confirmed `alertActive`.

**Signals & extraction (all from the existing full-face MediaPipe landmarks in `EyeDetector.kt`):**
1. **Eye closure** — existing EAR + `PerclosTracker` (PERCLOS%, continuous closure s).
2. **Yawn** — new `YawnDetector`: mouth-aspect-ratio (MAR) from lip landmarks (e.g. 13/14 vertical,
   61/291 horizontal). A yawn = MAR > thresh sustained ~1 s; count yawns over a rolling 2-min window.
3. **Head nod** — new `HeadPoseEstimator`: pitch from `FaceLandmarkerResult.facialTransformationMatrixes`
   (enable `outputFacialTransformationMatrixes(true)`), or approximate from nose/chin/forehead landmarks.
   Sustained downward pitch = nodding off.
4. **Blink rate** — derive from EAR zero-crossings; abnormally high/low blink rate weights the score.

**Fusion & false-alarm rejection (the key requirement):**
- Weighted score, e.g. `score = wP·perclosNorm + wC·closureNorm + wY·yawnRateNorm + wH·nodNorm`
  with weights in a `config` object (tunable, calibratable).
- **Hysteresis**: enter-alert threshold > exit-alert threshold (e.g. 70 / 45) to stop flapping.
- **Confirmation window**: `alertActive` only after the score stays above enter-threshold for
  N consecutive frames / ~T seconds — kills single-spike false positives (one blink, one yawn, a squint).
- **Blink vs drowsy**: blinks are < ~400 ms; only closures beyond the blink band feed closure weight.
- **Squint handling**: a *stable, partially-lowered* EAR (eyes not fully closed, low variance) is treated
  as squint, not closure (bright-light tolerance). UNKNOWN frames never inflate PERCLOS (already true).
- **Yawn semantics**: a single yawn never alone alerts; it raises `yawnCount` and adds weight — repeated
  yawning + nodding/closure does.
- **Speed-gating**: read `Location.speed`; if `speedKmph < ~5`, suppress alert (rider stopped) while
  still logging fatigue — prevents red-light false alarms. Configurable.
- **Per-rider calibration**: `CalibrationActivity` (already scaffolded) sets personal EAR open/closed and
  MAR baselines in `util/Prefs.kt`; FatigueScorer reads them.

**Wiring:** `service/DetectionService.kt` analyze loop → `EyeDetector.process()` →
`YawnDetector` + `HeadPoseEstimator` + `PerclosTracker` → `FatigueScorer.update(...)` →
`AlertManager` (on confirmed alert) + `OverlayManager.update(score)` + `FirestoreReporter`
(`fatigueScore`, `fatigueBreakdown`, `yawnCount`, `blinkRate`).
`DetectionResult`/`PerclosResult` gain the new fields (extend the existing data classes; `alertType`
already exists).

**Tests:** extend `smarthelm/tests/test_perclos.py`-style unit tests with a Kotlin/JVM test (or a Python
mirror) covering: 5 blinks → no alert; 1 yawn → no alert, count=1; sustained closure → alert; squint
(stable low EAR) → no alert; stationary closure → no alert (speed-gated).

---

## 6. F5 — Most-feasible SMS + notifications  (Phase 1/2)

**Decision: MSG91 transactional (route 4) sent directly from the Android app is the primary path** —
it bypasses Indian DND, needs no server and no Firebase Blaze plan, and is already implemented in
`alert/AlertManager.sendViaMSG91()`. Order of paths:

1. **Primary** — `AlertManager.sendViaMSG91()` → MSG91 v5 `https://control.msg91.com/api/v5/sms`,
   route 4, recipients = dedup(managerPhone, emergencyContact), 5-min cooldown. Credentials from
   `BuildConfig` (injected from `local.properties`).
2. **Manager push** — dashboard browser Notification (exists, edge-triggered). P1: add **FCM** —
   `firebase-messaging` in the app + a topic per `managerId`; Cloud Function publishes on alert.
3. **Server backup** — `functions/index.js` already sends MSG91 on `smsNeeded` false→true (enable when
   on Blaze); keeps secrets in Firebase Secrets.
4. **Offline (helmet track)** — `firestore_sms_bridge.py` → SIM800L.

**Dedup/cooldown:** single SMS per event per recipient per 5 min; the `smsNeeded` edge-flag prevents
re-sends; reset after send. F2 safety-concern SMS uses a separate, longer cooldown.

---

## 7. F6 — Full-face detect, eye-only to dashboard  (Phase 1, verify+harden)

**Already implemented** in `cloud/FirestoreReporter.kt`: detection uses full-face landmarks; the
snapshot pipeline rotates → transforms landmarks → draws overlay → **crops eye strip only**
(`cropEyeStrip`, 180×72 @ 55%) → pushes. No full face is sent in the normal path.

**Hardening (the only gap):**
- The **no-face fallback** currently sends an 80×60 @ 28% frame (`NO_FACE_*`) which *could* contain a
  recognisable face. Change it to push a **non-identifying placeholder** (solid/blurred tile or nothing)
  so a face can never leak when tracking is lost.
- Add an assertion/comment that `snapshot` is only ever an eye-strip or placeholder.
- Document the guarantee in context.md + pitch (DPDP Act alignment) — done in context.md.

**Acceptance check:** inspect Firestore `snapshot` field across states (face/no-face) — never a full face.

---

## 8. F7 — Safe-riding score + streaks  (Phase 4)

- Compute per-rider `ridingScore` (0–100) from: alerts/hour, average `fatigueScore`, speed-behaviour,
  trip completion. A nightly Cloud Function (or client aggregation) writes `ridingScore` + `streakDays`.
- Dashboard: score chip on each card + a simple sortable leaderboard; `rider.html` shows trend.

---

## 9. Phase / file change summary

| Phase | New files | Modified files |
|---|---|---|
| **1** (F4,F6,F5a) | `detection/FatigueScorer.kt`, `detection/YawnDetector.kt`, `detection/HeadPoseEstimator.kt` | `EyeDetector.kt`, `DetectionResult.kt`, `service/DetectionService.kt`, `alert/AlertManager.kt`, `cloud/FirestoreReporter.kt` (harden no-face), `util/Prefs.kt`, `CalibrationActivity.kt` |
| **2** (F1,F2,F5b) | `trip/TripManager.kt`, dashboard map module | `FirestoreReporter.kt` (speed/heading/heartbeat/appState), `DetectionService.kt`, `MainActivity.kt`, `fleet-dashboard/index.html` (Leaflet map + safety-concern state), `functions/index.js` (scheduled concern scan), `firestore.rules`, `firestore.indexes.json` |
| **3** (F3) | `dashcam/DashcamRecorder.kt` | `AndroidManifest.xml` (back camera), `DetectionService.kt`, `rider.html` |
| **4** (F7) | nightly scoring function | `index.html`, `rider.html` |

## 10. Cross-cutting

- **Config**: put all thresholds (fatigue weights, hysteresis, confirmation N, speed gate, concern
  threshold, cooldowns) in one Kotlin config object / `Prefs` so nothing is a magic number and per-rider
  calibration can override.
- **Battery**: heartbeats + location at modest cadence; reuse the single FusedLocation stream; keep the
  existing thermal frame-skip.
- **Backward compatibility**: all Firestore fields additive; the Pi writer and current dashboard keep
  working with missing fields.
- **Secrets**: MSG91 key, `google-services.json`, `firebase.config.js` stay gitignored.
