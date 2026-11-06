package io.github.dmulder.glashutte.glasses

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One immutable head-pose sample: orientation (w, x, y, z), optional 6DoF position, the time
 * it was captured and the head's angular velocity at that moment.
 *
 * Immutability is the point: a reader that holds a [TimedPose] always sees one coherent
 * sample, never a quaternion whose components come from two different updates.
 */
class TimedPose(
    val w: Float,
    val x: Float,
    val y: Float,
    val z: Float,
    val hasPosition: Boolean,
    val px: Float,
    val py: Float,
    val pz: Float,
    /** Capture time on the `System.nanoTime()` clock. */
    val timestampNanos: Long,
    /** Angular velocity in rad/s about the world axes (the frame the quaternion is in). */
    val wx: Float,
    val wy: Float,
    val wz: Float,
    /** Monotonic publish counter; lets a consumer tell "new sample" from "same sample again". */
    val sequence: Long,
) {
    val angularSpeed: Float get() = sqrt(wx * wx + wy * wy + wz * wz)
}

/**
 * Single-slot, thread-safe hand-off of the newest [TimedPose].
 *
 * [publish] stamps each sample, derives its angular velocity from a short history and swaps
 * the whole sample in with one atomic reference write; [latest] is a lock-free read. Pure
 * Kotlin (no Android types) so the maths is unit-tested on the JVM.
 *
 * Velocity is measured across at least [velocityWindowNanos] of history rather than between
 * two adjacent samples: at 120 Hz and above the quaternion quantisation noise between
 * neighbours would otherwise dominate the estimate.
 */
class PoseBuffer(private val velocityWindowNanos: Long = DEFAULT_VELOCITY_WINDOW_NANOS) {

    private class Entry(val t: Long, val w: Float, val x: Float, val y: Float, val z: Float)

    private val current = AtomicReference<TimedPose?>(null)
    private val lock = Any()
    private val history = ArrayDeque<Entry>()
    private var sequence = 0L

    /** The newest published sample, or null before the first [publish] / after [reset]. */
    fun latest(): TimedPose? = current.get()

    /** Forget all history (new tracking session, or the recentre reference changed). */
    fun reset() = synchronized(lock) {
        history.clear()
        current.set(null)
    }

    /**
     * Publish a sample captured at [timestampNanos]. A sample that is not newer than the
     * current one (a duplicate pull of the same sensor reading, or a clock glitch) is ignored
     * and the existing sample returned, so callers can pull every frame without special-casing.
     */
    fun publish(
        w: Float, x: Float, y: Float, z: Float,
        hasPosition: Boolean, px: Float, py: Float, pz: Float,
        timestampNanos: Long,
    ): TimedPose = synchronized(lock) {
        val prev = current.get()
        if (prev != null && timestampNanos <= prev.timestampNanos) return prev

        history.addLast(Entry(timestampNanos, w, x, y, z))
        // Keep one entry older than the window so a reference is always available, drop the rest.
        val horizon = timestampNanos - 2 * velocityWindowNanos
        while (history.size > 2 && history[1].t < horizon) history.removeFirst()

        val v = FloatArray(3)
        estimateVelocity(v)
        val pose = TimedPose(
            w, x, y, z, hasPosition, px, py, pz, timestampNanos, v[0], v[1], v[2], ++sequence,
        )
        current.set(pose)
        pose
    }

    private fun estimateVelocity(out: FloatArray) {
        out[0] = 0f; out[1] = 0f; out[2] = 0f
        if (history.size < 2) return
        val cur = history.last()
        var ref: Entry? = null
        for (i in history.size - 2 downTo 0) {
            val e = history[i]
            if (cur.t - e.t >= velocityWindowNanos) { ref = e; break }
        }
        // Not enough history for a full window yet: use the oldest sample if it is far enough
        // back to give a meaningful rate, otherwise report "unknown" (zero).
        if (ref == null) {
            val oldest = history.first()
            if (cur.t - oldest.t >= MIN_VELOCITY_SPAN_NANOS) ref = oldest
        }
        if (ref == null) return
        val dt = (cur.t - ref.t) / 1e9
        // Relative world-frame rotation: cur = rel * ref  =>  rel = cur * conj(ref).
        var rw = cur.w * ref.w + cur.x * ref.x + cur.y * ref.y + cur.z * ref.z
        var rx = -cur.w * ref.x + cur.x * ref.w - cur.y * ref.z + cur.z * ref.y
        var ry = -cur.w * ref.y + cur.x * ref.z + cur.y * ref.w - cur.z * ref.x
        var rz = -cur.w * ref.z - cur.x * ref.y + cur.y * ref.x + cur.z * ref.w
        if (rw < 0f) { rw = -rw; rx = -rx; ry = -ry; rz = -rz }
        val vn = sqrt(rx * rx + ry * ry + rz * rz)
        if (vn < 1e-9f) return
        val angle = 2.0 * atan2(vn.toDouble(), rw.toDouble())
        val rate = angle / dt
        // A jump that fast is a re-anchor or a sensor glitch, not a head turn: extrapolating it
        // would fling the view, so report no motion instead.
        if (rate > MAX_ANGULAR_RATE_RAD_S) return
        val k = (rate / vn).toFloat()
        out[0] = rx * k
        out[1] = ry * k
        out[2] = rz * k
    }

