package com.truesteps.app

/**
 * The brain of the app. Pure Kotlin (no Android classes) so it can be unit-tested.
 *
 * Steps are collected in short windows (30 s). Each window is classified using:
 *  - GPS speed (strongest signal: nobody walks at 15+ km/h)
 *  - Google's activity recognition (IN_VEHICLE / ON_BICYCLE / ON_FOOT ...)
 *  - Step cadence (vibration often produces impossible step rates)
 *  - "Ride hangover": right after a ride, steps stay suspicious until walking is confirmed
 *
 * Windows that can't be decided yet are HELD and resolved by the next clear window,
 * so the first seconds of a ride (before GPS locks on) are still removed.
 */

enum class Motion { VEHICLE, BICYCLE, ON_FOOT, STILL, UNKNOWN }

data class MotionReading(val motion: Motion, val confidence: Int, val timeMs: Long)

data class WindowInput(
    val startMs: Long,
    val endMs: Long,
    val steps: Int,
    /** GPS speeds (km/h) measured during this window, already filtered for accuracy. */
    val speedsKmh: List<Float>,
    /** Latest activity-recognition reading, if any. */
    val motion: MotionReading?,
)

enum class Kind { WALK, VEHICLE }

data class CommittedWindow(
    val startMs: Long,
    val endMs: Long,
    val steps: Int,
    val kind: Kind,
    val reason: String,
)

