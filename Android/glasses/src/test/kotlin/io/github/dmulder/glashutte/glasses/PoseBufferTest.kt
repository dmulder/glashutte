package io.github.dmulder.glashutte.glasses

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseBufferTest {

    private val ms = 1_000_000L

    /** Quaternion (w, x, y, z) for a rotation of [rad] about world Y. */
    private fun yaw(rad: Double) = floatArrayOf(cos(rad / 2).toFloat(), 0f, sin(rad / 2).toFloat(), 0f)

    private fun PoseBuffer.publishYaw(rad: Double, tNanos: Long): TimedPose {
        val q = yaw(rad)
        return publish(q[0], q[1], q[2], q[3], false, 0f, 0f, 0f, tNanos)
    }

    /** Feed a constant-rate yaw turn sampled every [stepMs] and return the last pose. */
    private fun PoseBuffer.turn(rateRadS: Double, stepMs: Int, samples: Int, startNanos: Long = 1_000 * ms): TimedPose {
        var last: TimedPose? = null
        for (i in 0 until samples) {
            val t = startNanos + i * stepMs * ms
            last = publishYaw(rateRadS * (i * stepMs / 1000.0), t)
        }
        return last!!
    }

    @Test
    fun emptyUntilFirstPublish() {
        val b = PoseBuffer()
        assertNull(b.latest())
        val p = b.publishYaw(0.0, 5 * ms)
        assertSame(p, b.latest())
        assertEquals(1L, p.sequence)
        assertEquals(5 * ms, p.timestampNanos)
    }

    @Test
    fun publishedPoseIsOneCoherentValue() {
        val b = PoseBuffer()
        val q = yaw(0.7)
        b.publish(q[0], q[1], q[2], q[3], true, 1f, 2f, 3f, 10 * ms)
        val p = b.latest()!!
        assertEquals(q[0], p.w, 0f)
        assertEquals(q[2], p.y, 0f)
        assertTrue(p.hasPosition)
        assertEquals(2f, p.py, 0f)
    }

    @Test
    fun duplicateOrOlderSampleIsIgnored() {
        val b = PoseBuffer()
        val first = b.publishYaw(0.1, 10 * ms)
        assertSame(first, b.publishYaw(0.9, 10 * ms))
        assertSame(first, b.publishYaw(0.9, 4 * ms))
        assertSame(first, b.latest())
        val next = b.publishYaw(0.2, 11 * ms)
        assertEquals(2L, next.sequence)
    }

    @Test
    fun resetForgetsHistory() {
        val b = PoseBuffer()
        val before = b.turn(1.0, 8, 10)
        b.reset()
        assertNull(b.latest())
        // Older timestamp than before the reset is accepted again, and no velocity carries over.
        val p = b.publishYaw(2.0, 5 * ms)
        assertEquals(0f, p.angularSpeed, 0f)
        assertTrue(p.sequence > before.sequence)
    }

    @Test
    fun steadyTurnReportsItsAngularVelocity() {
        val b = PoseBuffer()
        val p = b.turn(rateRadS = 2.0, stepMs = 4, samples = 20)
        assertEquals(0f, p.wx, 1e-3f)
        assertEquals(2.0f, p.wy, 0.02f)
        assertEquals(0f, p.wz, 1e-3f)
    }

    @Test
    fun velocityIsMeasuredOverTheWindowNotAdjacentSamples() {
        // 1 ms spacing: adjacent-sample differences would be dominated by float rounding.
        val b = PoseBuffer()
        val p = b.turn(rateRadS = 1.0, stepMs = 1, samples = 60)
        assertEquals(1.0f, p.wy, 0.02f)
    }

    @Test
    fun negativeTurnHasNegativeVelocity() {
        val b = PoseBuffer()
        val p = b.turn(rateRadS = -1.5, stepMs = 8, samples = 12)
        assertEquals(-1.5f, p.wy, 0.02f)
    }

    @Test
    fun velocityHandlesQuaternionSignFlip() {
        val b = PoseBuffer()
        var last: TimedPose? = null
        for (i in 0 until 12) {
            val q = yaw(1.0 * i * 0.008)
            // q and -q are the same rotation; trackers may flip sign between samples.
            val s = if (i % 2 == 0) 1f else -1f
            last = b.publish(s * q[0], s * q[1], s * q[2], s * q[3], false, 0f, 0f, 0f, (100 + i * 8) * ms)
        }
        assertEquals(1.0f, last!!.wy, 0.03f)
    }

    @Test
    fun implausiblyFastJumpIsTreatedAsNoMotion() {
        val b = PoseBuffer()
        b.publishYaw(0.0, 100 * ms)
        b.publishYaw(0.0, 108 * ms)
        // A 2 rad jump in 8 ms is 250 rad/s: a re-anchor, not a head turn.
        val p = b.publishYaw(2.0, 124 * ms)
        assertEquals(0f, p.angularSpeed, 0f)
    }

    @Test
    fun noVelocityFromASingleSample() {
        assertEquals(0f, PoseBuffer().publishYaw(1.0, 10 * ms).angularSpeed, 0f)
    }
}

