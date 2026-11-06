package io.github.dmulder.glashutte.glasses

import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelOpticsTest {

    private val ultra = PanelSpecs.LUMA_ULTRA

    @Test
    fun lumaUltraAxisFovsFollowFromTheDiagonal() {
        assertEquals(44.94, ultra.horizontalFovDeg, 0.05)
        assertEquals(28.99, ultra.verticalFovDeg, 0.05)
    }

    @Test
    fun axisFovsRecombineIntoTheDiagonal() {
        // On the image plane at unit distance, the legs and the diagonal satisfy Pythagoras.
        val h = tan(Math.toRadians(ultra.horizontalFovDeg / 2))
        val v = tan(Math.toRadians(ultra.verticalFovDeg / 2))
        val d = tan(Math.toRadians(ultra.diagonalFovDeg / 2))
        assertEquals(d * d, h * h + v * v, 1e-9)
    }

    @Test
    fun squarePanelHasEqualAxes() {
        val square = PanelSpec("square", 1000, 1000, 60.0)
        assertEquals(square.horizontalFovDeg, square.verticalFovDeg, 1e-9)
    }

    @Test
    fun fullPanelViewportUsesTheFullVerticalFov() {
        val fovY = ProjectionMath.viewportVerticalFovDeg(ultra, 1920, 1200)
        assertEquals(ultra.verticalFovDeg, fovY, 1e-6)
    }

    @Test
    fun bandViewportGetsTheMatchingSliceOfTheVerticalFov() {
        // 83% band of a 1200 px tall panel = 996 px.
        val fovY = ProjectionMath.viewportVerticalFovDeg(ultra, 1920, 996)
        assertEquals(24.2, fovY, 0.1)
        // Same pixel density as the full panel: 1920 px across 44.94 degrees at the centre.
        val pxPerRadFull = ProjectionMath.focalLengthPx(ultra, 1920)
        val pxPerRadBand = (996 / 2.0) / tan(Math.toRadians(fovY / 2))
        assertEquals(pxPerRadFull, pxPerRadBand, 1e-6)
    }

    @Test
    fun calibratedProjectionHasUnitGainAtAnyViewportHeight() {
        for (h in intArrayOf(1200, 996, 600)) {
            val fovY = ProjectionMath.viewportVerticalFovDeg(ultra, 1920, h)
            assertEquals(1.0, ProjectionMath.centreGain(ultra, 1920, h, fovY, 1.0), 1e-9)
        }
    }

    @Test
    fun legacyProjectionReproducesTheMeasuredGain() {
        // 55 degree vertical FOV drawn into a 996 px band of the 1920x1200 panel.
        assertEquals(0.41, ProjectionMath.centreGain(ultra, 1920, 996, 55.0, 1.0), 0.01)
        // Default FREE zoom of 1.2 on the projection: roughly half of the head motion.
        assertEquals(0.49, ProjectionMath.centreGain(ultra, 1920, 996, 55.0, 1.2), 0.01)
    }

    @Test
    fun zoomOnTheProjectionScalesGainLinearly() {
        val fovY = ProjectionMath.viewportVerticalFovDeg(ultra, 1920, 996)
        assertEquals(2.0, ProjectionMath.centreGain(ultra, 1920, 996, fovY, 2.0), 1e-9)
    }

    @Test
    fun focalLengthFollowsSurfaceWidthNotHeight() {
        // A 16:9 mode on the same panel width keeps the same pixels-per-degree horizontally.
        val full = ProjectionMath.focalLengthPx(ultra, 1920)
        assertEquals(1920 / 2.0 / tan(Math.toRadians(ultra.horizontalFovDeg / 2)), full, 1e-9)
        assertEquals(full / 2, ProjectionMath.focalLengthPx(ultra, 960), 1e-9)
    }

    @Test
    fun surfaceAspectCheck() {
        assertTrue(ProjectionMath.surfaceMatchesPanel(ultra, 1920, 1200))
        assertTrue(ProjectionMath.surfaceMatchesPanel(ultra, 960, 600))
        assertFalse(ProjectionMath.surfaceMatchesPanel(ultra, 1920, 1080))
        assertFalse(ProjectionMath.surfaceMatchesPanel(ultra, 0, 0))
    }

    @Test
    fun specLookupByUsbPid() {
        assertSame(ultra, PanelSpecs.forUsbPid(0x1101))
        assertSame(ultra, PanelSpecs.forUsbPid(0x1104))
        assertNull(PanelSpecs.forUsbPid(0x1201))
        assertNull(PanelSpecs.forUsbPid(null))
    }

    @Test
    fun activeSpecFollowsThePidAndFallsBackToTheTargetDevice() {
        val saved = PanelSpecs.activeUsbPid
        try {
            PanelSpecs.activeUsbPid = null
            assertSame(ultra, PanelSpecs.active())
            assertTrue(PanelSpecs.activeSource().startsWith("fallback"))

            PanelSpecs.activeUsbPid = 0x1104
            assertSame(ultra, PanelSpecs.active())
            assertEquals("pid=0x1104", PanelSpecs.activeSource())

            PanelSpecs.activeUsbPid = 0x1201
            assertNotNull(PanelSpecs.active())
            assertTrue(PanelSpecs.activeSource().contains("unverified"))
        } finally {
            PanelSpecs.activeUsbPid = saved
        }
    }
}
