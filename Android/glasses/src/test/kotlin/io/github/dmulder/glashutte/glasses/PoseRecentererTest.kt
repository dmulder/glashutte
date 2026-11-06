package io.github.dmulder.glashutte.glasses

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

class PoseRecentererTest {

    private val out4 = FloatArray(4)
    private val out3 = FloatArray(3)

    /** Quaternion (w, x, y, z) for a rotation of [rad] about world Y. */
    private fun yaw(rad: Double) = floatArrayOf(cos(rad / 2).toFloat(), 0f, sin(rad / 2).toFloat(), 0f)

    private fun assertQuat(expected: FloatArray, actual: FloatArray) {
        for (i in 0..3) assertEquals("component $i", expected[i], actual[i], 1e-5f)
    }

    @Test
    fun firstPoseBecomesIdentity() {
        val r = PoseRecenterer()
        val q = yaw(1.2)
        r.recenterOrientation(q[0], q[1], q[2], q[3], out4)
        assertQuat(floatArrayOf(1f, 0f, 0f, 0f), out4)
    }

    @Test
    fun laterYawIsRelativeToFirstPose() {
        val r = PoseRecenterer()
        val first = yaw(0.8)
        r.recenterOrientation(first[0], first[1], first[2], first[3], out4)
        val later = yaw(0.8 + 0.3)
        r.recenterOrientation(later[0], later[1], later[2], later[3], out4)
        assertQuat(yaw(0.3), out4)
    }

    @Test
    fun resetMakesNextPoseTheNewReference() {
        val r = PoseRecenterer()
        val a = yaw(0.5)
        r.recenterOrientation(a[0], a[1], a[2], a[3], out4)
        r.reset()
        val b = yaw(-1.0)
        r.recenterOrientation(b[0], b[1], b[2], b[3], out4)
        assertQuat(floatArrayOf(1f, 0f, 0f, 0f), out4)
    }

    @Test
    fun pitchIsNotRecentred() {
        val r = PoseRecenterer()
        // Reference looks 0.4 rad up (rotation about X); heading is zero so nothing is cancelled.
        val pitch = floatArrayOf(cos(0.2).toFloat(), sin(0.2).toFloat(), 0f, 0f)
        r.recenterOrientation(pitch[0], pitch[1], pitch[2], pitch[3], out4)
        assertQuat(pitch, out4)
    }

    @Test
    fun positionIsRelativeAndRotatedByReferenceHeading() {
        val r = PoseRecenterer()
        val q = yaw(Math.PI / 2)
        r.recenterOrientation(q[0], q[1], q[2], q[3], out4)

        r.recenterPosition(1f, 2f, 3f, out3)
        assertEquals(0f, out3[0], 1e-5f)
        assertEquals(0f, out3[1], 1e-5f)
        assertEquals(0f, out3[2], 1e-5f)

        // A +1 m step along world X is -1 m along recentred Z once the 90 degree heading is removed.
        r.recenterPosition(2f, 2f, 3f, out3)
        assertEquals(0f, out3[0], 1e-5f)
        assertEquals(0f, out3[1], 1e-5f)
        assertEquals(1f, kotlin.math.abs(out3[2]), 1e-5f)
    }
}
