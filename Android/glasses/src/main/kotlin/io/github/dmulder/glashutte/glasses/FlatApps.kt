package io.github.dmulder.glashutte.glasses

import android.content.Context

/**
 * How an app on the "always flat" list reaches the glasses.
 *
 *  - [FLAT_TRACKPAD]: the app runs on the glasses' own display (native output, flat, so the
 *    platform's HDCP and secure-layer path apply) and the phone is a trackpad that injects
 *    input into that display through the shell helper.
 *  - [MIRROR]: the app runs on the phone's built-in display and the glasses mirror the phone
 *    (touch works natively on the phone; the phone screen is busy, no trackpad).
 */
enum class FlatMode(val label: String, val wire: String) {
    FLAT_TRACKPAD("Flat + trackpad", "flat"),
    MIRROR("Mirror phone screen", "mirror"),
    ;

    companion object {
        fun fromWire(value: String?): FlatMode? = values().firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

/** One row of the always-flat list. [mode] `null` means "use the global default mode". */
data class FlatAppEntry(val packageName: String, val label: String, val mode: FlatMode? = null)

/**
 * Text format of the shipped defaults (`assets/flat_apps_default.txt`): one app per line,
 * `package|Label|mode`, with `mode` optional (`flat` or `mirror`). `#` starts a comment.
 * The list is data on purpose: edit the file, not the routing logic.
 */
object FlatAppsFormat {
    fun parse(text: String): List<FlatAppEntry> =
        text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split('|').map { it.trim() }
                val pkg = parts.getOrNull(0)?.takeIf { isPackageName(it) } ?: return@mapNotNull null
                FlatAppEntry(
                    packageName = pkg,
                    label = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: pkg,
                    mode = FlatMode.fromWire(parts.getOrNull(2)),
                )
            }
            .distinctBy { it.packageName }
            .toList()

    fun isPackageName(value: String): Boolean =
        value.length in 3..200 && PACKAGE_REGEX.matches(value)

    private val PACKAGE_REGEX = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
}

/**
 * What the user changed relative to the shipped defaults. Storing the *difference* (not a
 * full copy) means an updated defaults file still reaches users who never touched an entry.
 */
data class FlatAppState(
    val defaults: List<FlatAppEntry>,
    val added: Map<String, FlatAppEntry> = emptyMap(),
    val removed: Set<String> = emptySet(),
    val modeOverrides: Map<String, FlatMode> = emptyMap(),
)

/** Pure resolution of [FlatAppState] — the unit-tested core of [FlatAppStore]. */
object FlatAppResolver {
    fun effective(state: FlatAppState): List<FlatAppEntry> {
        val byPackage = LinkedHashMap<String, FlatAppEntry>()
        state.defaults.filter { it.packageName !in state.removed }.forEach { byPackage[it.packageName] = it }
        state.added.values.forEach { byPackage[it.packageName] = it }
        return byPackage.values.map { entry ->
            state.modeOverrides[entry.packageName]?.let { entry.copy(mode = it) } ?: entry
        }
    }

    fun find(state: FlatAppState, packageName: String): FlatAppEntry? =
        effective(state).firstOrNull { it.packageName == packageName }

    fun isFlat(state: FlatAppState, packageName: String): Boolean = find(state, packageName) != null

    fun modeFor(state: FlatAppState, packageName: String, globalDefault: FlatMode): FlatMode =
        find(state, packageName)?.mode ?: globalDefault

    fun withAdded(state: FlatAppState, entry: FlatAppEntry): FlatAppState =
        state.copy(
            added = state.added + (entry.packageName to entry),
            removed = state.removed - entry.packageName,
        )

    fun withRemoved(state: FlatAppState, packageName: String): FlatAppState =
        state.copy(
            added = state.added - packageName,
            removed = state.removed + packageName,
            modeOverrides = state.modeOverrides - packageName,
        )

    fun withMode(state: FlatAppState, packageName: String, mode: FlatMode): FlatAppState =
        state.copy(modeOverrides = state.modeOverrides + (packageName to mode))
}

/**
 * The persisted, editable "always flat" list. Apps on it always launch through the native
 * handoff (see the app module's `NativeHandoff`) instead of the head-tracked workspace.
 *
 * Defaults come from `assets/flat_apps_default.txt`; the user's additions, removals and
 * per-app mode choices are stored as a difference in their own SharedPreferences file.
 * Routing decisions are logged under the `GlashutteDiag` tag.
 */
object FlatAppStore {
    private const val TAG = GlassesDiagnostics.TAG
    private const val ASSET = "flat_apps_default.txt"
    private const val PREFS = "glashutte_flat_apps"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DEFAULT_MODE = "default_mode"
    private const val KEY_ADDED = "added"
    private const val KEY_REMOVED = "removed"
    private const val KEY_MODES = "modes"

