# SmartHelm — Product Requirements Document (PRD)

**Version:** 1.0 · **Date:** 10 June 2026 · **Owner:** Siddharth Prashood
**Context:** Unisys Innovation Program Y17 · helmet hardware unavailable → scope = **Android driver app + Fleet dashboard**
**Companion doc:** [TRD.md](TRD.md) (technical design) · [context.md](context.md) (system state)

---

## 1. Vision

Make the two-wheeler gig delivery rider the *safest* worker on the road — by detecting fatigue
before it becomes dangerous, knowing where every rider is, raising the alarm when a rider goes
silent, and doing all of it **on the rider's own phone, without uploading their face to the cloud.**

## 2. Users

| Persona | Goal | Primary surface |
|---|---|---|
| **Driver** (gig rider) | Stay safe, get warned before microsleep, not be falsely nagged | Android app + overlay widget |
| **Fleet manager** | See all riders live, get alerted to drowsiness *and* to a rider in trouble, prove safety | Web dashboard |
| **Emergency contact** | Be told fast if their rider is in danger | SMS |

## 3. Design principles

1. **Edge-first & private** — full face never leaves the device; only an eye-strip + numbers sync.
2. **Few false alarms** — a system that cries wolf gets uninstalled. Natural blinks/yawns/squints must not trigger alerts.
3. **Works on a bad network** — alerts degrade gracefully (SMS, on-device) when data is poor.
4. **Zero extra hardware** — everything runs on a mid-range Android phone the rider already owns.

## 4. Non-goals (this cycle)

- Helmet/Pi hardware integration and **HRV biometric fusion** (deferred — no hardware on hand).
- Continuous cloud video recording (privacy + cost; we send eye-strip + on-demand clips only).
- Native iOS app.

---

## 5. Features

Priority: **P0** = hackathon core (must demo) · **P1** = hackathon stretch · **P2** = post-hackathon.
Each maps to a phase (§7) and to TRD section numbers.

### F1 — Live driver location on a fleet map  · P0 · Phase 2 · TRD §2
**Problem:** Location is already sent to Firestore (`location` GeoPoint) but the manager can't see it.
**User story:** *As a fleet manager, I see every active rider as a live marker on a map, coloured by
their drowsiness state, so I know where my fleet is and who is at risk and where.*
**Requirements**
- Map panel on the dashboard with one marker per live rider; marker colour = state (green/amber/red/grey-offline).
- Marker popup: rider name, eye-state, PERCLOS, speed, last-update age; click → focus that rider's card.
- Recent breadcrumb trail (last N points) per selected rider.
- Auto-pan/fit to active riders; manual pan/zoom retained.
**Acceptance**
- A rider moving in the field updates their marker within ~3 s.
- An alerting rider's marker turns red and the map draws attention to it.
- No Google billing required (use OpenStreetMap tiles via Leaflet).

### F2 — App-off-mid-trip safety concern  · P0 · Phase 2 · TRD §3
**Problem:** If the app is killed or the phone is taken mid-delivery, no one knows. In India there
are real cases of riders being harassed or in distress; a silent app during an active trip is a signal.
**User story:** *As a fleet manager, if a rider's app goes dark while they are on an active trip for
longer than a threshold, I get a distinct "safety concern" (not just "offline"), with their last known
location, so I can check on them.*
**Requirements**
- App has an explicit **trip** concept (start on shift/trip begin, end on trip complete).
- While a trip is active, the app sends a heartbeat; a *graceful* stop marks `appState=ENDED`.
- A non-graceful disappearance (kill, force-stop, dead battery, phone seized) during an active trip,
  lasting > threshold (default 90 s), raises `safetyConcern` with last location + reason.
- Dashboard shows a **distinct amber "Safety concern"** state separate from drowsiness-red and offline-grey, and notifies the manager.
- Optional: SMS to the emergency contact on a sustained concern (cooldowned).
**Acceptance**
- Graceful trip-end ⇒ rider goes "offline", **no** concern raised.
- Force-stopping the app mid-trip ⇒ within ≤ threshold the dashboard shows "Safety concern" + last location.
- False-positive rate acceptable: parking under a flyover (GPS loss) alone does not raise a concern if heartbeats continue.

### F3 — Rear camera as dashcam  · P1/P2 · Phase 3 · TRD §4
**Problem:** Riders need video evidence for accidents/disputes, like Cautio offers — without the privacy/cost of always-on cloud video.
**User story:** *As a rider, my phone's back camera records the road in a rolling buffer and saves a
short clip automatically when an alert or hard event happens, which my manager can review.*
**Requirements**
- Back-camera capture with a rolling buffer (default 30 s).
- Save/flush a clip on: drowsiness alert, manual SOS, or (if available) hard-motion event.
- Clip stored on device; uploaded to Firebase Storage **only** on the triggering event; link in `dashcamClipUrl`.
- Respect concurrent-camera limits: detection (front) keeps priority; document devices where dual-camera isn't supported.
**Acceptance**
- An alert produces a saved clip covering the seconds before+after the event.
- No continuous upload; upload happens only on trigger.

### F4 — Robust drowsiness (ignore natural yawns/blinks/squints)  · P0 · Phase 1 · TRD §5
**Problem:** Single blinks, a yawn, or squinting in sunlight must not be mistaken for drowsiness, but
*repeated* yawning/eye-closure is genuine fatigue.
**User story:** *As a rider, the app only warns me when I'm genuinely drowsy — not every time I blink,
yawn once, or squint into the sun.*
**Requirements**
- Replace the single EAR/PERCLOS trigger with a **multimodal `FatigueScorer` (0–100)** fusing:
  eye-closure (EAR/PERCLOS), **yawn rate** (mouth-aspect-ratio), **head nod** (pitch), blink rate.