class PoseExtrapolatorTest {

    private val ms = 1_000_000L
    private val out = FloatArray(4)

    private fun yaw(rad: Double) = floatArrayOf(cos(rad / 2).toFloat(), 0f, sin(rad / 2).toFloat(), 0f)

    private fun poseTurning(yawRad: Double, rateRadS: Float, t: Long = 0L): TimedPose {
        val q = yaw(yawRad)
        return TimedPose(q[0], q[1], q[2], q[3], false, 0f, 0f, 0f, t, 0f, rateRadS, 0f, 1L)
    }

    @Test
    fun lookaheadIsAmountPlusAge() {
        assertEquals(15 * ms, PoseExtrapolator.lookaheadNanos(5 * ms, 10f))
        assertEquals(10 * ms, PoseExtrapolator.lookaheadNanos(0L, 10f))
    }

    @Test
    fun lookaheadIsCapped() {
        assertEquals(PoseExtrapolator.MAX_LOOKAHEAD_NANOS, PoseExtrapolator.lookaheadNanos(500 * ms, 10f))
        assertEquals(PoseExtrapolator.MAX_LOOKAHEAD_NANOS, PoseExtrapolator.lookaheadNanos(0L, 90f))
    }

    @Test
    fun negativeAgeIsIgnored() {
        assertEquals(10 * ms, PoseExtrapolator.lookaheadNanos(-3 * ms, 10f))
    }

    @Test
    fun zeroLookaheadLeavesPoseUnchanged() {
        val p = poseTurning(0.4, 3f)
        assertEquals(0L, PoseExtrapolator.extrapolate(p, 0L, out))
        assertEquals(p.w, out[0], 0f)
        assertEquals(p.y, out[2], 0f)
    }

    @Test
    fun extrapolatesAlongTheMeasuredRate() {
        val start = 0.4
        val p = poseTurning(start, 2f)
        val applied = PoseExtrapolator.extrapolate(p, 20 * ms, out)
        assertEquals(20 * ms, applied)
        val expected = yaw(start + 2.0 * 0.020)
        for (i in 0..3) assertEquals("component $i", expected[i], out[i], 1e-4f)
    }

    @Test
    fun resultStaysUnitLength() {
        val p = poseTurning(1.1, 8f)
        PoseExtrapolator.extrapolate(p, PoseExtrapolator.MAX_LOOKAHEAD_NANOS, out)
        val n = out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3]
        assertEquals(1f, n, 1e-5f)
    }

    @Test
    fun restingNoiseIsNotExtrapolated() {
        // 0.02 rad/s (~1 deg/s) is below the dead band: must not move the view.
        val p = poseTurning(0.3, 0.02f)
        assertEquals(0L, PoseExtrapolator.extrapolate(p, 30 * ms, out))
        assertEquals(0.0, PoseExtrapolator.angleBetween(floatArrayOf(p.w, p.x, p.y, p.z), out), 1e-6)
    }

    @Test
    fun velocityFadesInThroughTheRamp() {
        val mid = (PoseExtrapolator.DEAD_BAND_RAD_S + PoseExtrapolator.FULL_GAIN_RAD_S) / 2f
        val p = poseTurning(0.0, mid)
        PoseExtrapolator.extrapolate(p, 40 * ms, out)
        val half = PoseExtrapolator.angleBetween(floatArrayOf(p.w, p.x, p.y, p.z), out)
        // Half gain: half the angle a full-gain extrapolation would travel.
        assertEquals(mid * 0.5 * 0.040, half, 1e-4)
    }

    @Test
    fun endToEndBufferThenExtrapolateTracksTheTurn() {
        val b = PoseBuffer()
        val rate = 3.0
        var pose: TimedPose? = null
        for (i in 0 until 20) {
            val q = yaw(rate * i * 0.004)
            pose = b.publish(q[0], q[1], q[2], q[3], false, 0f, 0f, 0f, (500 + i * 4) * ms)
        }
        val last = pose!!
        // Pull 6 ms after the last sample with a 10 ms amount: should land 16 ms ahead of it.
        val now = last.timestampNanos + 6 * ms
        val look = PoseExtrapolator.lookaheadNanos(now - last.timestampNanos, 10f)
        PoseExtrapolator.extrapolate(last, look, out)
        val expectedYaw = rate * (19 * 0.004 + 0.016)
        val expected = yaw(expectedYaw)
        assertNotNull(look)
        assertEquals(16 * ms, look)
        assertEquals(0.0, PoseExtrapolator.angleBetween(expected, out), 5e-3)
        assertTrue(PI > 0)
    }
}
