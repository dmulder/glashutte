package io.github.dmulder.glashutte.desktop

import android.content.Context
import android.content.SharedPreferences
import io.github.dmulder.glashutte.spatial.Layout

/**
 * Process-wide SharedPreferences-backed settings store. Survives restarts.
 *
 * Today it holds the per-layout "main screen" override — the screen index that counts as
 * the user's primary display for that layout (defaults to [Layout.defaultMainScreen]).
 * The store is intentionally tiny and concrete; if it grows beyond a few settings, split
 * along the same pattern instead of adding generic accessors.
 */
object WorkspaceSettings {

    private const val PREFS_NAME = "glashutte_workspace_settings"
    private const val KEY_MAIN_SCREEN_PREFIX = "main_screen_"
    private const val KEY_LAYOUT_ENABLED_PREFIX = "layout_enabled_"

    private const val KEY_CURSOR_SENSITIVITY = "cursor_sensitivity"
    private const val KEY_CURSOR_IDLE_S = "cursor_idle_s"
    private const val KEY_SCROLL_SENSITIVITY = "scroll_sensitivity"
    private const val KEY_FLICK_SENSITIVITY = "flick_sensitivity"
    private const val KEY_LONG_PRESS_MS = "long_press_ms"
    private const val KEY_PINCH_ENABLED = "pinch_enabled"
    private const val KEY_AUTO_RECENTER_ON_UNLOCK = "auto_recenter_on_unlock"
    private const val KEY_CARINA_6DOF = "carina_6dof"

    /** Default multiplier on per-frame cursor delta; 1.0 = no scaling. */
    const val DEFAULT_CURSOR_SENSITIVITY = 1.0f
    /** Default idle timeout before the cursor sprite hides, in seconds. */
    const val DEFAULT_CURSOR_IDLE_S = 5
    /** Default multiplier on a two-finger scroll's `vScroll`. */
    const val DEFAULT_SCROLL_SENSITIVITY = 12f
    /** Default cap on auto-scroll velocity, in pad-fractions per millisecond. */
    const val DEFAULT_FLICK_SENSITIVITY = 0.01f
    /** Default hold duration that fires a long-press, in milliseconds. */
    const val DEFAULT_LONG_PRESS_MS = 500L

    private const val KEY_MAX_WINDOWS_PER_SLOT = "max_windows_per_slot"
    private const val KEY_SNAP_ZONES_ENABLED = "snap_zones_enabled"
    private const val KEY_RESIZE_HANDLES_ENABLED = "resize_handles_enabled"
    private const val KEY_RECORDING_FRAME_INTERVAL = "recording_frame_interval"
    private const val KEY_CAPTURE_DEBUG_OVERLAY = "capture_debug_overlay"
    private const val KEY_SHOW_TASKBAR = "show_taskbar"
    private const val KEY_SHOW_TASKBAR_CLOCK = "show_taskbar_clock"
    private const val KEY_SHOW_TASKBAR_BATTERY = "show_taskbar_battery"
    private const val KEY_SHOW_TASKBAR_VOLUME = "show_taskbar_volume"
    private const val KEY_CLOCK_USE_24H = "clock_use_24h"
    private const val KEY_SHOW_TASKBAR_DATE = "show_taskbar_date"
    private const val KEY_TASKBAR_AUTO_HIDE = "taskbar_auto_hide"

    const val DEFAULT_MAX_WINDOWS_PER_SLOT = 5
    const val DEFAULT_RECORDING_FRAME_INTERVAL = 12

    private const val KEY_APP_DISPLAY_DPI = "app_display_dpi"
    const val DEFAULT_APP_DISPLAY_DPI = 200

    /**
     * Density-DPI range the Windows tab's slider offers. Lower numbers mean less
     * pixel-per-dp → app UI looks smaller; higher numbers blow it up. 200 (the
     * default) sits between Android's stock `mdpi` (160) and `hdpi` (240) buckets.
     */
    const val APP_DISPLAY_DPI_MIN = 60
    const val APP_DISPLAY_DPI_MAX = 240
    const val APP_DISPLAY_DPI_STEP = 10

