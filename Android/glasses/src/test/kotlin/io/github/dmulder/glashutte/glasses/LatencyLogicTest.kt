package io.github.dmulder.glashutte.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyLogicTest {

    @Test
    fun defaultsAreConservative() {
        val d = LatencyExperiments.Settings()
        assertTrue(d.pullOnRender)
        assertTrue(d.highestRefresh)
        assertEquals(PredictionMode.OFF, d.predictionMode)
        assertEquals(0.0, d.sdkPredictSeconds, 0.0)
        assertFalse(d.appExtrapolation)
        assertEquals(LatencyExperiments.PULL_POLL_INTERVAL_US, d.nativePollIntervalMicros)
    }

    @Test
    fun sdkPredictOnlyWhenSdkModeSelected() {
        val s = LatencyExperiments.Settings(predictionMode = PredictionMode.SDK, predictionMs = 15)
        assertEquals(0.015, s.sdkPredictSeconds, 1e-9)
        assertFalse(s.appExtrapolation)
        val app = s.copy(predictionMode = PredictionMode.APP)
        assertEquals(0.0, app.sdkPredictSeconds, 0.0)
        assertTrue(app.appExtrapolation)
    }

    @Test
    fun appExtrapolationNeedsPullOnRender() {
        val s = LatencyExperiments.Settings(pullOnRender = false, predictionMode = PredictionMode.APP)
        assertFalse(s.appExtrapolation)
        assertEquals(LatencyExperiments.LEGACY_POLL_INTERVAL_US, s.nativePollIntervalMicros)
        assertTrue(s.describe().contains("inactive"))
    }

    @Test
    fun describeNamesEverySetting() {
        val text = LatencyExperiments.Settings(predictionMode = PredictionMode.SDK, predictionMs = 20).describe()
        assertTrue(text, text.contains("pullOnRender=true"))
        assertTrue(text, text.contains("prediction=SDK(20ms)"))
        assertTrue(text, text.contains("highestRefresh=true"))
    }

    @Test
    fun highestRefreshPicksFastestModeAtCurrentSize() {
        val modes = listOf(
            DisplayModeInfo(1, 1920, 1200, 60f),
            DisplayModeInfo(2, 1920, 1200, 120f),
            DisplayModeInfo(3, 3840, 1080, 144f),
            DisplayModeInfo(4, 1920, 1200, 90f),
        )
        assertEquals(2, DisplayRefresh.chooseHighest(modes, 1920, 1200)?.modeId)
    }

    @Test
    fun highestRefreshKeepsFirstOnTieAndHandlesNoMatch() {
        val modes = listOf(DisplayModeInfo(7, 1920, 1200, 60f), DisplayModeInfo(8, 1920, 1200, 60f))
        assertEquals(7, DisplayRefresh.chooseHighest(modes, 1920, 1200)?.modeId)
        assertNull(DisplayRefresh.chooseHighest(modes, 1280, 720))
        assertNull(DisplayRefresh.chooseHighest(emptyList(), 1920, 1200))
    }

    @Test
    fun rollingStatsPercentiles() {
        val s = RollingStats(100)
        assertEquals(0f, s.percentile(95f), 0f)
        for (i in 1..100) s.add(i.toFloat())
        assertEquals(50f, s.percentile(50f), 0f)
        assertEquals(95f, s.percentile(95f), 0f)
        assertEquals(100f, s.max(), 0f)
        assertEquals(50.5f, s.mean(), 1e-4f)
    }

    @Test
    fun rollingStatsOverwritesOldest() {
        val s = RollingStats(4)
        for (i in 1..10) s.add(i.toFloat())
        assertEquals(4, s.count)
        assertEquals(7f, s.percentile(1f), 0f)
        assertEquals(10f, s.max(), 0f)
    }

    @Test
    fun telemetrySummarisesFramesAndPoseAge() {
        val ms = 1_000_000L
        // Drain whatever earlier tests left in the shared singleton.
        LatencyTelemetry.takeSummary(0L)
        var t = 10_000 * ms
        LatencyTelemetry.takeSummary(t)
        repeat(61) {
            LatencyTelemetry.onFrame(t)
            LatencyTelemetry.onPoseUsed(4 * ms, 12 * ms)
            t += 10 * ms
        }
        val s = LatencyTelemetry.takeSummary(t - 10 * ms)
        assertEquals(60, s.frames)
        assertEquals(10f, s.dtP50Ms, 1e-3f)
        assertEquals(4f, s.ageP95Ms, 1e-3f)
        assertEquals(12f, s.meanLookaheadMs, 1e-3f)
        assertEquals(100f, s.fps, 0.5f)
    }

    @Test
    fun telemetryIgnoresPauseGaps() {
        val ms = 1_000_000L
        LatencyTelemetry.takeSummary(0L)
        LatencyTelemetry.onFrame(1_000 * ms)
        LatencyTelemetry.onFrame(1_010 * ms)
        LatencyTelemetry.onFrame(9_000 * ms) // activity was paused for ~8 s
        val s = LatencyTelemetry.takeSummary(9_001 * ms)
        assertEquals(1, s.frames)
    }

    @Test
    fun logLineCarriesSettingsRatesAndAge() {
        val summary = LatencyTelemetry.Summary(
            frames = 100, fps = 119.5f, dtP50Ms = 8.4f, dtP95Ms = 9.1f, dtMaxMs = 14f,
            poseSamples = 100, ageP50Ms = 2.1f, ageP95Ms = 4.3f, ageMaxMs = 6f, meanLookaheadMs = 12f,
        )
        val line = LatencyTelemetry.format(
            LatencyExperiments.Settings(predictionMode = PredictionMode.APP, predictionMs = 10),
            "display: 1920x1200@120Hz (mode 3)", summary, 248f,
        )
        for (needle in listOf("prediction=APP(10ms)", "1920x1200@120Hz", "119.5 fps", "pose age p50=2.1", "lookahead=12.0", "248 Hz")) {
            assertTrue("missing '$needle' in: $line", line.contains(needle))
        }
    }
}
