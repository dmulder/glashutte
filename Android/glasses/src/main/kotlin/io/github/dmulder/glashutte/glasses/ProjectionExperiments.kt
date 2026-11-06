package io.github.dmulder.glashutte.glasses

import android.content.Context

/**
 * Runtime switch for the projection/zoom behaviour, edited from the diagnostics screen and
 * stored in its own SharedPreferences file (separate from [TrackingExperiments]) so it can be
 * flipped on the device to A/B the two behaviours. Changes take effect on the next frame.
 *
 * ON (default): the projection FOV is derived from the glasses' panel optics, and zoom changes
 * the virtual display's size/distance in FREE (world-locked) view instead of scaling the
 * projection.
 *
 * OFF: the previous behaviour — a fixed 55 degree projection with zoom applied to the projection.
 */
object ProjectionExperiments {

    private const val PREFS = "glashutte_projection_experiments"
    private const val KEY_CALIBRATED = "calibrated_projection"

    @Volatile private var cached: Boolean? = null

    /** Cheap enough to call every frame: after the first read it is a volatile load. */
    fun calibratedProjection(context: Context): Boolean =
        cached ?: prefs(context).getBoolean(KEY_CALIBRATED, true).also { cached = it }

    fun setCalibratedProjection(context: Context, value: Boolean) {
        cached = value
        prefs(context).edit().putBoolean(KEY_CALIBRATED, value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