    @Volatile private var appContext: Context? = null
    @Volatile private var defaults: List<FlatAppEntry> = emptyList()
    @Volatile private var state = FlatAppState(emptyList())
    @Volatile private var enabledFlag = true
    @Volatile private var defaultModeValue = FlatMode.FLAT_TRACKPAD
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        defaults = runCatching {
            app.assets.open(ASSET).bufferedReader().use { FlatAppsFormat.parse(it.readText()) }
        }.onFailure { GlassesDiagnostics.w(TAG, "flat-app defaults unreadable: $it") }.getOrDefault(emptyList())
        val prefs = prefs(app)
        enabledFlag = prefs.getBoolean(KEY_ENABLED, true)
        defaultModeValue = FlatMode.fromWire(prefs.getString(KEY_DEFAULT_MODE, null)) ?: FlatMode.FLAT_TRACKPAD
        state = FlatAppState(
            defaults = defaults,
            added = (prefs.getStringSet(KEY_ADDED, emptySet()) ?: emptySet())
                .mapNotNull { decodeAdded(it) }.associateBy { it.packageName },
            removed = prefs.getStringSet(KEY_REMOVED, emptySet()) ?: emptySet(),
            modeOverrides = (prefs.getStringSet(KEY_MODES, emptySet()) ?: emptySet())
                .mapNotNull { row ->
                    val (pkg, mode) = row.split('|', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                    FlatMode.fromWire(mode)?.let { pkg to it }
                }.toMap(),
        )
        GlassesDiagnostics.i(
            TAG,
            "flat-app list loaded: ${defaults.size} defaults, ${state.added.size} added, " +
                "${state.removed.size} removed, enabled=$enabledFlag defaultMode=${defaultModeValue.wire}",
        )
    }

    fun addChangeListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeChangeListener(listener: () -> Unit) { listeners.remove(listener) }

    /** Master switch; off means every app opens in the workspace regardless of the list. */
    var enabled: Boolean
        get() = enabledFlag
        set(value) {
            enabledFlag = value
            appContext?.let { prefs(it).edit().putBoolean(KEY_ENABLED, value).apply() }
            changed("flat routing ${if (value) "enabled" else "disabled"}")
        }

    /** Mode used by listed apps that have no explicit mode. */
    var defaultMode: FlatMode
        get() = defaultModeValue
        set(value) {
            defaultModeValue = value
            appContext?.let { prefs(it).edit().putString(KEY_DEFAULT_MODE, value.wire).apply() }
            changed("default flat mode set to ${value.wire}")
        }

    fun entries(): List<FlatAppEntry> = FlatAppResolver.effective(state)

    fun contains(packageName: String): Boolean = FlatAppResolver.isFlat(state, packageName)

    /** Whether [packageName] should be routed flat right now (list membership and master switch). */
    fun shouldRouteFlat(packageName: String): Boolean = enabledFlag && contains(packageName)

    fun modeFor(packageName: String): FlatMode = FlatAppResolver.modeFor(state, packageName, defaultModeValue)

    fun isShippedDefault(packageName: String): Boolean = defaults.any { it.packageName == packageName }

    fun add(packageName: String, label: String, mode: FlatMode? = null) {
        if (!FlatAppsFormat.isPackageName(packageName)) return
        update(FlatAppResolver.withAdded(state, FlatAppEntry(packageName, label.ifBlank { packageName }, mode))) {
            "added $packageName to the always-flat list"
        }
    }

    fun remove(packageName: String) {
        update(FlatAppResolver.withRemoved(state, packageName)) { "removed $packageName from the always-flat list" }
    }

    fun setMode(packageName: String, mode: FlatMode) {
        if (!contains(packageName)) return
        update(FlatAppResolver.withMode(state, packageName, mode)) { "$packageName flat mode set to ${mode.wire}" }
    }

    fun resetToDefaults() {
        update(FlatAppState(defaults)) { "always-flat list reset to the shipped defaults" }
    }

    /** Record that a launch was routed flat because of the list. Visible with `logcat -s GlashutteDiag`. */
    fun logRouted(packageName: String, mode: FlatMode, how: String) {
        GlassesDiagnostics.i(
            TAG,
            "FLAT-ROUTE: $packageName routed flat because it is on the always-flat list " +
                "(${if (isShippedDefault(packageName)) "shipped default" else "user-added"}, mode=${mode.wire}) -> $how",
        )
    }

    private fun update(next: FlatAppState, message: () -> String) {
        state = next
        val app = appContext
        if (app != null) {
            prefs(app).edit()
                .putStringSet(KEY_ADDED, next.added.values.map { encodeAdded(it) }.toSet())
                .putStringSet(KEY_REMOVED, next.removed)
                .putStringSet(KEY_MODES, next.modeOverrides.map { (pkg, mode) -> "$pkg|${mode.wire}" }.toSet())
                .apply()
        }
        changed(message())
    }

    private fun changed(message: String) {
        GlassesDiagnostics.i(TAG, "flat-app list: $message")
        listeners.forEach { runCatching { it() } }
    }

    private fun encodeAdded(entry: FlatAppEntry) =
        "${entry.packageName}|${entry.label.replace('|', ' ')}|${entry.mode?.wire ?: ""}"

    private fun decodeAdded(row: String): FlatAppEntry? = FlatAppsFormat.parse(row).firstOrNull()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
