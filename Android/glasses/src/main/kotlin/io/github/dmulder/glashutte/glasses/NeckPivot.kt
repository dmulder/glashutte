package io.github.dmulder.glashutte.glasses

import kotlin.math.sqrt

/**
 * Camera offset for a head that rotates about the neck rather than about the eye.
 *
 * A pure orientation tracker places the camera at the origin and turns it in place, which is
 * the motion of a head pivoting about the eye. A real head pivots about a point behind and
 * below the eyes, so a turn also swings the eyes along a short arc. Reproducing that arc makes
 * near and far content shift against each other by a few centimetres, which is part of what
 * makes a virtual screen read as fixed in the room instead of stuck to the lenses.
 *
 * Frame: OpenGL-style, the camera looks down -Z at rest. The pivot is placed [lengthM] behind
 * the rest eye position, at (0, 0, +lengthM), so with the head at identity the camera is exactly
 * where it was without this offset and existing layout distances are unchanged. The eye then
 * sits at `pivot + R * (0, 0, -lengthM)`, where R is the head orientation.
 *
 * Pure Kotlin with no Android types, so it is unit-tested on the JVM.
 */
object NeckPivot {

    /** Pivot-to-eye distance used by default, in metres (about 5 inches). */
    const val DEFAULT_OFFSET_M = 0.127f

    /**
     * Writes the eye displacement from its rest position into [out] as (x, y, z) metres for the
     * head orientation (w, x, y, z). The quaternion need not be normalised; a degenerate one
     * yields a zero offset. Equivalent to `lengthM * (R * (0, 0, -1) - (0, 0, -1))`.
     */
    fun eyeOffset(w: Float, x: Float, y: Float, z: Float, lengthM: Float, out: FloatArray) {
        val n = w * w + x * x + y * y + z * z
        if (n < 1e-6f || lengthM == 0f) {
            out[0] = 0f; out[1] = 0f; out[2] = 0f
            return
        }
        val inv = 1f / sqrt(n)
        val qw = w * inv; val qx = x * inv; val qy = y * inv; val qz = z * inv
        out[0] = -2f * (qx * qz + qw * qy) * lengthM
        out[1] = -2f * (qy * qz - qw * qx) * lengthM
        out[2] = 2f * (qx * qx + qy * qy) * lengthM
    }
}
