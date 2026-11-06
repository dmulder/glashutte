package io.github.dmulder.glashutte.glasses

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Test

class NeckPivotTest {

    private val out = FloatArray(3)
    private val len = NeckPivot.DEFAULT_OFFSET_M

    private fun assertOffset(x: Float, y: Float, z: Float) {
        assertEquals("x", x, out[0], 1e-5f)
        assertEquals("y", y, out[1], 1e-5f)
        assertEquals("z", z, out[2], 1e-5f)
    }

    /** Quaternion for a rotation of [rad] about the axis (ax, ay, az); returns (w, x, y, z). */
    private fun axisAngle(rad: Double, ax: Double, ay: Double, az: Double): FloatArray {
        val n = sqrt(ax * ax + ay * ay + az * az)
        val s = sin(rad / 2) / n
        return floatArrayOf(cos(rad / 2).toFloat(), (ax * s).toFloat(), (ay * s).toFloat(), (az * s).toFloat())
    }

    private fun offsetFor(q: FloatArray, length: Float = len) {
        NeckPivot.eyeOffset(q[0], q[1], q[2], q[3], length, out)
    }

    @Test
    fun identityPoseLeavesCameraAtRest() {
        offsetFor(floatArrayOf(1f, 0f, 0f, 0f))
        assertOffset(0f, 0f, 0f)
    }

    @Test
    fun yawLeftNinetyDegreesSwingsEyeLeftAndForward() {
        // Looking 90 degrees to the left (-X): the eye is one neck length to the left of the pivot,
        // and the pivot sits one neck length behind the rest position, so z is also +len.
        offsetFor(axisAngle(Math.PI / 2, 0.0, 1.0, 0.0))
        assertOffset(-len, 0f, len)
    }

    @Test
    fun yawRightNinetyDegreesIsTheMirror() {
        offsetFor(axisAngle(-Math.PI / 2, 0.0, 1.0, 0.0))
        assertOffset(len, 0f, len)
    }

    @Test
    fun pitchUpSwingsEyeUp() {
        // Looking straight up: forward is +Y, so the eye is one neck length above the pivot.
        offsetFor(axisAngle(Math.PI / 2, 1.0, 0.0, 0.0))
        assertOffset(0f, len, len)
    }

    @Test
    fun smallYawMatchesTheArcFormula() {
        val deg = 10.0
        val rad = Math.toRadians(deg)
        offsetFor(axisAngle(rad, 0.0, 1.0, 0.0))
        assertOffset((-len * sin(rad)).toFloat(), 0f, (len * (1 - cos(rad))).toFloat())
    }

    @Test
    fun rollAboutTheViewAxisDoesNotMoveTheEye() {
        offsetFor(axisAngle(0.7, 0.0, 0.0, 1.0))
        assertOffset(0f, 0f, 0f)
    }

    @Test
    fun offsetScalesWithLengthAndZeroDisables() {
        val q = axisAngle(0.6, 0.0, 1.0, 0.0)
        offsetFor(q, 0.254f)
        val doubled = out.clone()
        offsetFor(q, 0.127f)
        for (i in 0..2) assertEquals(doubled[i], out[i] * 2f, 1e-5f)
        offsetFor(q, 0f)
        assertOffset(0f, 0f, 0f)
    }

    @Test
    fun unnormalisedAndDegenerateQuaternionsAreSafe() {
        val q = axisAngle(0.6, 0.0, 1.0, 0.0)
        offsetFor(q)
        val expected = out.clone()
        NeckPivot.eyeOffset(q[0] * 3f, q[1] * 3f, q[2] * 3f, q[3] * 3f, len, out)
        for (i in 0..2) assertEquals(expected[i], out[i], 1e-5f)
        NeckPivot.eyeOffset(0f, 0f, 0f, 0f, len, out)
        assertOffset(0f, 0f, 0f)
    }

    @Test
    fun eyeStaysOnACircleAroundThePivot() {
        // The pivot is (0, 0, len); the eye must always be exactly len from it.
        for (deg in listOf(5.0, 30.0, 75.0, 140.0)) {
            offsetFor(axisAngle(Math.toRadians(deg), 0.3, 1.0, 0.2))
            val dx = out[0]; val dy = out[1]; val dz = out[2] - len
            assertEquals(len, sqrt(dx * dx + dy * dy + dz * dz), 1e-5f)
        }
    }
}
