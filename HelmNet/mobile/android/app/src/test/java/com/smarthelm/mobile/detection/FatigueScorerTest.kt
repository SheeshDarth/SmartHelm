package com.smarthelm.mobile.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Encodes the PRD F4 acceptance criteria as runnable proof that the [FatigueScorer]
 * does NOT confuse natural blinks, single yawns, or stationary rests with drowsiness,
 * while still always catching a genuine sustained closure.
 *
 * Pure JVM — every call passes an explicit timestamp, so android.os.SystemClock is never hit.
 */
class FatigueScorerTest {

    private val SPEED_MOVING  = 25f   // km/h — riding
    private val SPEED_STOPPED = 0f    // km/h — at a light
    private val SPEED_UNKNOWN = -1f   // no GPS fix

    private fun det(
        eyeState: String,
        mar: Float = 0.05f,
        face: Boolean = true
    ) = DetectionResult(
        eyeState = eyeState, confidence = 1f, earSmoothed = 0.3f,
        faceDetected = face, mar = mar, headPitch = 0f
    )

    /** PerclosResult with only the fields FatigueScorer reads (perclos, continuousClosureSec). */
    private fun perc(perclos: Float, closureSec: Float) = PerclosResult(
        perclos = perclos, continuousClosureSec = closureSec,
        alertPerclos = false, alertContinuous = false, alertActive = false
    )

    // ── F4: natural blinks must NOT alert ──────────────────────────────────────
    @Test fun blinks_do_not_alert() {
        val s = FatigueScorer()
        var t = 0L
        var alerted = false
        // 30 s of normal riding: a ~200 ms blink every second, eyes open otherwise
        repeat(30) {
            // blink (closed 200 ms, never reaching the 0.4 s closure floor)
            val r1 = s.update(det("CLOSED"), perc(perclos = 4f, closureSec = 0.2f), SPEED_MOVING, t); t += 200
            // open the rest of the second
            val r2 = s.update(det("OPEN"), perc(perclos = 4f, closureSec = 0f), SPEED_MOVING, t); t += 800
            if (r1.alertActive || r2.alertActive) alerted = true
        }
        assertFalse("a 200 ms blink once per second must never raise an alert", alerted)
    }

    // ── F4: a single yawn must NOT alert ───────────────────────────────────────
    @Test fun single_yawn_does_not_alert() {
        val s = FatigueScorer()
        var t = 0L
        var alerted = false
        var last: FatigueResult? = null
        // ~1.2 s of wide-open mouth (yawn), eyes open, low PERCLOS
        repeat(12) {
            last = s.update(det("OPEN", mar = 0.7f), perc(perclos = 5f, closureSec = 0f), SPEED_MOVING, t)
            if (last!!.alertActive) alerted = true
            t += 100
        }
        // mouth closes again
        repeat(5) { last = s.update(det("OPEN", mar = 0.1f), perc(5f, 0f), SPEED_MOVING, t); t += 100 }
        assertFalse("one yawn must not raise an alert", alerted)
        assertTrue("the yawn should still be counted", last!!.yawnCount >= 1)
    }

    // ── F4 safety: a sustained closure (microsleep) MUST always alert ──────────
    @Test fun sustained_closure_alerts() {
        val s = FatigueScorer()
        var t = 0L
        var alerted = false
        // eyes closed continuously; closure timer ramps 0.0 → 2.0 s
        var closure = 0f
        repeat(20) {
            val r = s.update(det("CLOSED"), perc(perclos = 20f, closureSec = closure), SPEED_MOVING, t)
            if (r.alertActive) alerted = true
            closure += 0.1f
            t += 100
        }
        assertTrue("a >=1.5 s continuous closure while moving must alert", alerted)
    }

    // ── F4: stationary closure is speed-gated (rider resting at a light) ───────
    @Test fun stationary_closure_is_speed_gated() {
        val s = FatigueScorer()
        var t = 0L
        var alerted = false
        var gated = false
        var closure = 0f
        repeat(20) {
            val r = s.update(det("CLOSED"), perc(perclos = 20f, closureSec = closure), SPEED_STOPPED, t)
            if (r.alertActive) alerted = true
            if (r.speedGated) gated = true
            closure += 0.1f
            t += 100
        }
        assertFalse("a closure while stopped must NOT alarm (speed-gated)", alerted)
        assertTrue("the suppressed alert should be reported as speed-gated", gated)
    }

    // ── F4: high PERCLOS while riding alerts after the confirmation window ─────
    @Test fun high_perclos_alerts_after_confirmation() {
        val s = FatigueScorer()
        var t = 0L
        var alerted = false
        // PERCLOS pinned at 35% (full primary score) for >1 s, eyes between states
        repeat(15) {
            val r = s.update(det("UNKNOWN"), perc(perclos = 35f, closureSec = 0f), SPEED_MOVING, t)
            if (r.alertActive) alerted = true
            t += 100
        }
        assertTrue("sustained high PERCLOS while moving must alert", alerted)
    }

    // ── F4: speed unknown (no GPS) must not suppress a real alert ──────────────
    @Test fun unknown_speed_does_not_gate() {
        val s = FatigueScorer()
        var t = 0L
        var alerted = false
        var closure = 0f
        repeat(20) {
            val r = s.update(det("CLOSED"), perc(20f, closure), SPEED_UNKNOWN, t)
            if (r.alertActive) alerted = true
            closure += 0.1f
            t += 100
        }
        assertTrue("with no GPS fix the alert must still fire", alerted)
    }

    // ── breakdown is well-formed for the dashboard ─────────────────────────────
    @Test fun breakdown_keys_present() {
        val s = FatigueScorer()
        val r = s.update(det("OPEN"), perc(0f, 0f), SPEED_MOVING, 0L)
        assertEquals(setOf("closure", "perclos", "yawn", "nod"), r.breakdown.keys)
    }
}
