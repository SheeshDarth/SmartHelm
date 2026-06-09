"""
generate_alert_wav.py — Creates alert.wav for the SmartHelm Android app.

Run once before building:
    python scripts/generate_alert_wav.py

Output: app/src/main/res/raw/alert.wav
        440 Hz sine wave, 300 ms, 16-bit PCM, 44100 Hz, mono
"""

import struct
import math
import os

SAMPLE_RATE  = 44100
FREQUENCY    = 1000       # Hz — matches Pi backend ALERT_BEEP_FREQUENCY
DURATION_SEC = 0.30       # 300 ms — matches Pi backend ALERT_BEEP_DURATION_MS
AMPLITUDE    = 28000      # 16-bit max is 32767

OUT_PATH = os.path.join(
    os.path.dirname(__file__),
    "..", "app", "src", "main", "res", "raw", "alert.wav"
)


def write_wav(path: str, samples: list[int], sample_rate: int):
    num_samples   = len(samples)
    bits_per_samp = 16
    byte_rate     = sample_rate * 1 * bits_per_samp // 8
    block_align   = 1 * bits_per_samp // 8
    data_size     = num_samples * block_align

    with open(path, "wb") as f:
        # RIFF chunk
        f.write(b"RIFF")
        f.write(struct.pack("<I", 36 + data_size))
        f.write(b"WAVE")
        # fmt sub-chunk
        f.write(b"fmt ")
        f.write(struct.pack("<I", 16))          # chunk size
        f.write(struct.pack("<H", 1))           # PCM format
        f.write(struct.pack("<H", 1))           # mono
        f.write(struct.pack("<I", sample_rate))
        f.write(struct.pack("<I", byte_rate))
        f.write(struct.pack("<H", block_align))
        f.write(struct.pack("<H", bits_per_samp))
        # data sub-chunk
        f.write(b"data")
        f.write(struct.pack("<I", data_size))
        for s in samples:
            f.write(struct.pack("<h", s))


def main():
    n = int(SAMPLE_RATE * DURATION_SEC)
    # Sine wave with linear fade-out to avoid click at end
    samples = []
    for i in range(n):
        t    = i / SAMPLE_RATE
        fade = 1.0 - (i / n)               # linear fade-out
        val  = int(AMPLITUDE * math.sin(2 * math.pi * FREQUENCY * t) * fade)
        samples.append(max(-32768, min(32767, val)))

    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    write_wav(OUT_PATH, samples, SAMPLE_RATE)
    print(f"Written: {OUT_PATH}  ({len(samples)} samples, {DURATION_SEC*1000:.0f} ms)")


if __name__ == "__main__":
    main()
