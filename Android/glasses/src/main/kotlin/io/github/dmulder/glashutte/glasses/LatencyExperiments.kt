package io.github.dmulder.glashutte.glasses

import android.content.Context

/**
 * Runtime switches for the head-tracking latency pipeline, edited from the "Glashutte
 * Diagnostics" screen so the effect of each one can be A/B tested on a real pair of glasses.
 *
 * All of them apply **live** (no tracking restart): consumers read [current] when they need a
 * value, and [HeadTracking] / the presentation re-apply the native and window settings when
 * the value changes.
 *
 * Defaults:
 *  - [Settings.pullOnRender] ON and [Settings.highestRefresh] ON: both are pure scheduling
 *    changes (a coherent pose read on the GL thread right before drawing; the display's
 *    fastest mode) that cannot make the view less accurate.
 *  - [Settings.predictionMode] OFF: prediction is the one change that can visibly overshoot,
 *    so it is opt-in. [Settings.predictionMs] is the amount used once a mode is selected.
 */
object LatencyExperiments {

    data class Settings(
        /**
         * Publish each pose as one immutable timestamped value and read it on the GL thread at
         * the start of every frame, instead of pushing four separate floats from a poll loop.
         */
        val pullOnRender: Boolean = DEFAULT_PULL_ON_RENDER,
        val predictionMode: PredictionMode = DEFAULT_PREDICTION_MODE,
        /**
         * Look-ahead in milliseconds: the SDK's `predict_time` in [PredictionMode.SDK], the
         * fixed part of the app-side look-ahead in [PredictionMode.APP] (pose age is added).
         */
        val predictionMs: Int = DEFAULT_PREDICTION_MS,
        /** Ask for the glasses display's highest refresh rate at its current resolution. */
        val highestRefresh: Boolean = DEFAULT_HIGHEST_REFRESH,
    ) {
        /** Seconds handed to the SDK; zero unless the SDK is doing the predicting. */
        val sdkPredictSeconds: Double
            get() = if (predictionMode == PredictionMode.SDK) predictionMs / 1000.0 else 0.0

        /** App-side extrapolation only runs when the pose is pulled on the render thread. */
        val appExtrapolation: Boolean get() = pullOnRender && predictionMode == PredictionMode.APP

        /** Native pose poll period: tighter when the render thread samples it directly. */
        val nativePollIntervalMicros: Int
            get() = if (pullOnRender) PULL_POLL_INTERVAL_US else LEGACY_POLL_INTERVAL_US

        fun describe(): String =
            "pullOnRender=$pullOnRender prediction=${predictionMode.name}" +
                (if (predictionMode == PredictionMode.OFF) "" else "(${predictionMs}ms)") +
                (if (predictionMode == PredictionMode.APP && !pullOnRender) "[inactive: needs pullOnRender]" else "") +
                " highestRefresh=$highestRefresh"
    }

    const val DEFAULT_PULL_ON_RENDER = true
    val DEFAULT_PREDICTION_MODE = PredictionMode.OFF
    const val DEFAULT_PREDICTION_MS = 10
    const val DEFAULT_HIGHEST_REFRESH = true

    /** The amounts offered in the diagnostics UI. */
    val PREDICTION_CHOICES_MS = intArrayOf(0, 5, 10, 15, 20, 30, 40)

    /** ~120 Hz native poll, as before this feature existed. */
    const val LEGACY_POLL_INTERVAL_US = 8333

    /** 250 Hz: the render thread reads the newest sample, so a faster poll means a fresher one. */
    const val PULL_POLL_INTERVAL_US = 4000

    private const val PREFS = "glashutte_latency_experiments"
    private const val KEY_PULL = "pull_on_render"
    private const val KEY_MODE = "prediction_mode"
    private const val KEY_MS = "prediction_ms"
    private const val KEY_REFRESH = "highest_refresh"

    @Volatile private var settings = Settings()
    @Volatile private var loaded = false

    /** The active settings (defaults until [init] has run). Cheap; safe from any thread. */
    fun current(): Settings = settings

    /** Load the persisted settings once per process. Safe to call repeatedly. */
    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            settings = read(context)
            loaded = true
        }
    }

    /** Change one or more settings, persist them and make them live. */
    fun update(context: Context, change: (Settings) -> Settings) {
        init(context)
        val next = synchronized(this) {
            val value = change(settings)
            settings = value
            value
        }
        prefs(context).edit()
            .putBoolean(KEY_PULL, next.pullOnRender)
            .putString(KEY_MODE, next.predictionMode.name)
            .putInt(KEY_MS, next.predictionMs)
            .putBoolean(KEY_REFRESH, next.highestRefresh)
            .apply()
        GlassesDiagnostics.i(GlassesDiagnostics.TAG, "latency settings changed: ${next.describe()}")
    }

    /** Test hook: replace the in-memory settings without touching storage. */
    internal fun setForTest(value: Settings) {
        settings = value
        loaded = true
    }

    private fun read(context: Context): Settings {
        val p = prefs(context)
        val mode = p.getString(KEY_MODE, null)
            ?.let { name -> runCatching { PredictionMode.valueOf(name) }.getOrNull() }
            ?: DEFAULT_PREDICTION_MODE
        return Settings(
            pullOnRender = p.getBoolean(KEY_PULL, DEFAULT_PULL_ON_RENDER),
            predictionMode = mode,
            predictionMs = p.getInt(KEY_MS, DEFAULT_PREDICTION_MS).coerceIn(0, 100),
            highestRefresh = p.getBoolean(KEY_REFRESH, DEFAULT_HIGHEST_REFRESH),
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
