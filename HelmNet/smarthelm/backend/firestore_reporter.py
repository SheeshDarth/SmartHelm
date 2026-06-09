"""
firestore_reporter.py — Publishes Pi helmet data to Firebase Firestore.

Mirrors FirestoreReporter.kt (Android) so Pi-based helmet riders appear
on the fleet dashboard alongside phone-based riders with the same schema.

Schema written to  riders/{helmet_id}:
    riderName, managerId, emergencyContact
    eyeState, perclos, continuousClosureSec, alertActive, faceDetected
    heartRate, spo2                          ← Pi-only biometric fields
    eyeLandmarksLeft, eyeLandmarksRight      ← normalized flat lists
    snapshot                                 ← base64 eye-strip JPEG (180x72)
    connected, updatedAt, smsNeeded

Setup:
    pip install firebase-admin opencv-python-headless
    Place serviceAccountKey.json in the same directory as this file.
    Set HELMET_ID and FLEET_MANAGER_PHONE in config.py.
"""

import base64
import io
import logging
import threading
import time

import cv2
import numpy as np

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Optional Firebase import — module still loads if firebase-admin is absent
# ---------------------------------------------------------------------------
try:
    import firebase_admin
    from firebase_admin import credentials, firestore
    _FIREBASE_AVAILABLE = True
except ImportError:
    _FIREBASE_AVAILABLE = False
    logger.warning("firebase-admin not installed — Firestore reporting disabled")


# ---------------------------------------------------------------------------
# Eye-strip crop constants (matches Android FirestoreReporter)
# ---------------------------------------------------------------------------
SNAPSHOT_W       = 180
SNAPSHOT_H       = 72
SNAPSHOT_QUALITY = 55      # JPEG quality %
SNAPSHOT_INTERVAL_NORMAL = 0.35   # seconds (~3 fps)
SNAPSHOT_INTERVAL_ALERT  = 0.10   # seconds (~10 fps)
STATUS_INTERVAL          = 2.0    # seconds


