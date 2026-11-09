package io.github.dmulder.glashutte.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlatFitTest {

    private fun verdict(checks: List<FlatFit.Check>, name: String) = checks.first { it.name == name }.verdict

    @Test fun nativePanelAtDefaultDensityIsAllGood() {
        val checks = FlatFit.analyze(1920, 1200, FlatFit.DEFAULT_DENSITY_DPI, 1920, 1200)
        assertTrue(checks.all { it.verdict == FlatFit.Verdict.OK })
    }

    @Test fun portraitPhoneScreenFailsTheShapeCheck() {
        val checks = FlatFit.analyze(1344, 2992, 480)
        assertEquals(FlatFit.Verdict.FAIL, verdict(checks, "display shape"))
    }

    @Test fun nonSixteenTenLandscapeWarns() {
        assertEquals(FlatFit.Verdict.WARN, verdict(FlatFit.analyze(1920, 1080, 240), "display shape"))
    }

    @Test fun tinyDensityFailsReadability() {
        assertEquals(FlatFit.Verdict.FAIL, verdict(FlatFit.analyze(1920, 1200, 120), "UI size"))
    }

    @Test fun oversizedDensityWarns() {
        assertEquals(FlatFit.Verdict.WARN, verdict(FlatFit.analyze(1920, 1200, 480), "UI size"))
    }

    @Test fun letterboxedWindowIsReported() {
        val checks = FlatFit.analyze(1920, 1200, 240, 600, 1200)
        assertEquals(FlatFit.Verdict.WARN, verdict(checks, "window fills display"))
    }

    @Test fun aspectHelpers() {
        assertTrue(FlatFit.isSixteenTen(1920, 1200))
        assertTrue(FlatFit.isSixteenTen(1280, 800))
        assertFalse(FlatFit.isSixteenTen(1920, 1080))
        assertEquals(1280, FlatFit.dpSize(1920, 240))
        assertEquals(800, FlatFit.dpSize(1200, 240))
        assertEquals(FlatFit.MIN_DENSITY_DPI, FlatFit.clampDensity(10))
        assertEquals(FlatFit.MAX_DENSITY_DPI, FlatFit.clampDensity(900))
    }

    @Test fun letterboxCentersPortraitContentOnLandscapeDisplay() {
        val box = FlatFit.letterbox(1920, 1200, 9f / 16f)
        assertEquals(675, box[2])
        assertEquals(1200, box[3])
        assertEquals((1920 - 675) / 2, box[0])
    }
}