    @Volatile
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * Effective main-screen index for [layout] — the user's override if any, else
     * [Layout.defaultMainScreen]. Always clamped into the layout's screen range, since
     * a stale override (saved before a layout's screen count changed) shouldn't crash.
     */
    fun mainScreenFor(layout: Layout): Int {
        val stored = if (::prefs.isInitialized) {
            prefs.getInt(KEY_MAIN_SCREEN_PREFIX + layout.name, layout.defaultMainScreen)
        } else layout.defaultMainScreen
        return stored.coerceIn(0, (layout.screens.size - 1).coerceAtLeast(0))
    }

    /** Override the main-screen index for [layout]. Persisted. */
    fun setMainScreenFor(layout: Layout, screenIdx: Int) {
        if (!::prefs.isInitialized) return
        val clamped = screenIdx.coerceIn(0, (layout.screens.size - 1).coerceAtLeast(0))
        prefs.edit().putInt(KEY_MAIN_SCREEN_PREFIX + layout.name, clamped).apply()
        listeners.forEach { runCatching { it() } }
    }

    /**
     * Whether [layout] is included in the toolbar layout-cycle rotation. Default true.
     * The Screens tab toggles this; [enabledLayouts] is what the controller's cycle
     * actually walks.
     */
    fun layoutEnabledFor(layout: Layout): Boolean {
        if (!::prefs.isInitialized) return true
        return prefs.getBoolean(KEY_LAYOUT_ENABLED_PREFIX + layout.name, true)
    }

    /**
     * Persist the enabled flag for [layout]. The "at least one layout must stay enabled"
     * guard is the caller's — [enabledLayouts] still returns SINGLE if everything is
     * somehow disabled, so the workspace never has no layout to render.
     */
    fun setLayoutEnabledFor(layout: Layout, enabled: Boolean) {
        if (!::prefs.isInitialized) return
        prefs.edit().putBoolean(KEY_LAYOUT_ENABLED_PREFIX + layout.name, enabled).apply()
        listeners.forEach { runCatching { it() } }
    }

    /**
     * The user-enabled layouts in declaration order. Always at least one entry — if the
     * user has somehow disabled them all, falls back to SINGLE so the cycle has
     * something to land on.
     */
    fun enabledLayouts(): List<Layout> {
        val list = Layout.values().filter { layoutEnabledFor(it) }
        return if (list.isEmpty()) listOf(Layout.SINGLE) else list
    }

    // region Input + view settings

    /** Cursor sensitivity multiplier, 0.25 – 3.0. */
    fun cursorSensitivity(): Float =
        if (::prefs.isInitialized) prefs.getFloat(KEY_CURSOR_SENSITIVITY, DEFAULT_CURSOR_SENSITIVITY)
        else DEFAULT_CURSOR_SENSITIVITY

    fun setCursorSensitivity(value: Float) = putFloat(KEY_CURSOR_SENSITIVITY, value)

    /** Seconds of no input before the cursor sprite hides. */
    fun cursorIdleSeconds(): Int =
        if (::prefs.isInitialized) prefs.getInt(KEY_CURSOR_IDLE_S, DEFAULT_CURSOR_IDLE_S)
        else DEFAULT_CURSOR_IDLE_S

    fun setCursorIdleSeconds(value: Int) = putInt(KEY_CURSOR_IDLE_S, value)

    /** Multiplier on two-finger scroll vScroll. */
    fun scrollSensitivity(): Float =
        if (::prefs.isInitialized) prefs.getFloat(KEY_SCROLL_SENSITIVITY, DEFAULT_SCROLL_SENSITIVITY)
        else DEFAULT_SCROLL_SENSITIVITY

    fun setScrollSensitivity(value: Float) = putFloat(KEY_SCROLL_SENSITIVITY, value)

    /** Cap on auto-scroll velocity (pad-fractions per ms). */
    fun flickSensitivity(): Float =
        if (::prefs.isInitialized) prefs.getFloat(KEY_FLICK_SENSITIVITY, DEFAULT_FLICK_SENSITIVITY)
        else DEFAULT_FLICK_SENSITIVITY

    fun setFlickSensitivity(value: Float) = putFloat(KEY_FLICK_SENSITIVITY, value)

    /** Press-and-hold duration that fires a long-press, in milliseconds. */
    fun longPressMs(): Long =
        if (::prefs.isInitialized) prefs.getLong(KEY_LONG_PRESS_MS, DEFAULT_LONG_PRESS_MS)
        else DEFAULT_LONG_PRESS_MS

    fun setLongPressMs(value: Long) = putLong(KEY_LONG_PRESS_MS, value)

    /** Whether two-finger pinch zooms the workspace. */
    fun pinchEnabled(): Boolean =
        if (::prefs.isInitialized) prefs.getBoolean(KEY_PINCH_ENABLED, true) else true

    fun setPinchEnabled(value: Boolean) = putBoolean(KEY_PINCH_ENABLED, value)

    /** Whether unlocking (PINNED → FREE) auto-recenters the view to the head. */
    fun autoRecenterOnUnlock(): Boolean =
        if (::prefs.isInitialized) prefs.getBoolean(KEY_AUTO_RECENTER_ON_UNLOCK, true) else true

    fun setAutoRecenterOnUnlock(value: Boolean) = putBoolean(KEY_AUTO_RECENTER_ON_UNLOCK, value)

    /**
     * Carina 6DOF head tracking (positional parallax) vs 3DOF (orientation only). Default
     * 3DOF — the lighter, orientation-only path that matches the reliable baseline; turn on
     * for head-translation parallax. Applies on the next glasses reconnect.
     */
    fun carina6Dof(): Boolean =
        if (::prefs.isInitialized) prefs.getBoolean(KEY_CARINA_6DOF, false) else false

    fun setCarina6Dof(value: Boolean) = putBoolean(KEY_CARINA_6DOF, value)

    // Windows
    fun maxWindowsPerSlot(): Int =
        if (::prefs.isInitialized) prefs.getInt(KEY_MAX_WINDOWS_PER_SLOT, DEFAULT_MAX_WINDOWS_PER_SLOT)
        else DEFAULT_MAX_WINDOWS_PER_SLOT
    fun setMaxWindowsPerSlot(v: Int) = putInt(KEY_MAX_WINDOWS_PER_SLOT, v)

    fun snapZonesEnabled(): Boolean =
        if (::prefs.isInitialized) prefs.getBoolean(KEY_SNAP_ZONES_ENABLED, true) else true
    fun setSnapZonesEnabled(v: Boolean) = putBoolean(KEY_SNAP_ZONES_ENABLED, v)

    fun resizeHandlesEnabled(): Boolean =
        if (::prefs.isInitialized) prefs.getBoolean(KEY_RESIZE_HANDLES_ENABLED, true) else true
    fun setResizeHandlesEnabled(v: Boolean) = putBoolean(KEY_RESIZE_HANDLES_ENABLED, v)

    // Capture
    fun recordingFrameInterval(): Int =
        if (::prefs.isInitialized) prefs.getInt(KEY_RECORDING_FRAME_INTERVAL, DEFAULT_RECORDING_FRAME_INTERVAL)
        else DEFAULT_RECORDING_FRAME_INTERVAL
    fun setRecordingFrameInterval(v: Int) = putInt(KEY_RECORDING_FRAME_INTERVAL, v)

    fun captureDebugOverlay(): Boolean =
        if (::prefs.isInitialized) prefs.getBoolean(KEY_CAPTURE_DEBUG_OVERLAY, true) else true
    fun setCaptureDebugOverlay(v: Boolean) = putBoolean(KEY_CAPTURE_DEBUG_OVERLAY, v)

    // Taskbar
    fun showTaskbar(): Boolean =
        if (::prefs.isInitialized) prefs.getBoolean(KEY_SHOW_TASKBAR, true) else true
    fun setShowTaskbar(v: Boolean) = putBoolean(KEY_SHOW_TASKBAR, v)

    fun showTaskbarClock(): Boolean =
        if (::prefs.isInitialized) prefs.getBoolean(KEY_SHOW_TASKBAR_CLOCK, true) else true
    fun setShowTaskbarClock(v: Boolean) = putBoolean(KEY_SHOW_TASKBAR_CLOCK, v)

    fun showTaskbarBattery(): Boolean =
        if (::prefs.isInitialized) prefs.getBoolean(KEY_SHOW_TASKBAR_BATTERY, true) else true
    fun setShowTaskbarBattery(v: Boolean) = putBoolean(KEY_SHOW_TASKBAR_BATTERY, v)

    fun showTaskbarVolume(): Boolean =
        if (::prefs.isInitialized) prefs.getBoolean(KEY_SHOW_TASKBAR_VOLUME, true) else true
    fun setShowTaskbarVolume(v: Boolean) = putBoolean(KEY_SHOW_TASKBAR_VOLUME, v)

    fun clockUse24h(): Boolean =
        if (::prefs.isInitialized) prefs.getBoolean(KEY_CLOCK_USE_24H, false) else false
    fun setClockUse24h(v: Boolean) = putBoolean(KEY_CLOCK_USE_24H, v)

    fun showTaskbarDate(): Boolean =
        if (::prefs.isInitialized) prefs.getBoolean(KEY_SHOW_TASKBAR_DATE, true) else true
    fun setShowTaskbarDate(v: Boolean) = putBoolean(KEY_SHOW_TASKBAR_DATE, v)

    /** When true, the taskbar hides on idle and reappears while the cursor sits over the slot's bottom band. */
    fun taskbarAutoHide(): Boolean =
        if (::prefs.isInitialized) prefs.getBoolean(KEY_TASKBAR_AUTO_HIDE, false) else false
    fun setTaskbarAutoHide(v: Boolean) = putBoolean(KEY_TASKBAR_AUTO_HIDE, v)

    /** Density-DPI used when creating a per-app trusted VirtualDisplay. */
    fun appDisplayDpi(): Int {
        val raw = if (::prefs.isInitialized) {
            prefs.getInt(KEY_APP_DISPLAY_DPI, DEFAULT_APP_DISPLAY_DPI)
        } else {
            DEFAULT_APP_DISPLAY_DPI
        }
        // Clamp into the current slider range so a value persisted under the old
        // wider presets (e.g. 320 / 420) maps into bounds.
        return raw.coerceIn(APP_DISPLAY_DPI_MIN, APP_DISPLAY_DPI_MAX)
    }
    fun setAppDisplayDpi(v: Int) =
        putInt(KEY_APP_DISPLAY_DPI, v.coerceIn(APP_DISPLAY_DPI_MIN, APP_DISPLAY_DPI_MAX))

    /** Wipe every value back to defaults. Used by the About tab's "Reset" button. */
    fun resetAll() {
        if (!::prefs.isInitialized) return
        prefs.edit().clear().apply()
        listeners.forEach { runCatching { it() } }
    }

    private fun putFloat(key: String, v: Float) {
        if (!::prefs.isInitialized) return
        prefs.edit().putFloat(key, v).apply()
        listeners.forEach { runCatching { it() } }
    }
    private fun putInt(key: String, v: Int) {
        if (!::prefs.isInitialized) return
        prefs.edit().putInt(key, v).apply()
        listeners.forEach { runCatching { it() } }
    }
    private fun putLong(key: String, v: Long) {
        if (!::prefs.isInitialized) return
        prefs.edit().putLong(key, v).apply()
        listeners.forEach { runCatching { it() } }
    }
    private fun putBoolean(key: String, v: Boolean) {
        if (!::prefs.isInitialized) return
        prefs.edit().putBoolean(key, v).apply()
        listeners.forEach { runCatching { it() } }
    }

    // endregion

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun addChangeListener(l: () -> Unit) { listeners.add(l) }
    fun removeChangeListener(l: () -> Unit) { listeners.remove(l) }
}
