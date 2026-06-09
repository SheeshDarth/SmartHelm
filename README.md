# SmartHelm

AI-powered drowsiness detection system for motorcycle delivery riders. Detects eye closure in real time using MediaPipe FaceLandmarker, fires on-device alerts, and streams live data to a fleet manager dashboard — all with edge-only video processing (no raw face footage leaves the device).

---

## System Overview

```
┌─────────────────────────────┐     ┌──────────────────────────────┐
│   Helmet (Raspberry Pi 4)   │     │   Rider Phone (Android App)  │
│                             │     │                              │
│  Pi Camera → MediaPipe      │     │  Front Camera → MediaPipe    │
│  MAX30102  → HR + SpO2      │     │  PERCLOS tracker             │
│  SIM800L  → SMS fallback    │     │  Firebase Phone Auth login   │
│  GPIO17   → Buzzer          │     │  Floating overlay widget     │
└────────────┬────────────────┘     └───────────┬──────────────────┘
             │  Eye state, PERCLOS, HR, SpO2     │  Eye state, PERCLOS
             │  Eye-region JPEG only (no face)   │  Eye-region JPEG only
             └──────────────┬────────────────────┘
                            ▼
                    Firebase Firestore
                    riders/{deviceId}
                            │
                     Cloud Function
                     MSG91 SMS (DND-bypass)
                            │
                            ▼
              ┌─────────────────────────┐
              │   Fleet Dashboard       │
              │   (browser, any device) │
              │   Real-time rider grid  │
              │   Eye-strip live feed   │
              │   Alert history         │
              └─────────────────────────┘
```

---

## Repository Structure

```
HelmNet/
├── smarthelm/               # Raspberry Pi backend (Python/Flask)
│   ├── backend/
│   │   ├── app.py           # Flask server + inference orchestrator
│   │   ├── detector.py      # MediaPipe EAR eye detection
│   │   ├── perclos.py       # 60s rolling PERCLOS tracker
│   │   ├── sensor.py        # MAX30102 HR + SpO2 (I2C)
│   │   ├── alerts.py        # GPIO buzzer + SMS via SIM800L
│   │   ├── streams.py       # Pi Camera + ESP32-CAM stream wrappers
│   │   ├── firestore_reporter.py  # Pi → Firestore publisher
│   │   └── config.py        # All tunable constants
│   ├── dashboard/           # Local Pi dashboard (Flask templates)
│   └── firmware/            # ESP32-CAM Arduino sketches
├── mobile/android/          # Android app (Kotlin)
│   └── app/src/main/java/com/smarthelm/mobile/
│       ├── detection/       # EyeDetector, PerclosTracker, DetectionResult
│       ├── cloud/           # FirestoreReporter — Firestore publisher
│       ├── alert/           # AlertManager — beep + vibration + MSG91
│       ├── service/         # DetectionService — foreground service
│       └── overlay/         # OverlayManager — floating widget
└── fleet-dashboard/         # Web fleet manager dashboard
    ├── index.html           # Real-time rider grid (Firestore onSnapshot)
    ├── functions/           # Firebase Cloud Functions (MSG91 SMS)
    └── firestore.rules      # Security rules
```

---

## Quick Start

### Raspberry Pi Helmet

```bash
# Clone and set up
git clone https://github.com/SheeshDarth/SmartHelm.git
cd SmartHelm/HelmNet
./setup_rpi.sh

# Configure
cp smarthelm/backend/config.py.example smarthelm/backend/config.py
# Edit HELMET_ID, EMERGENCY_CONTACT, FLEET_MANAGER_PHONE

# Run
source ~/smarthelm-venv/bin/activate
sudo python smarthelm/backend/app.py
# Dashboard: http://<pi-ip>:5000
```

### Android App

1. Download `face_landmarker.task` (~3.6 MB) from [MediaPipe Models](https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task) → place at `mobile/android/app/src/main/assets/`
2. Copy `google-services.json.example` → `google-services.json` (fill in your Firebase project values)
3. Copy `local.properties.example` → `local.properties` (add MSG91 credentials)
4. Build: `.\gradlew.bat assembleDebug`

### Fleet Dashboard

```bash
cd HelmNet/fleet-dashboard
cp firebase.config.js.example firebase.config.js   # fill in Firebase config
# Open index.html in a browser, or:
firebase serve
```

---

## Key Design Decisions

| Decision | Reason |
|---|---|
| **Edge-only AI** | Raw video never leaves the device — DPDP Act 2023 compliant |
| **Eye-region crop only** | 180×72 px strip replaces full face — privacy + lower bandwidth |
| **MSG91 route 4 (transactional)** | Bypasses India's DND registry — alerts actually arrive |
| **Firebase Cloud Functions for SMS** | API key never in APK; server-side retry |
| **Firestore flat document** | Single `onSnapshot` per rider — minimal reads, real-time |
| **MediaPipe VIDEO mode** | Temporal tracking across frames — fewer false positives |
| **Biometric fusion (Pi)** | EAR + HR + SpO2 → earlier detection than camera alone |

---

## Hardware (Pi Helmet)

| Component | Purpose |
|---|---|
| Raspberry Pi 4 Model B | Main compute |
| Pi Camera OV5647 (CSI) | Rider face — eye detection |
| ESP32-CAM OV2640 × 2 | Front/rear road view — incident recording |
| MAX30102 | Heart rate + SpO2 (I2C 0x57) |
| SIM800L | GSM SMS fallback (no internet needed) |
| GPIO17 + NPN buzzer | Audio alert |

---

## Privacy Architecture

```
What NEVER leaves the helmet / phone:
  ✗  Raw video frames
  ✗  Full face images
  ✗  468-point face landmark coordinates

What goes to Firestore (metadata only):
  ✓  Eye state (OPEN / CLOSED — not who)
  ✓  PERCLOS % number
  ✓  Heart rate BPM (Pi only)
  ✓  Eye-region JPEG strip (180×72 px, eyes only)
  ✓  GPS coordinates
  ✓  Alert events (timestamp + type)
```

---

## License

MIT — see [LICENSE](LICENSE)
