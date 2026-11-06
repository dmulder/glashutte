package io.github.dmulder.glashutte.glasses

import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Optical description of a glasses panel: its native pixel grid and the diagonal field of view
 * the optics present for that full grid. The renderer uses it to build a projection whose
 * pixels subtend the same angle as the real panel's, so a world-locked object moves across the
 * display by exactly the head-rotation angle (gain 1.0).
 *
 * The diagonal FOV is the vendor's advertised figure; real units vary a little with fit.
 */
data class PanelSpec(
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val diagonalFovDeg: Double,
) {
    /** Panel aspect ratio, width / height. */
    val aspect: Double get() = widthPx.toDouble() / heightPx

    /** Horizontal FOV across the full panel width, assuming square pixels and a rectilinear image. */
    val horizontalFovDeg: Double
        get() = Math.toDegrees(2.0 * atan(tanHalfDiagonal() * widthPx / diagonalPx()))

    /** Vertical FOV across the full panel height. */
    val verticalFovDeg: Double
        get() = Math.toDegrees(2.0 * atan(tanHalfDiagonal() * heightPx / diagonalPx()))

    // The diagonal of the image plane at unit distance is 2 * tan(diag / 2); its horizontal and
    // vertical legs are that diagonal scaled by width / |diagonal| and height / |diagonal|.
    private fun tanHalfDiagonal() = tan(Math.toRadians(diagonalFovDeg / 2.0))
    private fun diagonalPx() = sqrt(widthPx.toDouble() * widthPx + heightPx.toDouble() * heightPx)
}

/** Known panels, keyed by the USB product id the glasses enumerate with. */
object PanelSpecs {

    /** VITURE Luma Ultra: 1920x1200, 52 degrees diagonal. */
    val LUMA_ULTRA = PanelSpec("Luma Ultra", 1920, 1200, 52.0)

    /** USB product ids of the Luma Ultra (see [GlassesUsb]). */
    private val LUMA_ULTRA_PIDS = setOf(0x1101, 0x1104)

    /**
     * Product id of the glasses the head-tracking session opened, or null before one has. Set
     * by [HeadTracking]; read by the renderer so panel specs follow the connected device.
     */
    @Volatile var activeUsbPid: Int? = null

    /**
     * The spec for [pid], or null when this build has no verified numbers for that product. Only
     * the Luma Ultra is filled in: the other models' panel figures have not been confirmed, and a
     * wrong figure is worse than the fallback. Add entries here as they are verified.
     */
    fun forUsbPid(pid: Int?): PanelSpec? = when (pid) {
        null -> null
        in LUMA_ULTRA_PIDS -> LUMA_ULTRA
        else -> null
    }

    /** Spec for the active device, falling back to the Luma Ultra (the target hardware). */
    fun active(): PanelSpec = forUsbPid(activeUsbPid) ?: LUMA_ULTRA

    /** Human-readable origin of [active] for logs. */
    fun activeSource(): String {
        val pid = activeUsbPid
        return when {
            pid == null -> "fallback (no glasses pid yet)"
            forUsbPid(pid) != null -> "pid=0x${pid.toString(16)}"
            else -> "fallback (unverified pid=0x${pid.toString(16)})"
        }
    }
}

/** Projection maths derived from a [PanelSpec]. Pure functions, no Android dependencies. */
object ProjectionMath {

    /**
     * Focal length in surface pixels. The surface is assumed to span the panel's full width with
     * square pixels, so the panel's horizontal FOV maps onto [surfaceWidthPx]. Using the width
     * (not the height) keeps this right if the display mode has a different aspect to the panel.
     */
    fun focalLengthPx(spec: PanelSpec, surfaceWidthPx: Int): Double =
        (surfaceWidthPx / 2.0) / tan(Math.toRadians(spec.horizontalFovDeg / 2.0))

    /**
     * Vertical FOV, in degrees, to give a perspective projection whose viewport is
     * [viewportHeightPx] tall on a surface [surfaceWidthPx] wide, so each viewport pixel
     * subtends the same angle as on the real panel.
     */
    fun viewportVerticalFovDeg(spec: PanelSpec, surfaceWidthPx: Int, viewportHeightPx: Int): Double =
        Math.toDegrees(2.0 * atan((viewportHeightPx / 2.0) / focalLengthPx(spec, surfaceWidthPx)))

    /**
     * Screen movement per unit head rotation near the centre of the view: 1.0 means a
     * world-locked point moves across the display by the angle the head turned; below 1.0 the
     * scene is dragged along with the head.
     *
     * [projectionScale] is the extra factor applied to the projection's X/Y (legacy zoom).
     */
    fun centreGain(
        spec: PanelSpec,
        surfaceWidthPx: Int,
        viewportHeightPx: Int,
        renderedVerticalFovDeg: Double,
        projectionScale: Double,
    ): Double {
        val renderedFocalPx = projectionScale * (viewportHeightPx / 2.0) /
            tan(Math.toRadians(renderedVerticalFovDeg / 2.0))
        return renderedFocalPx / focalLengthPx(spec, surfaceWidthPx)
    }

    /** True when the surface has the panel's aspect to within [tolerance] (fractional). */
    fun surfaceMatchesPanel(spec: PanelSpec, surfaceWidthPx: Int, surfaceHeightPx: Int, tolerance: Double = 0.02): Boolean {
        if (surfaceWidthPx <= 0 || surfaceHeightPx <= 0) return false
        val surfaceAspect = surfaceWidthPx.toDouble() / surfaceHeightPx
        return kotlin.math.abs(surfaceAspect - spec.aspect) / spec.aspect <= tolerance
    }
}
