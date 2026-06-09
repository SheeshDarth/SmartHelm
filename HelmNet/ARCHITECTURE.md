# SmartHelm — System Architecture

```mermaid
flowchart LR

  subgraph HW["Helmet Hardware"]
    C1["ESP32-CAM\nRider Face"]
    C2["ESP32-CAM\nRoad Ahead"]
    C3["ESP32-CAM\nRear Traffic"]
    GSM["SIM800L GSM\nSMS Alerts"]
  end

  subgraph PI["Pi Backend  ·  smarthelm/backend/"]
    STR["streams.py\nMJPEG + Auto-reconnect"]
    DET["detector.py\nMediaPipe 468pt · EAR"]
    PER["perclos.py\n60s Rolling Window"]
    ALE["alerts.py\nBeep · SMS · MQTT"]
    APP["app.py\nFlask :5000 · SharedState"]
  end

  subgraph AND["Android App  ·  mobile/android/"]
    CAM["CameraX 480×360\nFront Camera · YUV"]
    EYE["EyeDetector.kt\nMediaPipe VIDEO · EAR"]
    PRT["PerclosTracker.kt\nKotlin port of perclos.py"]
    ALM["AlertManager.kt\nSoundPool · Vibrate · SMS"]
    OVL["OverlayManager.kt\n22dp dot → Alert Pill"]
    FSR["FirestoreReporter.kt\n5s throttle · edge push"]
  end

  subgraph WEB["Fleet Dashboard  ·  fleet-dashboard/"]
    AUTH["login.html\nGoogle Auth"]
    FLEET["index.html\nReal-time Fleet Table"]
    RIDER["rider.html\nHistory · Chart · CSV"]
  end

  DB[("Firebase\nFirestore\nsmarthelm-99225")]
  PIDASH["Pi Dashboard\nlocalhost:5000"]
  BROKER["MQTT Broker\nlocalhost:1883"]
  EMRG["Emergency\nContact"]

  C1 & C2 & C3 -->|"WiFi MJPEG"| STR
  STR --> DET --> PER --> ALE
  ALE -->|"UART"| GSM
  ALE --> APP --> PIDASH
  APP --> BROKER

  CAM -->|"no Bitmap alloc"| EYE --> PRT
  PRT --> ALM --> EMRG
  PRT --> OVL
  PRT --> FSR --> DB

  AUTH --> FLEET
  DB -->|"onSnapshot\n<2s latency"| FLEET --> RIDER

  style HW  fill:#1a1a2e,stroke:#444,color:#ccc
  style PI  fill:#0d2137,stroke:#1e6091,color:#ccc
  style AND fill:#1a2e1a,stroke:#2d6a2d,color:#ccc
  style WEB fill:#2e1a2e,stroke:#6a2d6a,color:#ccc
  style DB  fill:#2e2a1a,stroke:#8a7a2a,color:#ccc
```

---

## Data Flow Summary

| Source | Destination | Protocol | Rate |
|---|---|---|---|
| ESP32-CAM × 3 | Raspberry Pi | WiFi MJPEG | ~25 FPS |
| Pi inference thread | SharedState | in-process lock | per frame |
| Pi alerts | SIM800L | UART | on alert (5-min cooldown) |
| Pi Flask | Browser | HTTP / MJPEG | on request |
| Pi app.py | MQTT broker | MQTT QoS 1 | 2 Hz |
| Android CameraX | EyeDetector | YUV_420_888 | ~25 FPS |
| Android PerclosTracker | FirestoreReporter | in-process | per frame |
| FirestoreReporter | Firestore | HTTPS | 5s normal / immediate on alert |
| Firestore | Fleet Dashboard | WebSocket (onSnapshot) | <2s latency |

## Component Responsibilities

| Component | File(s) | Responsibility |
|---|---|---|
| **Pi backend** | `app.py` | Flask server, inference thread, SharedState |
| | `detector.py` | MediaPipe FaceLandmarker + EAR calculation |
| | `perclos.py` | PERCLOS rolling window + continuous closure |
| | `alerts.py` | Beep (winsound/aplay) + SMS (SIM800L) |
| | `streams.py` | MJPEG auto-reconnect + webcam fallback |
| | `config.py` | Single source of truth for all constants |
| **Android app** | `DetectionService.kt` | LifecycleService, CameraX, thermal throttle |
| | `EyeDetector.kt` | MediaPipe VIDEO mode, no Bitmap allocation |
| | `PerclosTracker.kt` | Kotlin port of perclos.py |
| | `AlertManager.kt` | SoundPool + Vibration + SMS |
| | `OverlayManager.kt` | Floating 22dp dot → alert pill over other apps |
| | `FirestoreReporter.kt` | Smart-throttled Firestore push |
| | `CalibrationActivity.kt` | 100-frame personal EAR baseline |
| **Fleet dashboard** | `app.js` | Firestore onSnapshot, edge-triggered notifications |
| | `rider.js` | Alert history, SVG chart, CSV export |
| | `auth.js` | Firebase Google sign-in |
