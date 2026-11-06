package io.github.dmulder.glashutte.glasses

import kotlin.math.sqrt

/**
 * App-side recentring of backend poses. Pure Kotlin (no Android types) so it is unit-tested
 * on the JVM and works identically for every [GlassesBackend].
 *
 * The first pose seen after [reset] becomes the reference: its HEADING (yaw about world Y)
 * is cancelled so wherever the user is looking when tracking starts is "straight ahead".
 * Pitch and roll are left exactly as the tracker reports them (gravity-anchored), so a head
 * yaw is always a rotation about true vertical and never bleeds into roll.
 *
 * Not thread-safe; use from the single pose-polling thread.
 */
class PoseRecenterer {

    private var haveRef = false

    // Yaw-only twist of the reference orientation, normalised to (cos, sin) of the half angle
    // about world Y.
    private var refYawCos = 1f
    private var refYawSin = 0f

    private var haveRefPos = false
    private var refPx = 0f
    private var refPy = 0f
    private var refPz = 0f

    /** Forget the reference. The next pose becomes the new "straight ahead". */
    fun reset() {
        haveRef = false
        haveRefPos = false
    }

    /**
     * Recentre the orientation (w, x, y, z) and write the result into [out] as (w, x, y, z).
     * effective = conjugate(refYawTwist) * raw, with conjugate(refYawTwist) = (cos, 0, -sin, 0).
     */
    fun recenterOrientation(w: Float, x: Float, y: Float, z: Float, out: FloatArray) {
        if (!haveRef) {
            // Normalise (w, 0, y, 0), the yaw twist of the reference, to a unit (cos, sin).
            val n = sqrt(w * w + y * y)
            if (n > 1e-6f) {
                refYawCos = w / n
                refYawSin = y / n
            } else {
                refYawCos = 1f
                refYawSin = 0f
            }
            haveRef = true
        }
        val c = refYawCos
        val s = refYawSin
        out[0] = c * w + s * y
        out[1] = c * x - s * z
        out[2] = c * y - s * w
        out[3] = c * z + s * x
    }

    /**
     * Recentre a 6DoF position: subtract the first position seen, then rotate the displacement
     * by the inverse reference heading so it lands in the same frame as the orientation.
     * Writes (x, y, z) into [out].
     */
    fun recenterPosition(px: Float, py: Float, pz: Float, out: FloatArray) {
        if (!haveRefPos) {
            refPx = px
            refPy = py
            refPz = pz
            haveRefPos = true
        }
        val dx = px - refPx
        val dy = py - refPy
        val dz = pz - refPz
        // cos/sin here are the full-angle terms derived from the half-angle (cos, sin) twist:
        // cos(t) = c^2 - s^2, sin(t) = 2cs.
        val c = refYawCos
        val s = refYawSin
        val cosT = c * c - s * s
        val sinT = 2f * c * s
        out[0] = cosT * dx - sinT * dz
        out[1] = dy
        out[2] = sinT * dx + cosT * dz
    }
}
