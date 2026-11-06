package io.github.dmulder.glashutte.glasses

import android.content.Context

/**
 * Runtime switches for how head tracking *feels*: the neck-pivot camera offset, the wait for
 * tracker calibration, and the Carina DoF mode. Each is exposed in the "Glashutte
 * Diagnostics" experiments list so they can be compared on a device without a rebuild, and the
 * active values are logged under [GlassesDiagnostics.TAG] at session start, on every change,
 * and in the 5-second heartbeat.
 *
 * Kept apart from [TrackingExperiments] (which only holds fault-finding switches) so the two
 * evolve independently. Values are cached in memory; the renderer reads [current] every frame.
 */
object TrackingFeel {

    /** Which Carina DoF the session opens in. Applies on the next *Restart tracking*. */
    enum class DofMode(val label: String) {
        /** Follow the Settings screen toggle (6DoF off by default, so 3DoF). */
        APP_SETTING("Follow app Settings (default: 3DoF)"),
        CARINA_3DOF("3DoF (orientation only)"),
        CARINA_6DOF("6DoF with positional parallax"),
        /** Run VIO in 6DoF but use only its orientation; the camera never translates. */
        CARINA_6DOF_ORIENTATION_ONLY("6DoF VIO, orientation only (no parallax)"),
    }

    /** An immutable view of every switch, so one frame or one poll sees a consistent set. */
    data class Snapshot(
        /** Offset the 3DoF camera so it pivots at the neck instead of the eye. */
        val neckPivotEnabled: Boolean = true,
        val neckOffsetM: Float = NeckPivot.DEFAULT_OFFSET_M,
        /** Hold the reference pose until the tracker has calibrated (Carina only). */
        val waitForCalibration: Boolean = true,
        val calibrationWaitSec: Int = DEFAULT_CALIBRATION_WAIT_SEC,
        val dofMode: DofMode = DofMode.APP_SETTING,
    ) {
        /** Whether to open Carina in 6DoF, given the app-level Settings toggle. */
        fun wantsSixDof(appSettingSixDof: Boolean): Boolean = when (dofMode) {
            DofMode.APP_SETTING -> appSettingSixDof
            DofMode.CARINA_3DOF -> false
            DofMode.CARINA_6DOF, DofMode.CARINA_6DOF_ORIENTATION_ONLY -> true
        }

        /** Whether tracked position may reach the camera. */
        val deliversPosition: Boolean get() = dofMode != DofMode.CARINA_6DOF_ORIENTATION_ONLY

        fun summary(): String =
            "neckPivot=${if (neckPivotEnabled) "on" else "off"}(%.1fcm) ".format(neckOffsetM * 100f) +
                "waitForCalibration=${if (waitForCalibration) "on" else "off"}(${calibrationWaitSec}s) " +
                "dofMode=${dofMode.name}"
    }

    const val DEFAULT_CALIBRATION_WAIT_SEC = 10
    const val MAX_NECK_OFFSET_M = 0.30f
    const val MAX_CALIBRATION_WAIT_SEC = 30

    private const val PREFS = "glashutte_tracking_feel"
    private const val KEY_NECK = "neck_pivot"
    private const val KEY_NECK_CM = "neck_offset_cm"
    private const val KEY_WAIT = "wait_calibration"
    private const val KEY_WAIT_SEC = "calibration_wait_sec"
    private const val KEY_DOF = "dof_mode"

    @Volatile var current: Snapshot = Snapshot()
        private set

    @Volatile private var loaded = false

    /** Load persisted values. Idempotent; safe to call from any entry point. */
    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = prefs(context)
            val dof = p.getString(KEY_DOF, null)
                ?.let { name -> runCatching { DofMode.valueOf(name) }.getOrNull() }
                ?: DofMode.APP_SETTING
            current = Snapshot(
                neckPivotEnabled = p.getBoolean(KEY_NECK, true),
                neckOffsetM = p.getFloat(KEY_NECK_CM, NeckPivot.DEFAULT_OFFSET_M * 100f) / 100f,
                waitForCalibration = p.getBoolean(KEY_WAIT, true),
                calibrationWaitSec = p.getInt(KEY_WAIT_SEC, DEFAULT_CALIBRATION_WAIT_SEC),
                dofMode = dof,
            ).sanitised()
            loaded = true
        }
        GlassesDiagnostics.logSettings("loaded")
    }

    fun setNeckPivotEnabled(context: Context, value: Boolean) =
        update(context, "neckPivotEnabled=$value") { it.copy(neckPivotEnabled = value) }

    fun setNeckOffsetM(context: Context, value: Float) =
        update(context, "neckOffsetM=$value") { it.copy(neckOffsetM = value) }

    fun setWaitForCalibration(context: Context, value: Boolean) =
        update(context, "waitForCalibration=$value") { it.copy(waitForCalibration = value) }

    fun setCalibrationWaitSec(context: Context, value: Int) =
        update(context, "calibrationWaitSec=$value") { it.copy(calibrationWaitSec = value) }

    fun setDofMode(context: Context, value: DofMode) =
        update(context, "dofMode=$value (applies on restart)") { it.copy(dofMode = value) }

    private fun update(context: Context, what: String, change: (Snapshot) -> Snapshot) {
        init(context)
        val next = synchronized(this) { change(current).sanitised().also { current = it } }
        prefs(context).edit()
            .putBoolean(KEY_NECK, next.neckPivotEnabled)
            .putFloat(KEY_NECK_CM, next.neckOffsetM * 100f)
            .putBoolean(KEY_WAIT, next.waitForCalibration)
            .putInt(KEY_WAIT_SEC, next.calibrationWaitSec)
            .putString(KEY_DOF, next.dofMode.name)
            .apply()
        GlassesDiagnostics.logSettings("changed: $what")
    }

    private fun Snapshot.sanitised(): Snapshot = copy(
        neckOffsetM = if (neckOffsetM.isNaN()) NeckPivot.DEFAULT_OFFSET_M else neckOffsetM.coerceIn(0f, MAX_NECK_OFFSET_M),
        calibrationWaitSec = calibrationWaitSec.coerceIn(0, MAX_CALIBRATION_WAIT_SEC),
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
