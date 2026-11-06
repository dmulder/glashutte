package io.github.dmulder.glashutte.glasses

import android.content.Context

/**
 * Developer switches for narrowing down a tracking failure on a real device, stored in
 * SharedPreferences and edited from the diagnostics screen. Every default reproduces the
 * upstream behaviour exactly; they only matter once someone changes them.
 */
object TrackingExperiments {

    /** Which tracking path to force, regardless of what the SDK reports for the product. */
    enum class PathOverride(val label: String) {
        AUTO("Auto (SDK decides, as upstream)"),
        CARINA_3DOF("Force Carina VIO, 3DoF"),
        CARINA_6DOF("Force Carina VIO, 6DoF"),
        HOST_IMU("Force Gen1/2 host IMU"),
        NATIVE_DOF("Force on-glasses native DoF"),
    }

    private const val PREFS = "glashutte_tracking_experiments"
    private const val KEY_PATH = "path_override"
    private const val KEY_CACHE_DIR = "init_cache_dir"
    private const val KEY_OVERLAY = "glasses_overlay"

    fun pathOverride(context: Context): PathOverride {
        val name = prefs(context).getString(KEY_PATH, null) ?: return PathOverride.AUTO
        return runCatching { PathOverride.valueOf(name) }.getOrDefault(PathOverride.AUTO)
    }

    fun setPathOverride(context: Context, value: PathOverride) {
        prefs(context).edit().putString(KEY_PATH, value.name).apply()
    }

    /** Pass the app's cache directory to `xr_device_provider_initialize` instead of NULL. */
    fun initCacheDir(context: Context): Boolean = prefs(context).getBoolean(KEY_CACHE_DIR, false)

    fun setInitCacheDir(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_CACHE_DIR, value).apply()
    }

    /** Draw the diagnostics text over the glasses' view. */
    fun glassesOverlay(context: Context): Boolean = prefs(context).getBoolean(KEY_OVERLAY, false)

    fun setGlassesOverlay(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_OVERLAY, value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