class FirestoreReporter:
    """
    Thread-safe Firestore publisher for Pi helmet data.
    All Firestore writes happen on a background daemon thread.
    """

    def __init__(self, helmet_id: str, rider_name: str,
                 emergency_contact: str = "", manager_id: str = "",
                 service_account_path: str = "serviceAccountKey.json"):
        self._helmet_id        = helmet_id
        self._rider_name       = rider_name
        self._emergency_contact = emergency_contact
        self._manager_id       = manager_id
        self._enabled          = False

        self._last_status_t   = 0.0
        self._last_snapshot_t = 0.0
        self._prev_alert      = False

        if not _FIREBASE_AVAILABLE:
            return

        try:
            if not firebase_admin._apps:
                cred = credentials.Certificate(service_account_path)
                firebase_admin.initialize_app(cred)
            self._db  = firestore.client()
            self._ref = self._db.collection("riders").document(helmet_id)
            self._enabled = True
            logger.info(f"FirestoreReporter: connected — helmet_id={helmet_id}")
        except Exception as e:
            logger.warning(f"FirestoreReporter: init failed ({e})")

    # ------------------------------------------------------------------
    # Public API — called from app.py inference loop
    # ------------------------------------------------------------------

    def on_frame(self, eye_state: str, perclos: float,
                 continuous_sec: float, alert_active: bool,
                 face_detected: bool, heart_rate: float = 0.0,
                 spo2: float = 0.0,
                 left_eye_norm=None, right_eye_norm=None):
        """Push status to Firestore (throttled to STATUS_INTERVAL)."""
        if not self._enabled:
            return

        now       = time.time()
        alert_edge = alert_active != self._prev_alert
        self._prev_alert = alert_active

        if not alert_edge and (now - self._last_status_t) < STATUS_INTERVAL:
            return
        self._last_status_t = now

        left_flat  = [v for pt in (left_eye_norm  or []) for v in pt]
        right_flat = [v for pt in (right_eye_norm or []) for v in pt]

        data = {
            "riderName":            self._rider_name,
            "managerId":            self._manager_id,
            "emergencyContact":     self._emergency_contact,
            "eyeState":             eye_state,
            "perclos":              round(perclos, 2),
            "continuousClosureSec": round(continuous_sec, 2),
            "alertActive":          alert_active,
            "faceDetected":         face_detected,
            "heartRate":            round(heart_rate, 1),
            "spo2":                 round(spo2, 1),
            "eyeLandmarksLeft":     left_flat,
            "eyeLandmarksRight":    right_flat,
            "connected":            True,
            "updatedAt":            firestore.SERVER_TIMESTAMP,
        }
        if alert_edge:
            data["smsNeeded"] = alert_active   # Cloud Function → MSG91 SMS

        threading.Thread(
            target=self._write, args=(data,), daemon=True, name="fs-status"
        ).start()

    def maybe_update_snapshot(self, frame: np.ndarray,
                              left_eye_norm=None, right_eye_norm=None,
                              alert_active: bool = False):
        """
        Crop to eye strip, encode as JPEG, push to Firestore.
        Frame must be a BGR numpy array (OpenCV format).
        """
        if not self._enabled:
            return

        now      = time.time()
        interval = SNAPSHOT_INTERVAL_ALERT if alert_active else SNAPSHOT_INTERVAL_NORMAL
        if (now - self._last_snapshot_t) < interval:
            return
        self._last_snapshot_t = now

        threading.Thread(
            target=self._build_and_push_snapshot,
            args=(frame.copy(), left_eye_norm, right_eye_norm, alert_active),
            daemon=True, name="fs-snapshot"
        ).start()

    def flush(self):
        """Mark rider as disconnected on shutdown."""
        if not self._enabled:
            return
        try:
            self._ref.update({
                "connected": False,
                "smsNeeded": False,
                "updatedAt": firestore.SERVER_TIMESTAMP,
            })
            logger.info("FirestoreReporter: flushed disconnect status")
        except Exception as e:
            logger.warning(f"FirestoreReporter: flush failed ({e})")

    # ------------------------------------------------------------------
    # Private — snapshot pipeline
    # ------------------------------------------------------------------

    def _build_and_push_snapshot(self, frame, left_eye, right_eye, alert_active):
        try:
            annotated = frame.copy()

            # Draw eye outlines on the full frame
            if left_eye and right_eye:
                self._draw_eye_overlay(annotated, left_eye, right_eye, alert_active)

            # Crop to eye strip using landmark bounding box
            if left_eye and right_eye:
                crop = self._crop_eye_strip(annotated, left_eye, right_eye)
            else:
                h, w = annotated.shape[:2]
                crop = annotated[h//3: 2*h//3, :]   # fallback: middle third

            # Scale to fixed output size
            scaled = cv2.resize(crop, (SNAPSHOT_W, SNAPSHOT_H), interpolation=cv2.INTER_LINEAR)

            # JPEG encode
            encode_params = [cv2.IMWRITE_JPEG_QUALITY, SNAPSHOT_QUALITY]
            _, buf = cv2.imencode(".jpg", scaled, encode_params)
            b64 = base64.b64encode(buf.tobytes()).decode("ascii")

            self._ref.update({
                "snapshot":          b64,
                "snapshotUpdatedAt": firestore.SERVER_TIMESTAMP,
            })
        except Exception as e:
            logger.debug(f"FirestoreReporter: snapshot failed ({e})")

    def _crop_eye_strip(self, frame, left_eye, right_eye):
        h, w = frame.shape[:2]
        all_pts = list(left_eye) + list(right_eye)
        xs = [p[0] for p in all_pts]; ys = [p[1] for p in all_pts]

        span_x = max(max(xs) - min(xs), 0.05)
        span_y = max(max(ys) - min(ys), 0.015)
        pad_x  = span_x * 0.50
        pad_y  = span_y * 2.50

        left   = max(0,   int((min(xs) - pad_x) * w))
        top    = max(0,   int((min(ys) - pad_y) * h))
        right  = min(w,   int((max(xs) + pad_x) * w))
        bottom = min(h,   int((max(ys) + pad_y) * h))

        if right <= left or bottom <= top:
            return frame
        return frame[top:bottom, left:right]

    def _draw_eye_overlay(self, frame, left_eye, right_eye, alert_active):
        h, w = frame.shape[:2]
        color = (34, 204, 68) if not alert_active else (68, 68, 255)  # BGR

        for eye in (left_eye, right_eye):
            pts = np.array([[int(x*w), int(y*h)] for x, y in eye], np.int32)
            cv2.polylines(frame, [pts], isClosed=True, color=color, thickness=2)
            for px, py in pts:
                cv2.circle(frame, (px, py), 3, color, -1)

        if alert_active:
            overlay = frame.copy()
            cv2.rectangle(overlay, (0, 0), (w, h), (0, 0, 180), -1)
            cv2.addWeighted(overlay, 0.15, frame, 0.85, 0, frame)

    def _write(self, data: dict):
        try:
            self._ref.set(data, merge=True)
        except Exception as e:
            logger.warning(f"FirestoreReporter: write failed ({e})")
