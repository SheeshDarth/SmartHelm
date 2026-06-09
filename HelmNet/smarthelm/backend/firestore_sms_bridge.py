"""
firestore_sms_bridge.py — SIM800L SMS gateway triggered by Firestore.

Listens to riders/ collection for smsNeeded=True.
When found, sends an SMS via the SIM800L GSM module over UART,
then clears the smsNeeded flag so duplicates are never sent.

Setup:
    pip install firebase-admin pyserial
    Place serviceAccountKey.json (from Firebase console → Project settings →
    Service accounts → Generate new private key) in the same directory.

SIM800L wiring (Raspberry Pi):
    SIM800L TX  → Pi GPIO 15 (RXD)
    SIM800L RX  → Pi GPIO 14 (TXD)
    SIM800L GND → Pi GND
    SIM800L VCC → 3.7–4.2 V (use a separate LiPo or DC-DC, NOT Pi 3.3 V)
    Enable UART: add  enable_uart=1  to /boot/config.txt, disable serial console.

Run:
    python3 firestore_sms_bridge.py
    # or as a systemd service (recommended)
"""

import logging
import signal
import sys
import time
import threading

import firebase_admin
from firebase_admin import credentials, firestore
import serial

# ---------------------------------------------------------------------------
# Config — edit these to match your setup
# ---------------------------------------------------------------------------
SERVICE_ACCOUNT_KEY = "serviceAccountKey.json"   # path to Firebase service account
UART_PORT           = "/dev/ttyS0"               # or /dev/ttyAMA0 on older Pi
UART_BAUD           = 9600
SMS_COOLDOWN_SECS   = 300   # 5 min minimum between SMS per rider

# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s"
)
log = logging.getLogger(__name__)

# Per-rider last-SMS timestamp (to enforce cooldown even across bridge restarts
# at the process level; Firestore flag provides the persistent cooldown).
_last_sms_time: dict = {}
_stop_event = threading.Event()


# ---------------------------------------------------------------------------
# SIM800L helpers
# ---------------------------------------------------------------------------

def send_sms_sim800l(number: str, message: str) -> bool:
    """
    Sends a text message via SIM800L using raw AT commands.
    Returns True on success, False on failure.
    """
    try:
        with serial.Serial(UART_PORT, UART_BAUD, timeout=10) as ser:
            def at(cmd: str, wait: float = 0.5) -> str:
                ser.write((cmd + "\r\n").encode())
                time.sleep(wait)
                return ser.read_all().decode(errors="ignore")

            # Basic handshake
            rsp = at("AT")
            if "OK" not in rsp:
                log.error("SIM800L not responding — check wiring and power")
                return False

            at("AT+CMGF=1")              # text mode
            at(f'AT+CMGS="{number}"', 0.5)
            ser.write(message.encode() + b'\x1A')   # Ctrl+Z = send
            time.sleep(4)
            rsp = ser.read_all().decode(errors="ignore")

            if "+CMGS" in rsp:
                log.info(f"SMS sent to {number}")
                return True
            else:
                log.warning(f"SMS may have failed — modem response: {rsp!r}")
                return False

    except serial.SerialException as e:
        log.error(f"Serial error: {e}")
        return False
    except Exception as e:
        log.error(f"Unexpected error in send_sms_sim800l: {e}")
        return False


# ---------------------------------------------------------------------------
# Firestore watcher
# ---------------------------------------------------------------------------

def _on_riders_snapshot(col_snapshot, changes, read_time):
    """Firestore onSnapshot callback — fires on every change to riders/."""
    for change in changes:
        if change.type.name not in ("ADDED", "MODIFIED"):
            continue

        doc_ref = change.document.reference
        data    = change.document.to_dict() or {}
        device_id = change.document.id

        if not data.get("smsNeeded"):
            continue    # nothing to do

        contact = data.get("emergencyContact", "").strip()
        if not contact:
            log.warning(f"[{device_id}] smsNeeded=True but no emergencyContact — skipping")
            doc_ref.update({"smsNeeded": False})
            continue

        # Enforce local cooldown
        now = time.time()
        if (now - _last_sms_time.get(device_id, 0)) < SMS_COOLDOWN_SECS:
            log.info(f"[{device_id}] cooldown active — skipping SMS")
            doc_ref.update({"smsNeeded": False})
            continue

        rider_name = data.get("riderName", device_id)
        msg = (
            f"SmartHelm Alert: {rider_name} is showing signs of drowsiness. "
            f"Please check in immediately."
        )

        log.info(f"[{device_id}] Sending SMS to {contact} for rider '{rider_name}'")

        # Send in a background thread so the callback returns immediately
        def _send(did=device_id, ref=doc_ref, num=contact, m=msg):
            success = send_sms_sim800l(num, m)
            _last_sms_time[did] = time.time()
            ref.update({"smsNeeded": False})   # always clear regardless of success
            if not success:
                log.warning(f"[{did}] SMS failed — smsNeeded cleared anyway")

        t = threading.Thread(target=_send, daemon=True)
        t.start()


def watch_riders(db):
    """Subscribe to the riders collection and block until _stop_event is set."""
    log.info("Subscribing to Firestore riders/ collection…")
    watch = db.collection("riders").on_snapshot(_on_riders_snapshot)
    log.info("Watching for smsNeeded flags. Press Ctrl+C to stop.")

    while not _stop_event.is_set():
        time.sleep(1)

    watch.unsubscribe()
    log.info("Unsubscribed. Exiting.")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main():
    cred = credentials.Certificate(SERVICE_ACCOUNT_KEY)
    firebase_admin.initialize_app(cred)
    db = firestore.client()

    def _signal_handler(sig, frame):
        log.info("Shutdown signal received")
        _stop_event.set()

    signal.signal(signal.SIGINT,  _signal_handler)
    signal.signal(signal.SIGTERM, _signal_handler)

    watch_riders(db)


if __name__ == "__main__":
    main()
