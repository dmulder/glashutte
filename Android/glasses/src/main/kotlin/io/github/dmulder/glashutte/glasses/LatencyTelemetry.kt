package io.github.dmulder.glashutte.glasses

import android.util.Log

/** Fixed-size window of samples with percentile queries; not thread-safe. */
class RollingStats(private val capacity: Int = 256) {
    private val values = FloatArray(capacity)
    private var next = 0
    var count = 0
        private set

    fun add(v: Float) {
        values[next] = v
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    fun clear() {
        next = 0
        count = 0
    }

    fun max(): Float = if (count == 0) 0f else (0 until count).maxOf { values[it] }

    fun mean(): Float = if (count == 0) 0f else (0 until count).sumOf { values[it].toDouble() }.toFloat() / count

    /** Nearest-rank percentile, [p] in 0..100. Returns 0 when empty. */
    fun percentile(p: Float): Float {
        if (count == 0) return 0f
        val sorted = values.copyOf(count).also { it.sort() }
        val rank = Math.ceil(p / 100.0 * count).toInt().coerceIn(1, count)
        return sorted[rank - 1]
    }
}

/**
 * Per-frame latency numbers the owner needs to A/B the latency settings on a device: the
 * render cadence (does the display really run at the selected rate, and does the GL loop hold
 * it) and how old the pose is when a frame uses it.
 *
 * The GL thread records; [heartbeat] (called from the tracking supervisor loop) summarises
 * every few seconds into one `GlashutteDiag` logcat line plus the diagnostics report.
 */
object LatencyTelemetry {

    class Summary(
        val frames: Int,
        val fps: Float,
        val dtP50Ms: Float,
        val dtP95Ms: Float,
        val dtMaxMs: Float,
        val poseSamples: Int,
        val ageP50Ms: Float,
        val ageP95Ms: Float,
        val ageMaxMs: Float,
        val meanLookaheadMs: Float,
    )

    private const val HEARTBEAT_MS = 5000L
    private const val PAUSE_GAP_NANOS = 1_000_000_000L

    private val lock = Any()
    private val frameDtMs = RollingStats(512)
    private val ageMs = RollingStats(512)
    private var lookaheadSumMs = 0.0
    private var lookaheadCount = 0
    private var lastFrameNanos = 0L
    private var windowStartNanos = 0L
    private var framesInWindow = 0

    /** The glasses display's active mode, filled in by the presentation. */
    @Volatile var displayInfo: String = "display: not known yet"

    private var lastHeartbeatMs = 0L
    private var lastNativeOk = -1L
    private var lastNativeAtMs = 0L

    /** GL thread, once per frame. */
    fun onFrame(nowNanos: Long) = synchronized(lock) {
        if (lastFrameNanos != 0L) {
            val dt = nowNanos - lastFrameNanos
            if (dt in 1 until PAUSE_GAP_NANOS) {
                frameDtMs.add(dt / 1e6f)
                framesInWindow++
            }
        }
        if (windowStartNanos == 0L) windowStartNanos = nowNanos
        lastFrameNanos = nowNanos
    }

    /** GL thread: the pose used for this frame was [ageNanos] old and extrapolated by [lookaheadNanos]. */
    fun onPoseUsed(ageNanos: Long, lookaheadNanos: Long) = synchronized(lock) {
        if (ageNanos < 0L) return@synchronized
        ageMs.add(ageNanos / 1e6f)
        lookaheadSumMs += lookaheadNanos / 1e6
        lookaheadCount++
    }

    /** Summarise everything recorded since the previous call, then start a fresh window. */
    fun takeSummary(nowNanos: Long): Summary = synchronized(lock) {
        val elapsed = (nowNanos - windowStartNanos).coerceAtLeast(1L) / 1e9f
        val s = Summary(
            frames = frameDtMs.count,
            fps = if (windowStartNanos == 0L) 0f else framesInWindow / elapsed,
            dtP50Ms = frameDtMs.percentile(50f),
            dtP95Ms = frameDtMs.percentile(95f),
            dtMaxMs = frameDtMs.max(),
            poseSamples = ageMs.count,
            ageP50Ms = ageMs.percentile(50f),
            ageP95Ms = ageMs.percentile(95f),
            ageMaxMs = ageMs.max(),
            meanLookaheadMs = if (lookaheadCount == 0) 0f else (lookaheadSumMs / lookaheadCount).toFloat(),
        )
        frameDtMs.clear()
        ageMs.clear()
        lookaheadSumMs = 0.0
        lookaheadCount = 0
        framesInWindow = 0
        windowStartNanos = nowNanos
        s
    }

    fun format(
        settings: LatencyExperiments.Settings,
        display: String,
        s: Summary,
        nativeHz: Float,
    ): String =
        "latency: {${settings.describe()}} $display | render %.1f fps dt p50=%.1f p95=%.1f max=%.1f ms | ".format(
            s.fps, s.dtP50Ms, s.dtP95Ms, s.dtMaxMs,
        ) + "pose age p50=%.1f p95=%.1f max=%.1f ms (n=%d) lookahead=%.1f ms | sdk pose rate %s".format(
            s.ageP50Ms, s.ageP95Ms, s.ageMaxMs, s.poseSamples, s.meanLookaheadMs,
            if (nativeHz > 0f) "%.0f Hz".format(nativeHz) else "n/a",
        )

    /** Log the active settings and current numbers under the `GlashutteDiag` tag, at most every 5 s. */
    fun heartbeat(nowMs: Long, nowNanos: Long, settings: LatencyExperiments.Settings) {
        if (nowMs - lastHeartbeatMs < HEARTBEAT_MS) return
        lastHeartbeatMs = nowMs
        val ok = GlassesDiagnostics.nativeStats["poll_ok"]?.toLongOrNull() ?: -1L
        var hz = 0f
        if (ok >= 0 && lastNativeOk >= 0 && nowMs > lastNativeAtMs) {
            hz = (ok - lastNativeOk) * 1000f / (nowMs - lastNativeAtMs)
        }
        lastNativeOk = ok
        lastNativeAtMs = nowMs
        val line = format(settings, displayInfo, takeSummary(nowNanos), hz)
        Log.i(GlassesDiagnostics.TAG, line)
        GlassesDiagnostics.put("latency", line)
    }
}
