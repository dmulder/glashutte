package io.github.dmulder.glashutte.glasses

import android.content.Context

/**
 * Switches for the native handoff mode (an app running flat on the glasses' real display).
 * Stored in their own SharedPreferences file; the defaults are what the feature ships with.
 */
object HandoffSettings {

    /** Where a flat app is put. */
    enum class Target(val label: String) {
        AUTO("Glasses display first, phone mirror if that fails"),
        EXTERNAL_ONLY("Glasses' own display only (never mirror the phone)"),
        MIRROR_ONLY("Phone screen mirrored to the glasses"),
        ;

        companion object {
            fun parse(name: String?): Target = values().firstOrNull { it.name == name } ?: AUTO
        }
    }

    private const val PREFS = "glashutte_handoff"
    private const val KEY_TARGET = "target"
    private const val KEY_DENSITY = "density_dpi"
    private const val KEY_CURSOR = "cursor_overlay"
    private const val KEY_RESIZE = "set_display_size"
    private const val KEY_STOP_ON_RETURN = "stop_on_return"
    private const val KEY_DISPLAY_WAS_CHANGED = "display_was_changed"

    fun target(context: Context): Target = Target.parse(prefs(context).getString(KEY_TARGET, null))
    fun setTarget(context: Context, value: Target) = prefs(context).edit().putString(KEY_TARGET, value.name).apply()

    /** Density the flat app's display is set to while it runs (restored on return). */
    fun densityDpi(context: Context): Int =
        FlatFit.clampDensity(prefs(context).getInt(KEY_DENSITY, FlatFit.DEFAULT_DENSITY_DPI))

    fun setDensityDpi(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_DENSITY, FlatFit.clampDensity(value)).apply()

    /** Draw a cursor on a non-secure overlay on the glasses. Default ON. */
    fun cursorOverlay(context: Context): Boolean = prefs(context).getBoolean(KEY_CURSOR, true)
    fun setCursorOverlay(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_CURSOR, value).apply()

    /**
     * Force the glasses display to the panel's native 1920x1200 when it reports something else
     * (`wm size -d`). Default ON; a display that already reports 16:10 is left alone.
     */
    fun forcePanelSize(context: Context): Boolean = prefs(context).getBoolean(KEY_RESIZE, true)
    fun setForcePanelSize(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_RESIZE, value).apply()

    /** Force-stop the app when returning to the workspace (so audio does not keep playing). Default ON. */
    fun stopOnReturn(context: Context): Boolean = prefs(context).getBoolean(KEY_STOP_ON_RETURN, true)
    fun setStopOnReturn(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_STOP_ON_RETURN, value).apply()

    /**
     * `wm size` / `wm density` changes to a physical display outlive the app, so this records
     * that one is pending restoration; the next start undoes it if the app died meanwhile.
     */
    fun displayChangePending(context: Context): Int =
        prefs(context).getInt(KEY_DISPLAY_WAS_CHANGED, -1)

    fun setDisplayChangePending(context: Context, displayId: Int) =
        prefs(context).edit().putInt(KEY_DISPLAY_WAS_CHANGED, displayId).apply()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