- Each signal **debounced + hysteresis**; a single yawn/blink contributes weight but never alone trips an alert.
- **Confirmation window**: alert requires sustained evidence (N consecutive frames / seconds), not a single spike.
- **Speed-gating**: suppress alerts when stationary/very slow (rider stopped, not riding).
- **Per-rider calibration** (CalibrationActivity already scaffolded): personal EAR/MAR baselines.
**Acceptance**
- Demo: 5 normal blinks ⇒ no alert. One yawn ⇒ no alert (yawn count increments). Sustained closure / repeated yawning + nodding ⇒ alert.
- Squinting in bright light (lower EAR but stable, eyes not closed) ⇒ no alert.
- Stationary at a light with eyes closed ⇒ no alert (speed-gated); same closure while moving ⇒ alert.

### F5 — Most-feasible SMS + valid notifications  · P0 · Phase 1/2 · TRD §6
**Problem:** Indian carrier DND blocks ordinary SMS; the path must be reliable and not require paid infra we don't have.
**User story:** *As a manager/emergency contact, I reliably receive an SMS and an in-app/browser
notification when my rider is drowsy or in a safety concern.*
**Requirements**
- **Primary SMS**: MSG91 transactional (route 4, bypasses DND) sent directly from the app (`sendViaMSG91`) — already working, no server/Blaze needed.
- **Manager notification**: dashboard browser Notification (exists) + (P1) Firebase Cloud Messaging push.
- **Server backup**: Cloud Function on `smsNeeded` (when Blaze available).
- **Dedup + cooldown**: one SMS per event per 5 min; recipients deduped (manager + emergency contact).
**Acceptance**
- Triggering an alert delivers an SMS to manager + emergency contact within seconds and a dashboard notification.
- No duplicate SMS storm if the alert flickers.

### F6 — Full-face detection, eye-region-only to dashboard  · P0 (verify) · Phase 1 · TRD §7
**Problem:** Accuracy needs the full face; privacy (DPDP Act) forbids sending faces to the cloud.
**User story:** *As a rider, the app uses my whole face on-device for accurate detection, but my
manager only ever sees my eyes — never my full face.*
**Requirements**
- Detection runs on the **full-face** MediaPipe landmarks on-device (already true).
- Only the **eye-strip** (180×72) is encoded and pushed (already true via `cropEyeStrip`).
- **Harden** the no-face fallback so a recognisable face can never be sent (blur/placeholder instead of an 80×60 face crop).
- Document the privacy guarantee for the pitch (DPDP alignment).
**Acceptance**
- Inspect Firestore: `snapshot` is always an eye-strip or a non-identifying placeholder — never a full face.
- Detection accuracy unchanged (full face still used internally).

### F7 — Safe-riding score + streaks (gamification)  · P1 · Phase 4 · TRD §8
**User story:** *As a manager, each rider has a daily safe-riding score (0–100) and streaks, so I can
coach and reward; as a rider, I see my score and want to keep it up.*
**Requirements:** per-rider score from alert frequency, fatigue trend, speed behaviour; streak of safe days; dashboard card + simple leaderboard.

---

## 6. Success metrics

| Metric | Target |
|---|---|
| False-alarm rate (blinks/yawns/squints) | < 1 per 30 min of normal riding |
| True drowsiness caught | sustained closure ≥ 1.5 s or fatigue score ≥ threshold always alerts |
| Alert → manager visible | < 2 s (dashboard), SMS < ~10 s |
| Safety-concern detection | within threshold (default 90 s) of app going silent mid-trip |
| Privacy | 0 full-face frames in cloud (verified) |

---

## 7. Phased delivery plan

> Helmet hardware is out of scope; **HRV biometric fusion deferred.** Phases ordered by demo value
> and dependency. P0 items (Phase 1–2) are the hackathon-week target.

| Phase | Theme | Features | Why this order |
|---|---|---|---|
| **Phase 1** | Detection robustness & privacy | F4, F6, F5 (SMS half) | Pure on-phone software, zero new hardware/permissions, highest "is it accurate?" judge value. Builds the `FatigueScorer` everything else trusts. |
| **Phase 2** | Location & trip safety | F1, F2, F5 (notify half) | Uses location already in Firestore; the app-off-safety angle is the strongest *impact* differentiator (rider harassment/distress). Map makes the demo vivid. |
| **Phase 3** | Dashcam & evidence | F3 | Heavier (camera concurrency); valuable but stretch — defer if Phase 1–2 slip. |
| **Phase 4** | Fleet intelligence | F7 | Polish; depends on fatigue data from Phase 1. |

**Hackathon-week cut line:** Phase 1 + Phase 2 = the submission. Phase 3–4 = roadmap shown on a slide.

---

## 8. Risks

| Risk | Mitigation |
|---|---|
| Dual-camera (detect + dashcam) unsupported on test device | F3 is P1/P2; detection (front) always wins; document supported devices |
| GPS noise raises false safety-concerns | heartbeat-based (not location-based) trip-silence detection; threshold + hysteresis |
| MSG91 key exposure | keep in local.properties / Firebase Secrets only; never commit |
| Over-tuning FatigueScorer to the demo | keep per-signal thresholds in config; calibrate per rider |