class StepFilter(
    private val vehicleSpeedKmh: Float = 15f,
    private val walkingMaxSpeedKmh: Float = 8f,
    private val minConfidence: Int = 60,
    private val maxCadencePerSec: Float = 4.0f,
    private val motionMaxAgeMs: Long = 200_000,
    private val hangoverMs: Long = 180_000,
    private val maxHoldMs: Long = 300_000,
) {
    private enum class Verdict { WALK, VEHICLE, UNSURE }

    private data class Decision(
        val verdict: Verdict,
        val reason: String,
        val strong: Boolean,
        /** For UNSURE: if nothing settles it, treat as vehicle rather than walking. */
        val leanVehicle: Boolean = false,
    )

    private val held = ArrayDeque<Pair<WindowInput, Boolean>>() // (window, leans vehicle)

    /** Last time we had clear evidence of being in a vehicle. */
    var lastVehicleMs: Long = 0L
        private set

    /** Last time walking was confirmed by activity recognition. */
    private var lastFootConfirmedMs: Long = 0L

    val heldSteps: Int get() = held.sumOf { it.first.steps }

    fun process(w: WindowInput): List<CommittedWindow> {
        val d = decide(w)
        val out = mutableListOf<CommittedWindow>()

        when (d.verdict) {
            Verdict.VEHICLE -> {
                lastVehicleMs = w.endMs
                out += resolveHeld(Kind.VEHICLE, "Part of a ride")
                if (w.steps > 0) out += w.commit(Kind.VEHICLE, d.reason)
            }
            Verdict.WALK -> {
                // A weak WALK on a 0-step window says nothing; don't use it to resolve held windows.
                if (w.steps > 0 || d.strong) out += resolveHeld(Kind.WALK, "Confirmed walking afterwards")
                if (w.steps > 0) out += w.commit(Kind.WALK, d.reason)
            }
            Verdict.UNSURE -> if (w.steps > 0) held.addLast(w to d.leanVehicle)
        }
        out += expireHeld(w.endMs)
        return out
    }

    /** Called when tracking stops: decide everything still on hold. */
    fun flushAll(nowMs: Long): List<CommittedWindow> = expireHeld(nowMs, force = true)

    private fun decide(w: WindowInput): Decision {
        val speed = median(w.speedsKmh)
        val motion = w.motion?.takeIf { w.endMs - it.timeMs <= motionMaxAgeMs }
        val seconds = ((w.endMs - w.startMs) / 1000f).coerceAtLeast(1f)
        val cadence = w.steps / seconds

        if (motion?.motion == Motion.ON_FOOT && motion.confidence >= minConfidence) {
            lastFootConfirmedMs = motion.timeMs
        }
        val inHangover = w.endMs - lastVehicleMs < hangoverMs && lastFootConfirmedMs < lastVehicleMs

        // 1. Speed: the most reliable signal.
        if (speed != null && speed >= vehicleSpeedKmh) {
            return Decision(Verdict.VEHICLE, "GPS speed ${speed.toInt()} km/h", true)
        }
        // 2. Activity recognition says vehicle / bike.
        if (motion != null && motion.confidence >= minConfidence) {
            if (motion.motion == Motion.VEHICLE || motion.motion == Motion.BICYCLE) {
                // GPS says walking pace: could be a traffic jam, or you just parked. Hold and decide later.
                if (speed != null && speed < walkingMaxSpeedKmh) {
                    return Decision(Verdict.UNSURE, "Slow, but phone still says vehicle", false)
                }
                val what = if (motion.motion == Motion.VEHICLE) "Phone detected in vehicle" else "Detected on a bike"
                return Decision(Verdict.VEHICLE, "$what (${motion.confidence}%)", true)
            }
        }
        // 3. Impossible cadence = vibration.
        if (w.steps >= 20 && cadence > maxCadencePerSec) {
            return Decision(Verdict.VEHICLE, "Impossible step rate (${"%.1f".format(cadence)}/s)", true)
        }
        // 4. Activity recognition confirms walking (and the rhythm looks like real walking;
        //    a reading from just before a ride can still say "walking" for a few seconds).
        val walkingRhythm = w.steps < 20 || cadence in 1.2f..3.2f
        if (motion != null && motion.motion == Motion.ON_FOOT && motion.confidence >= minConfidence && walkingRhythm) {
            return Decision(Verdict.WALK, "Walking detected (${motion.confidence}%)", true)
        }
        // 5. Shortly after a ride, slow movement could still be traffic.
        if (inHangover) {
            return Decision(Verdict.UNSURE, "Just after a ride", false)
        }
        if (w.steps == 0) {
            // Nothing to save; a clear walking speed can still settle earlier held steps.
            return Decision(Verdict.WALK, "No steps", speed != null && speed in 2f..walkingMaxSpeedKmh)
        }
        // 6. GPS shows walking pace or standing.
        if (speed != null && speed < walkingMaxSpeedKmh) {
            // Moving at walking speed but hardly any steps = a vehicle crawling in traffic, not walking.
            if (speed >= 2f && cadence < 0.7f) {
                return Decision(Verdict.UNSURE, "Moving, but too few steps for walking", false, leanVehicle = true)
            }
            return if (speed >= 2f) {
                Decision(Verdict.WALK, "Walking pace (${"%.1f".format(speed)} km/h)", true)
            } else {
                Decision(Verdict.WALK, "Small movement, not travelling", false)
            }
        }
        // 7. Steps with no GPS and no "walking" from Google: never keep blindly.
        //    (In a smooth car Google often says "still" while vibration adds a few steps.)
        //    Held until GPS or Google decides; kept after 5 min if no vehicle shows up.
        return Decision(Verdict.UNSURE, "Waiting for GPS/activity", false)
    }

    private fun resolveHeld(kind: Kind, reason: String): List<CommittedWindow> {
        val out = held.map { it.first.commit(kind, reason) }
        held.clear()
        return out
    }

    private fun expireHeld(nowMs: Long, force: Boolean = false): List<CommittedWindow> {
        val out = mutableListOf<CommittedWindow>()
        while (held.isNotEmpty() && (force || nowMs - held.first().first.endMs >= maxHoldMs)) {
            val (w, leanVehicle) = held.removeFirst()
            val nearRide = w.endMs - lastVehicleMs < hangoverMs && lastFootConfirmedMs < lastVehicleMs
            out += when {
                nearRide -> w.commit(Kind.VEHICLE, "Close to a ride, never confirmed walking")
                leanVehicle -> w.commit(Kind.VEHICLE, "Slow travel with too few steps (traffic)")
                else -> w.commit(Kind.WALK, "No vehicle signs")
            }
        }
        return out
    }

    private fun WindowInput.commit(kind: Kind, reason: String) =
        CommittedWindow(startMs, endMs, steps, kind, reason)

    private fun median(v: List<Float>): Float? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }
}
