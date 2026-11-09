package io.github.dmulder.glashutte.glasses

import io.github.dmulder.glashutte.glasses.FlatSnapshot.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlatSnapshotTest {

    private val pkg = "com.netflix.mediaclient"

    private fun activities(vararg displays: Pair<Int, String>) = displays.joinToString("\n") { (id, body) ->
        "Display #$id (activities from top to bottom):\n$body"
    }

    private val netflixOn2 = """
        * Task{aa #12 type=standard A=10001:$pkg U=0 visible=true}
          mBounds=Rect(0, 0 - 1920, 1200)
          * ActivityRecord{bb u0 $pkg/com.netflix.mediaclient.ui.launch.UIWebViewActivity t12}
    """.trimIndent()

    private val windowsNetflixVisible = """
        Window #0 Window{c1 u0 $pkg/com.netflix.mediaclient.ui.launch.UIWebViewActivity}:
          mDisplayId=2 rootTaskId=12 mSession=Session{x} mClient=android.os.BinderProxy@y
          mAttrs=WM.LayoutParams{(0,0)(fillxfill) ty=BASE_APPLICATION fl=ALLOW_LOCK_WHILE_SCREEN_ON HARDWARE_ACCELERATED SECURE pfl=NO_MOVE_ANIMATION}
          mHasSurface=true isReadyForDisplay()=true
        Window #1 Window{c2 u0 io.github.dmulder.glashutte.spatial.WorkspacePresentation}:
          mDisplayId=2 rootTaskId=-1
          mAttrs=WM.LayoutParams{(0,0)(fillxfill) ty=PRIVATE_PRESENTATION alpha=0.0 fl=NOT_FOCUSABLE NOT_TOUCHABLE}
          mHasSurface=true isReadyForDisplay()=true
    """.trimIndent()

    @Test fun visibleOnTargetDisplay() {
        val r = FlatSnapshot.analyze(activities(0 to "", 2 to netflixOn2), windowsNetflixVisible, 2, pkg)
        assertEquals(Verdict.VISIBLE_ON_DISPLAY, r.verdict)
        assertTrue(r.passes)
        assertTrue("window carries FLAG_SECURE", r.appWindowSecure)
        assertEquals(2, r.windowsOnTarget.size)
        assertEquals("0.0", r.windowsOnTarget[1].alpha)
    }

    @Test fun taskThatBouncedToThePhoneIsElsewhere() {
        val windows = windowsNetflixVisible.replace("mDisplayId=2 rootTaskId=12", "mDisplayId=0 rootTaskId=12")
        val r = FlatSnapshot.analyze(activities(0 to netflixOn2, 2 to ""), windows, 2, pkg)
        assertEquals(Verdict.ELSEWHERE, r.verdict)
        assertFalse(r.passes)
        assertEquals(setOf(0), r.activityDisplays)
    }

    @Test fun nothingRunningAnywhere() {
        val r = FlatSnapshot.analyze(activities(0 to "", 2 to ""), "", 2, "com.example.none")
        assertEquals(Verdict.NOT_RUNNING, r.verdict)
    }

    @Test fun activityOnDisplayButWindowNotReady() {
        val windows = """
            Window #0 Window{c1 u0 $pkg/com.netflix.mediaclient.ui.launch.UIWebViewActivity}:
              mDisplayId=2 rootTaskId=12
              mHasSurface=false isReadyForDisplay()=false
        """.trimIndent()
        val r = FlatSnapshot.analyze(activities(2 to netflixOn2), windows, 2, pkg)
        assertEquals(Verdict.ON_DISPLAY_NOT_VISIBLE, r.verdict)
        assertFalse(r.passes)
    }

    @Test fun unparseableWindowDumpIsUnverifiedNotFailure() {
        val r = FlatSnapshot.analyze(activities(2 to netflixOn2), "garbage", 2, pkg)
        assertEquals(Verdict.ON_DISPLAY_UNVERIFIED, r.verdict)
        assertTrue(r.passes)
    }

    @Test fun headerRoundTrips() {
        val r = FlatSnapshot.analyze(activities(2 to netflixOn2), windowsNetflixVisible, 2, pkg)
        assertEquals(Verdict.VISIBLE_ON_DISPLAY, FlatSnapshot.decodeVerdict(r.encode()))
        assertTrue(FlatSnapshot.passes(FlatSnapshot.decodeVerdict(r.encode())))
        assertNotNull(r.header())
        assertEquals(null, FlatSnapshot.decodeVerdict("helper call failed"))
    }

    @Test fun insecureWindowIsNotMarkedSecure() {
        val windows = windowsNetflixVisible.replace(" SECURE", "")
        assertFalse(FlatSnapshot.analyze(activities(2 to netflixOn2), windows, 2, pkg).appWindowSecure)
    }
}
