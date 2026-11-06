package io.github.dmulder.glashutte.glasses

/**
 * Decides when a tracker has finished calibrating, so the app only takes its reference pose
 * ("straight ahead") from a settled estimate.
 *
 * Carina VIO needs on the order of ten seconds after start-up before its pose is trustworthy;
 * a reference taken from the first sample bakes an unsettled heading into every later frame.
 * The gate is ready when both hold:
 *  - at least [waitMs] has passed since [begin], and
 *  - when the tracker reports a usable status (6DoF), it has read "stable" continuously for
 *    [stableHoldMs]. Trackers that do not report a status are gated on time alone.
 *
 * A status that never settles must not block tracking forever, so after [waitMs] plus
 * [maxExtraMs] the gate opens anyway and [timedOut] is set. Once ready it stays ready until
 * [begin] is called again.
 *
 * Pure Kotlin with caller-supplied timestamps; not thread-safe (use from the pose thread).
 */
class CalibrationGate(
    var enabled: Boolean = true,
    var waitMs: Long = DEFAULT_WAIT_MS,
    private val stableHoldMs: Long = DEFAULT_STABLE_HOLD_MS,
    private val maxExtraMs: Long = DEFAULT_MAX_EXTRA_MS,
) {
    enum class Phase { CALIBRATING, READY }

    var phase: Phase = Phase.CALIBRATING
        private set

    /** True when the gate opened because the status never settled, not because it did. */
    var timedOut: Boolean = false
        private set

    /** Why the gate is open, for logs; empty while calibrating. */
    var readyReason: String = ""
        private set

    private var beganAtMs = 0L
    private var stableSinceMs = -1L

    /** Start (or restart) calibration at [nowMs]. */
    fun begin(nowMs: Long) {
        beganAtMs = nowMs
        stableSinceMs = -1L
        phase = Phase.CALIBRATING
        timedOut = false
        readyReason = ""
    }

    /**
     * Feed one pose poll. [statusUsable] says whether [status] means anything on this tracker
     * (Carina 6DoF); [status] is [STATUS_STABLE] when settled, any other value for unsettled.
     */
    fun update(nowMs: Long, statusUsable: Boolean, status: Int): Phase {
        if (phase == Phase.READY) return phase
        if (!enabled) return open("calibration wait disabled")

        if (statusUsable) {
            if (status == STATUS_STABLE) {
                if (stableSinceMs < 0) stableSinceMs = nowMs
            } else {
                stableSinceMs = -1L
            }
        }
        val elapsed = nowMs - beganAtMs
        val timeOk = elapsed >= waitMs
        val stableOk = !statusUsable || (stableSinceMs >= 0 && nowMs - stableSinceMs >= stableHoldMs)
        return when {
            timeOk && stableOk -> open(
                if (statusUsable) "waited ${elapsed} ms, status stable" else "waited ${elapsed} ms (no status on this path)",
            )
            elapsed >= waitMs + maxExtraMs -> {
                timedOut = true
                open("status still unstable after ${elapsed} ms; proceeding anyway")
            }
            else -> phase
        }
    }

    /** Milliseconds until the minimum wait elapses (0 once it has, or once ready). */
    fun remainingMs(nowMs: Long): Long =
        if (phase == Phase.READY) 0L else (beganAtMs + waitMs - nowMs).coerceAtLeast(0L)

    private fun open(reason: String): Phase {
        phase = Phase.READY
        readyReason = reason
        return phase
    }

    companion object {
        /** Carina `pose_status` value meaning the estimate has settled. */
        const val STATUS_STABLE = 0

        const val DEFAULT_WAIT_MS = 10_000L
        const val DEFAULT_STABLE_HOLD_MS = 500L
        const val DEFAULT_MAX_EXTRA_MS = 20_000L
    }
}
