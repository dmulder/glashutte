package io.github.dmulder.glashutte.glasses

import io.github.dmulder.glashutte.glasses.GlassesDiagnostics.Stage
import io.github.dmulder.glashutte.glasses.GlassesDiagnostics.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GlassesDiagnosticsTest {

    @Before
    fun fresh() {
        GlassesDiagnostics.beginSession()
    }

    @Test
    fun firstNonOkStageIsReportedAsTheFailure() {
        GlassesDiagnostics.stage(Stage.USB_DEVICE, Status.OK, "found pid 0x1104")
        GlassesDiagnostics.stage(Stage.USB_PERMISSION, Status.FAIL, "denied")
        val problem = GlassesDiagnostics.firstProblem()
        assertNotNull(problem)
        assertEquals(Stage.USB_PERMISSION, problem!!.first)
        assertTrue(GlassesDiagnostics.verdict().contains("denied"))
    }

    @Test
    fun poseSamplesGiveAFirstPoseStageAndCount() {
        GlassesDiagnostics.onPoseSample(1f, 0f, 0f, 0f, false, 0f, 0f, 0f)
        assertEquals(1L, GlassesDiagnostics.poseCount())
        assertEquals(Status.OK, statusOf(Stage.POSE_STREAM))
    }

    @Test
    fun rendererWarnsWhenPoseArrivesButViewIsPinned() {
        GlassesDiagnostics.onPoseSample(1f, 0f, 0f, 0f, false, 0f, 0f, 0f)
        GlassesDiagnostics.setRenderer("PINNED", true, 10)
        assertEquals(Status.WARN, statusOf(Stage.RENDERER))
        GlassesDiagnostics.setRenderer("FREE", true, 20)
        assertEquals(Status.OK, statusOf(Stage.RENDERER))
    }

    @Test
    fun constantPosesAreFlaggedAsDeadTracker() {
        for (s in Stage.values()) GlassesDiagnostics.stage(s, Status.OK, "ok")
        repeat(200) { GlassesDiagnostics.onPoseSample(1f, 0f, 0f, 0f, false, 0f, 0f, 0f) }
        assertTrue(GlassesDiagnostics.verdict().contains("never change"))
    }

    @Test
    fun movingPosesAreOk() {
        for (s in Stage.values()) GlassesDiagnostics.stage(s, Status.OK, "ok")
        repeat(200) { i ->
            val a = i * 0.01f
            GlassesDiagnostics.onPoseSample(
                kotlin.math.cos(a / 2), 0f, kotlin.math.sin(a / 2), 0f, false, 0f, 0f, 0f,
            )
        }
        assertNull(GlassesDiagnostics.firstProblem())
        assertTrue(GlassesDiagnostics.verdict().startsWith("OK"))
    }

    @Test
    fun reportContainsTheKeySections() {
        val report = GlassesDiagnostics.report(includeEvents = true, includeSdkLog = false)
        for (needle in listOf("Pipeline stages", "Pose", "Native (JNI) counters", "USB devices")) {
            assertTrue("missing '$needle'", report.contains(needle))
        }
    }

    private fun statusOf(stage: Stage): Status {
        val line = GlassesDiagnostics.report(includeEvents = false, includeSdkLog = false)
            .lineSequence().firstOrNull { it.contains(stage.title) } ?: return Status.PENDING
        return when {
            line.startsWith("[ OK ]") -> Status.OK
            line.startsWith("[FAIL]") -> Status.FAIL
            line.startsWith("[WARN]") -> Status.WARN
            line.startsWith("[skip]") -> Status.SKIPPED
            else -> Status.PENDING
        }
    }
}
