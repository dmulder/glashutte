package io.github.dmulder.glashutte.glasses

import android.content.Context

/**
 * Switches for narrowing down an input-injection failure on a real device, edited from the
 * diagnostics screen and stored in their own SharedPreferences file. The defaults are the
 * behaviour the app ships with; the A/B switches only matter once someone changes them.
 */
object InputExperiments {

    /** How the shell-uid helper reaches `InputManager.injectInputEvent`. */
    enum class Strategy(val label: String, val wire: Int, val routeName: String) {
        AUTO("Auto (first one that works)", 0, "auto"),
        SERVICE_CONTEXT("InputManager from the helper's system context", 1, "service-context"),
        GLOBAL("InputManagerGlobal.getInstance() (Android 14+)", 2, "global"),
        LEGACY("InputManager.getInstance() (what upstream used)", 3, "legacy-getInstance"),
        BINDER("IInputManager binder from ServiceManager", 4, "binder"),
        SHELL_INPUT("`input motionevent` shell command (touch only)", 5, "shell-input"),
        ;

        companion object {
            fun fromWire(value: Int): Strategy = values().firstOrNull { it.wire == value } ?: AUTO
        }
    }

    private const val PREFS = "glashutte_input_experiments"
    private const val KEY_STRATEGY = "strategy"
    private const val KEY_WAIT = "wait_for_result"
    private const val KEY_OWN_FOCUS = "own_focus_flag"
    private const val KEY_FORWARD_KEYS = "forward_physical_keys"
    private const val KEY_MOUSE_FALLBACK = "mouse_phone_window_fallback"

    @Volatile private var cachedStrategy: Strategy? = null
    @Volatile private var cachedWait: Boolean? = null
    @Volatile private var cachedOwnFocus: Boolean? = null
    @Volatile private var cachedForwardKeys: Boolean? = null
    @Volatile private var cachedMouseFallback: Boolean? = null

    fun strategy(context: Context): Strategy =
        cachedStrategy ?: run {
            val name = prefs(context).getString(KEY_STRATEGY, null)
            runCatching { Strategy.valueOf(name ?: "AUTO") }.getOrDefault(Strategy.AUTO)
        }.also { cachedStrategy = it }

    fun setStrategy(context: Context, value: Strategy) {
        cachedStrategy = value
        prefs(context).edit().putString(KEY_STRATEGY, value.name).apply()
    }

    /**
     * Inject with the dispatcher's "wait for result" mode instead of fire-and-forget. With
     * fire-and-forget the call reports success even when the event is dropped because no
     * window can take it; with this on, a dropped touch comes back as a failure and is shown
     * in the report. Default ON.
     */
    fun waitForResult(context: Context): Boolean =
        cachedWait ?: prefs(context).getBoolean(KEY_WAIT, true).also { cachedWait = it }

    fun setWaitForResult(context: Context, value: Boolean) {
        cachedWait = value
        prefs(context).edit().putBoolean(KEY_WAIT, value).apply()
    }

    /**
     * Create new app displays with `VIRTUAL_DISPLAY_FLAG_OWN_FOCUS` (the display keeps its own
     * window focus and touch mode instead of competing with the phone for the top focus).
     * Default OFF, matching upstream; applies to windows opened after the change.
     */
    fun ownFocusFlag(context: Context): Boolean =
        cachedOwnFocus ?: prefs(context).getBoolean(KEY_OWN_FOCUS, false).also { cachedOwnFocus = it }

    fun setOwnFocusFlag(context: Context, value: Boolean) {
        cachedOwnFocus = value
        prefs(context).edit().putBoolean(KEY_OWN_FOCUS, value).apply()
    }

    /**
     * Send key presses from a physical (Bluetooth) keyboard to the app window the cursor last
     * clicked in. Before this existed physical keys went only to the phone's own window and
     * never reached an app on the glasses. Default ON.
     */
    fun forwardPhysicalKeys(context: Context): Boolean =
        cachedForwardKeys ?: prefs(context).getBoolean(KEY_FORWARD_KEYS, true).also { cachedForwardKeys = it }

    fun setForwardPhysicalKeys(context: Context, value: Boolean) {
        cachedForwardKeys = value
        prefs(context).edit().putBoolean(KEY_FORWARD_KEYS, value).apply()
    }

    /**
     * Drive the workspace cursor from the mouse events the phone's own window receives when the
     * helper's raw `/dev/input` path is not delivering (helper missing, or no events read).
     * Edge-clamped like any phone pointer, so it is a fallback, not the primary path. Default ON.
     */
    fun mousePhoneWindowFallback(context: Context): Boolean =
        cachedMouseFallback ?: prefs(context).getBoolean(KEY_MOUSE_FALLBACK, true).also { cachedMouseFallback = it }

    fun setMousePhoneWindowFallback(context: Context, value: Boolean) {
        cachedMouseFallback = value
        prefs(context).edit().putBoolean(KEY_MOUSE_FALLBACK, value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