    companion object {
        /** About one 60 Hz frame: long enough to average out sensor quantisation. */
        const val DEFAULT_VELOCITY_WINDOW_NANOS = 16_000_000L
        const val MIN_VELOCITY_SPAN_NANOS = 2_000_000L

        /** ~1700 deg/s; no human head turn is faster, so anything above is treated as a glitch. */
        const val MAX_ANGULAR_RATE_RAD_S = 30.0
    }
}

/** How the view tries to compensate for pipeline latency. Selectable at runtime. */
enum class PredictionMode(val label: String) {
    /** No prediction: render the newest pose as reported. */
    OFF("Off (render newest pose as-is)"),

    /** Ask the glasses SDK to predict ahead (its `predict_time` parameter); the app adds nothing. */
    SDK("SDK predict_time"),

    /** App-side: rotate the newest pose forward by angular velocity x (amount + pose age). */
    APP("App extrapolation (amount + pose age)"),
}

/**
 * App-side look-ahead: rotates a [TimedPose] forward along its measured angular velocity.
 *
 * The look-ahead is `amount + age`, where `age` is how old the sample already is when it is
 * used, so a stale sample is projected further than a fresh one and the displayed orientation
 * lands on "now + amount" either way. Two guards keep it from making things worse:
 *  - the look-ahead is capped at [MAX_LOOKAHEAD_NANOS], so a stalled stream cannot extrapolate
 *    far past the last real sample;
 *  - the velocity is faded in between [DEAD_BAND_RAD_S] and [FULL_GAIN_RAD_S], so sensor noise
 *    while the head is at rest never turns into visible jitter.
 */
object PoseExtrapolator {

    const val MAX_LOOKAHEAD_NANOS = 40_000_000L
    const val DEAD_BAND_RAD_S = 0.03f   // ~1.7 deg/s
    const val FULL_GAIN_RAD_S = 0.15f   // ~8.6 deg/s

    /** `amountMs` plus the sample's age, clamped to `0..MAX_LOOKAHEAD_NANOS`. */
    fun lookaheadNanos(ageNanos: Long, amountMs: Float): Long {
        val total = (amountMs * 1_000_000f).toLong() + maxOf(0L, ageNanos)
        return total.coerceIn(0L, MAX_LOOKAHEAD_NANOS)
    }

    /**
     * Write the orientation (w, x, y, z) of [pose] advanced by [lookaheadNanos] into [out].
     * Returns the effective look-ahead in nanoseconds (0 when the pose was copied unchanged).
     */
    fun extrapolate(pose: TimedPose, lookaheadNanos: Long, out: FloatArray): Long {
        out[0] = pose.w; out[1] = pose.x; out[2] = pose.y; out[3] = pose.z
        if (lookaheadNanos <= 0L) return 0L
        val speed = pose.angularSpeed
        val gain = ((speed - DEAD_BAND_RAD_S) / (FULL_GAIN_RAD_S - DEAD_BAND_RAD_S)).coerceIn(0f, 1f)
        if (gain <= 0f) return 0L
        val angle = speed.toDouble() * gain * (lookaheadNanos / 1e9)
        val s = (sin(angle / 2.0) / speed).toFloat()
        val dw = cos(angle / 2.0).toFloat()
        val dx = pose.wx * s
        val dy = pose.wy * s
        val dz = pose.wz * s
        // out = dq * pose, then renormalise.
        val w = dw * pose.w - dx * pose.x - dy * pose.y - dz * pose.z
        val x = dw * pose.x + dx * pose.w + dy * pose.z - dz * pose.y
        val y = dw * pose.y - dx * pose.z + dy * pose.w + dz * pose.x
        val z = dw * pose.z + dx * pose.y - dy * pose.x + dz * pose.w
        val n = sqrt(w * w + x * x + y * y + z * z)
        if (n < 1e-6f) return 0L
        out[0] = w / n; out[1] = x / n; out[2] = y / n; out[3] = z / n
        return lookaheadNanos
    }

    /** Angle in radians between two unit quaternions given as (w, x, y, z) arrays. */
    fun angleBetween(a: FloatArray, b: FloatArray): Double {
        val dot = abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]).coerceAtMost(1f)
        return 2.0 * kotlin.math.acos(dot.toDouble())
    }
}

/** What the render thread gets for one frame; owned and reused by the caller, never allocated per frame. */
class PulledPose {
    @JvmField var w = 1f
    @JvmField var x = 0f
    @JvmField var y = 0f
    @JvmField var z = 0f
    @JvmField var hasPosition = false
    @JvmField var px = 0f
    @JvmField var py = 0f
    @JvmField var pz = 0f

    /** Sequence of the underlying sample; unchanged between frames means "no new sample". */
    @JvmField var sequence = 0L

    /** How old the underlying sample was when pulled. */
    @JvmField var ageNanos = 0L

    /** Look-ahead that was applied (0 when no prediction ran). */
    @JvmField var lookaheadNanos = 0L
}

/** Where the render thread gets the head pose from, once per frame. */
fun interface HeadPoseSource {
    /**
     * Fill [out] with the pose to render with at [nowNanos] (`System.nanoTime()` clock). Returns
     * false when this source does not drive the view (pull-on-render is off, or no pose has
     * arrived), in which case the renderer keeps whatever was pushed to it.
     */
    fun pull(nowNanos: Long, out: PulledPose): Boolean
}
