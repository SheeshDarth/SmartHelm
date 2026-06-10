# SmartHelm — Full Project Context

> **Status (June 2026):** The physical helmet hardware (Raspberry Pi + ESP32-CAM + MAX30102)
> is **not currently available to the team**. Active development this cycle focuses on the two
> software products that run without it: the **Android driver app** (phone front camera does the
> detection) and the **Fleet Manager dashboard** (real-time web app on Firebase). The Pi/helmet
> path remains in the codebase and is documented below as the hardware track for when it returns.
>
> For the current feature plan see **[PRD.md](PRD.md)** (product requirements) and
> **[TRD.md](TRD.md)** (technical requirements). For the architecture map run `graphify` →
> `graphify-out/graph.html`.

---

## What Is This

SmartHelm is an AI-powered **drowsiness detection and rider-safety system for two-wheeler gig
delivery riders** (Swiggy, Zomato, Zepto, logistics). It detects when a rider is falling asleep in
real time from a rider-facing camera, fires an on-device audio/vibration alert, and notifies the
fleet manager and an emergency contact by SMS — all while keeping the rider's face on the device
and sending **only an eye-region strip** to the cloud for privacy.

It exists in two deployment forms that share one Firestore schema and one dashboard:

| Form | Camera | Compute | Status |
|---|---|---|---|
| **Android app** (driver's phone) | Front camera (CameraX) | On-phone MediaPipe | **Active focus** |
| **Smart helmet** (on the bike) | ESP32-CAM inside helmet | Raspberry Pi 4 | Hardware track (paused — no hardware) |

Built for: **Unisys Innovation Program (UIP) Year 17** — theme *Connected World: IoT, Edge
Computing & AI*. Judging axes: feasibility, creativity, technical excellence, impact.

Active repository: https://github.com/SheeshDarth/SmartHelm

---

## The Problem

India loses ~9 two-wheeler riders every hour; rider deaths nearly doubled in a decade to ~173,000
road deaths/year. Gig delivery riders work 10–16 hour shifts under ten-minute-delivery pressure and
often don't realise they're drowsy until it's too late. No affordable, **rider-specific** early-warning
product serves them — every competitor (Cautio, Netradyne, LightMetrics, Samsara, NAYAN AI,
SafetyConnect, …) is built for the four-wheeler cabin or for compliance enforcement, not the rider.
See the competitive analysis in `docs/SmartHelm_Unisys_Innovation_Report.pdf`.

---

## System Architecture (current)

```
┌─────────────────────────────┐         ┌──────────────────────────────┐
│  ANDROID DRIVER APP          │         │  SMART HELMET (hardware track)│
│  Kotlin · CameraX · MediaPipe│         │  Pi 4 · ESP32-CAM · MAX30102  │
│                              │         │                              │
│  EyeDetector (full face,EAR) │         │  detector.py (MediaPipe,EAR) │
│  PerclosTracker              │         │  perclos.py                  │
│  AlertManager (MSG91 SMS,    │         │  alerts.py (beep + SIM800L)  │
│    SoundPool, Vibrate)       │         │  firestore_reporter.py       │
│  OverlayManager (widget)     │         │  firestore_sms_bridge.py     │
│  FirestoreReporter           │         │  (HR/SpO2 biometrics)        │
│    · full-face → detect      │         └──────────────┬───────────────┘
│    · eye-strip 180×72 → cloud│                        │
│  FusedLocation → GeoPoint    │                        │
└───────────────┬──────────────┘                        │
                │   both write riders/{deviceId}         │
                └───────────────┬────────────────────────┘
                                ▼
                     ┌──────────────────────┐
                     │  FIREBASE FIRESTORE   │  riders/{deviceId} (flat doc)
                     │  + Cloud Function     │  + alerts/ subcollection
                     │    (MSG91 on smsNeeded)│
                     └──────────┬────────────┘
                                ▼
                     ┌──────────────────────┐
                     │  FLEET DASHBOARD      │  Vanilla JS + Firebase SDK v9
                     │  index.html (live)    │  onSnapshot → rider cards
                     │  login/portal/rider   │  eye-strip feed, alert banner,
                     │  Firebase Hosting     │  HR/SpO2, browser notifications
                     └──────────────────────┘
```

### Detection pipeline (per frame)
```
Camera frame (front, 480×360)
  → MediaPipe FaceLandmarker (468 landmarks, full face, VIDEO mode)
  → 6 eye landmarks/eye → EAR = (||P2-P6|| + ||P3-P5||) / (2·||P1-P4||)
  → eye-state: EAR>0.25 OPEN · EAR<0.20 CLOSED · between UNKNOWN (5-frame smooth)
  → PerclosTracker (rolling 60 s window)
  → alert if  PERCLOS > 30%  OR  continuous closure ≥ 1.5 s
  → AlertManager: beep + vibrate + MSG91 SMS
  → FirestoreReporter: status every 2 s (immediate on alert edge);
       full frame used for detection, ONLY eye-strip JPEG pushed to cloud
```

---

## Firestore Schema — `riders/{deviceId}` (flat document)

| Field | Type | Written by | Notes |
|---|---|---|---|
| `riderName`, `managerId`, `emergencyContact` | string | app/Pi | identity + routing |
| `eyeState` | string | app/Pi | OPEN / CLOSED / UNKNOWN |
| `perclos`, `continuousClosureSec` | number | app/Pi | drowsiness metrics |
| `alertActive` | bool | app/Pi | current alert state |
| `alertType` | string | app/Pi | PERCLOS / CONTINUOUS_CLOSURE |
| `faceDetected` | bool | app/Pi | tracking presence |
| `eyeLandmarksLeft/Right` | number[] | app/Pi | flat [x,y,…] for dashboard overlay |
| `snapshot` | string (b64) | app/Pi | **eye-strip only** 180×72 JPEG |
| `location` | GeoPoint | app | **already pushed** — see advancement #1 |
| `heartRate`, `spo2` | number | Pi only | MAX30102 biometrics |
| `connected`, `updatedAt` | bool / ts | app/Pi | liveness; dashboard marks stale > 30 s |
| `smsNeeded` | bool | app/Pi | edge-flag → Cloud Function MSG91 |
| `alerts/{id}` (subcollection) | — | app/Pi | append-only alert log w/ location |

Planned additions (see TRD.md): `speedKmph`, `tripActive`/`tripId`/`tripStartedAt`, `appState`,
`safetyConcern`, `fatigueScore`/`fatigueBreakdown`, `yawnCount`/`blinkRate`,
`ridingScore`/`streakDays`, `dashcamClipUrl`.

---

## Repository Layout

```
SmartHelm/
├── HelmNet/
│   ├── mobile/android/            ← Android driver app (Kotlin)
│   │   └── app/src/main/java/com/smarthelm/mobile/
│   │       ├── LoginActivity / SetupActivity / MainActivity / CalibrationActivity
│   │       ├── SmartHelmApp.kt            ← notification channels
│   │       ├── service/DetectionService.kt ← LifecycleService orchestrator
│   │       ├── detection/EyeDetector · PerclosTracker · DetectionResult
│   │       ├── alert/AlertManager.kt       ← MSG91 SMS + SoundPool + Vibrate
│   │       ├── overlay/OverlayManager.kt   ← floating widget
│   │       ├── cloud/FirestoreReporter.kt  ← eye-strip + location push
│   │       └── util/Prefs.kt
│   ├── fleet-dashboard/           ← Firebase web dashboard
│   │   ├── index.html (live fleet) · login.html · portal.html · rider.html
│   │   ├── app.js · auth.js · rider.js
│   │   ├── functions/index.js     ← Cloud Function: MSG91 on smsNeeded
│   │   ├── firebase.json · firestore.rules · firestore.indexes.json
│   │   └── firebase.config.js      (gitignored)
│   ├── smarthelm/                 ← Pi backend (hardware track)
│   │   └── backend/ app.py · detector.py · perclos.py · alerts.py · streams.py
│   │       · mqtt_client.py · firestore_reporter.py · firestore_sms_bridge.py · config.py
│   ├── ARCHITECTURE.md · README.md · context.md (this) · PRD.md · TRD.md
│   └── SmartHelm Pitch Deck.html
├── docs/                          ← reports (Unisys PDF + generator)
└── graphify-out/                  ← knowledge graph (graph.html, GRAPH_REPORT.md)
```

---

## Alerting Paths (current)

| Path | Mechanism | Status | Use |
|---|---|---|---|
| Android SMS | `AlertManager.sendViaMSG91()` → MSG91 v5 (route 4, bypasses DND) | **working** | primary, no server needed |
| Cloud Function SMS | `functions/index.js` on `smsNeeded` false→true | needs Blaze | server-side backup |
| Pi SMS | `firestore_sms_bridge.py` → SIM800L GSM | hardware track | offline helmet |
| Dashboard alert | browser Notification API + pulsing banner, edge-triggered | working | manager screen |
| On-device | SoundPool beep (STREAM_ALARM) + vibration waveform | working | rider |

> MSG91 credentials live only in `local.properties` (Android, gitignored) and Firebase Secrets.
> Never commit `MSG91_AUTH_KEY`, `google-services.json`, or `firebase.config.js`.

---

## Permissions (AndroidManifest)

CAMERA · FOREGROUND_SERVICE(_CAMERA/_LOCATION) · SYSTEM_ALERT_WINDOW · VIBRATE · SEND_SMS ·
POST_NOTIFICATIONS · INTERNET · ACCESS_FINE/COARSE_LOCATION. Front camera `required=true`.
(Back-camera dashcam — advancement #3 — will relax the front-only feature flag.)

---

## Build & Run

### Android
```
# local.properties must contain MSG91_AUTH_KEY + MSG91_SENDER_ID
# app/google-services.json from Firebase console (gitignored)
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Fleet dashboard
```
cd HelmNet/fleet-dashboard
# firebase.config.js from template (.example) — fill Firebase web config
firebase deploy --only hosting --account siddharthprashoo@gmail.com
# Firebase project: smarthelm-99225
```

### Pi backend (hardware track — see README.md)
```
pip install -r HelmNet/smarthelm/requirements.txt   # incl. firebase-admin
python HelmNet/smarthelm/backend/app.py             # http://localhost:5000
```

---

## Detection thresholds (defaults — tune per rider via CalibrationActivity)

```
EAR_OPEN = 0.25 · EAR_CLOSED = 0.20 · smoothing 5 frames
PERCLOS_THRESHOLD = 30%  over 60 s window
CLOSED_DURATION_THRESHOLD = 1.5 s
SMS cooldown = 5 min
```

---

## Known Limitations (current) & how the roadmap addresses them

| Limitation | Addressed by |
|---|---|
| Location captured but **not shown on a map** | Advancement #1 (dashboard map) — PRD F1 |
| No detection that the **app was killed mid-trip** | Advancement #2 (trip-safety) — PRD F2 |
| Back camera unused (no dashcam) | Advancement #3 — PRD F3 |
| Natural yawns / squints can read as drowsiness | Advancement #4 (multimodal FatigueScorer) — PRD F4 |
| SMS/notification path not consolidated | Advancement #5 — PRD F5 |
| Privacy crop exists but not hardened/verified | Advancement #6 — PRD F6 |
| No per-rider safe-riding score | Planned F7 (gamification) |
| Biometric HRV moat | Deferred — needs helmet MAX30102 hardware |

---

## Team & History

Team project (HelmNet). Original Pi prototype contact: Vishnu K. Current app + dashboard +
cloud + competitive/report work: Siddharth Prashood (siddharthprashoo@gmail.com).
Detection device for app testing: Samsung Galaxy S23.
