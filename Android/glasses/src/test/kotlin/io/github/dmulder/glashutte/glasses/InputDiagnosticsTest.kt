package io.github.dmulder.glashutte.glasses

import io.github.dmulder.glashutte.glasses.InputDiagnostics.HitRecord
import io.github.dmulder.glashutte.glasses.InputDiagnostics.Path
import io.github.dmulder.glashutte.glasses.InputDiagnostics.Route
import io.github.dmulder.glashutte.glasses.InputDiagnostics.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InputDiagnosticsTest {

    @Before
    fun fresh() {
        InputDiagnostics.reset()
    }

    private fun appHit(path: Path, roundTrip: Float = 0.0001f) = HitRecord(
        path = path, kind = "click", route = Route.APP, reason = "ok", cursorNdc = "(0.10,0.20)", screenIdx = 0,
        slotPx = "(640,360)", windowPkg = "com.example.app", windowDisplayId = 7, localX = 300, localY = 500,
        displaySize = "1080x1920", roundTripErr = roundTrip, projection = "fov=46",
    )

    private fun stage(path: Path, titlePart: String) =
        InputDiagnostics.stages(path).first { it.title.contains(titlePart) }

    private fun connectHelper() {
        InputDiagnostics.helperState("READY", ready = true)
        InputDiagnostics.helperPing(true)
        InputDiagnostics.injector(true, "InputManagerGlobal")
    }

    @Test
    fun helperIsStageZeroOfEveryPathAndFirstInTheReport() {
        InputDiagnostics.helperState("NEEDS_PAIRING", ready = false, detail = "no pairing code entered yet")
        Path.values().forEach { p ->
            val first = InputDiagnostics.stages(p).first()
            assertTrue(first.title, first.title.startsWith("0. Input helper connected"))
            assertEquals(Status.FAIL, first.status)
            assertTrue(first.detail, first.detail.startsWith("NO"))
        }
        val report = InputDiagnostics.report()
        assertTrue(report.contains("HELPER CONNECTED: NO (state NEEDS_PAIRING"))
        assertTrue(report.indexOf("HELPER CONNECTED") < report.indexOf("== PATH:"))
        assertTrue(InputDiagnostics.verdict().startsWith("HELPER NOT CONNECTED"))
        assertTrue(InputDiagnostics.pathReport(Path.MOUSE).lines()[1].startsWith("HELPER CONNECTED: NO"))
    }

    @Test
    fun trackpadHiddenBehindWizardIsReported() {
        InputDiagnostics.helperState("NEEDS_PAIRING", ready = false)
        InputDiagnostics.phoneScene("setup wizard (helper state NEEDS_PAIRING)", trackpadShown = false)
        val s = stage(Path.TRACKPAD, "Phone shows the trackpad")
        assertEquals(Status.FAIL, s.status)
        assertTrue(s.detail.contains("HIDDEN"))
    }

    @Test
    fun trackpadHealthyChainIsOk() {
        connectHelper()
        InputDiagnostics.phoneScene("trackpad", trackpadShown = true)
        InputDiagnostics.note(Path.TRACKPAD, "touch", "DOWN (10,20)")
        InputDiagnostics.note(Path.TRACKPAD, "move")
        InputDiagnostics.note(Path.TRACKPAD, "tap")
        InputDiagnostics.note(Path.TRACKPAD, "cursor")
        InputDiagnostics.note(Path.TRACKPAD, "clickQueued")
        InputDiagnostics.note(Path.TRACKPAD, "clickProcessed")
        InputDiagnostics.recordHit(appHit(Path.TRACKPAD))
        InputDiagnostics.recordInject("touch", "DOWN display=7 (300,500)", InjectOutcome.ok("global"))
        InputDiagnostics.stages(Path.TRACKPAD).forEach { assertEquals(it.title, Status.OK, it.status) }
        assertTrue(InputDiagnostics.verdict(Path.TRACKPAD).startsWith("OK"))
    }

    @Test
    fun trackpadClickQueuedButNeverProcessedFails() {
        connectHelper()
        InputDiagnostics.note(Path.TRACKPAD, "clickQueued")
        assertEquals(Status.FAIL, stage(Path.TRACKPAD, "Click reaches the workspace renderer").status)
    }

    @Test
    fun gesturesDroppedWithoutRendererFail() {
        connectHelper()
        InputDiagnostics.note(Path.TRACKPAD, "noRenderer")
        assertEquals(Status.FAIL, stage(Path.TRACKPAD, "Workspace cursor moves").status)
    }

    @Test
    fun rejectedInjectionIsAttributedToTheClickingPath() {
        connectHelper()
        InputDiagnostics.recordHit(appHit(Path.MOUSE))
        InputDiagnostics.recordInject("touch", "DOWN display=7 (300,500)", InjectOutcome(InjectOutcome.REJECTED, "legacy", "false"))
        assertEquals(Status.FAIL, stage(Path.MOUSE, "Dispatcher accepted").status)
        assertEquals(Status.PENDING, stage(Path.TRACKPAD, "Dispatcher accepted").status)
        assertTrue(InputDiagnostics.report().contains("injections ok=0 failed=1"))
    }

    @Test
    fun mouseNeedsAnAndroidDeviceAndAHelperMonitor() {
        connectHelper()
        InputDiagnostics.deviceInventory(listOf("id=3 Pixel touch"), emptyList(), emptyList())
        assertEquals(Status.FAIL, stage(Path.MOUSE, "Android sees a mouse").status)
        InputDiagnostics.deviceInventory(listOf("id=9 BT Mouse"), listOf("BT Mouse"), emptyList())
        assertEquals(Status.OK, stage(Path.MOUSE, "Android sees a mouse").status)

        InputDiagnostics.helperReport("STATS monitor=off opened=0 grabbed=0 keys=0 rel=0")
        assertEquals(Status.FAIL, stage(Path.MOUSE, "Helper is reading the mouse").status)
        InputDiagnostics.helperReport("STATS monitor=on opened=4 grabbed=1 keys=0 rel=120 skipped=none")
        assertEquals(Status.OK, stage(Path.MOUSE, "Helper is reading the mouse").status)
    }

    @Test
    fun mouseOnlyReachingTheFallbackIsAWarning() {
        connectHelper()
        InputDiagnostics.note(Path.MOUSE, "activityDelta")
        assertEquals(Status.WARN, stage(Path.MOUSE, "Mouse events reach Glashutte").status)
        InputDiagnostics.note(Path.MOUSE, "evdevDelta")
        assertEquals(Status.OK, stage(Path.MOUSE, "Mouse events reach Glashutte").status)
    }

    @Test
    fun keyboardNeedsATypingTargetBeforeForwarding() {
        connectHelper()
        InputDiagnostics.deviceInventory(listOf("id=4 BT Keyboard"), emptyList(), listOf("BT Keyboard"))
        InputDiagnostics.note(Path.KEYBOARD, "keyDown", "A")
        assertEquals(Status.OK, stage(Path.KEYBOARD, "Key presses reach").status)
        assertEquals(Status.FAIL, stage(Path.KEYBOARD, "typing target").status)
        assertEquals(Status.FAIL, stage(Path.KEYBOARD, "forwarded").status)

        InputDiagnostics.typingTarget(7, "com.example.app")
        InputDiagnostics.note(Path.KEYBOARD, "forwarded")
        assertEquals(Status.OK, stage(Path.KEYBOARD, "typing target").status)
        assertEquals(Status.OK, stage(Path.KEYBOARD, "forwarded").status)
        InputDiagnostics.recordInject("key", "A down display=7", InjectOutcome.ok("global"))
        assertEquals(Status.OK, stage(Path.KEYBOARD, "Dispatcher accepted").status)
    }

    @Test
    fun keysSeenByHelperButNotByTheWindowWarn() {
        connectHelper()
        InputDiagnostics.helperReport("STATS monitor=on opened=4 grabbed=0 keys=9 rel=0")
        assertEquals(Status.WARN, stage(Path.KEYBOARD, "Key presses reach").status)
    }

    @Test
    fun beginTestClearsOnlyThatPath() {
        InputDiagnostics.note(Path.MOUSE, "evdevDelta")
        InputDiagnostics.note(Path.KEYBOARD, "keyDown")
        InputDiagnostics.beginTest(Path.MOUSE)
        assertEquals(0L, InputDiagnostics.count(Path.MOUSE, "evdevDelta"))
        assertEquals(1L, InputDiagnostics.count(Path.KEYBOARD, "keyDown"))
        assertEquals(Path.MOUSE, InputDiagnostics.activeTest())
    }

    @Test
    fun helperDeathIsRemembered() {
        InputDiagnostics.helperState("READY", ready = true)
        InputDiagnostics.helperDied("binder died")
        assertTrue(InputDiagnostics.helperLine().startsWith("HELPER CONNECTED: NO"))
        InputDiagnostics.helperState("READY", ready = true)
        assertTrue(stage(Path.TRACKPAD, "Input helper connected").detail.contains("died 1 time"))
    }

    @Test
    fun reportContainsTimelineDevicesAndHelperSelfReport() {
        InputDiagnostics.timelineEvent("connect attempt on port 41234")
        InputDiagnostics.deviceInventory(listOf("id=9 BT Mouse src=0x2002"), listOf("BT Mouse"), emptyList())
        InputDiagnostics.helperReport("STATS monitor=on opened=1\nstrategies tried: global=ok")
        val report = InputDiagnostics.report()
        assertTrue(report.contains("connect attempt on port 41234"))
        assertTrue(report.contains("BT Mouse src=0x2002"))
        assertTrue(report.contains("strategies tried: global=ok"))
        assertFalse(InputDiagnostics.report(includeTimeline = false).contains("connect attempt"))
    }

    @Test
    fun statsLinesAreParsed() {
        val parsed = InputDiagnostics.parseStats("noise\nSTATS monitor=on opened=3 skipped=event1:EACCES,event2:EACCES\nmore")
        assertEquals("on", parsed["monitor"])
        assertEquals("3", parsed["opened"])
        assertEquals("event1:EACCES,event2:EACCES", parsed["skipped"])
    }

    @Test
    fun injectOutcomeRoundTripsThroughItsWireFormat() {
        val outcome = InjectOutcome(InjectOutcome.EXCEPTION, "legacy", "NoSuchMethod\tgetInstance\nboom")
        val parsed = InjectOutcome.parse(outcome.encode())
        assertEquals(InjectOutcome.EXCEPTION, parsed.code)
        assertEquals("legacy", parsed.strategy)
        assertFalse(parsed.detail.contains('\n'))
        assertFalse(parsed.detail.contains('\t'))
    }

    @Test
    fun garbageFromTheHelperBecomesACallFailure() {
        assertEquals(InjectOutcome.HELPER_CALL_FAILED, InjectOutcome.parse(null).code)
        assertEquals(InjectOutcome.HELPER_CALL_FAILED, InjectOutcome.parse("what").code)
        assertTrue(InjectOutcome.parse("0\tglobal\t").ok)
    }

    @Test
    fun dumpsysFilterKeepsTheRequestedDisplayAndFocusLines() {
        val dump = """
            Input Dispatcher State:
              FocusedDisplayId: 0
              FocusedApplications:
                displayId=0, name='ActivityRecord{1 u0 com.phone/.Main t1}'
                displayId=7, name='ActivityRecord{2 u0 com.example.app/.Main t9}'
              Display: 0
                Windows:
                  0: name='phone'
              Display: 7
                Windows:
                  0: name='com.example.app/.Main', touchableRegion=[0,0][1080,1920]
              Display: 9
                Windows:
                  0: name='other'
        """.trimIndent()
        val out = DumpsysFilter.forDisplay(dump, 7)
        assertTrue(out, out.contains("com.example.app/.Main', touchableRegion"))
        assertTrue(out, out.contains("FocusedDisplayId: 0"))
        assertFalse(out, out.contains("name='other'"))
        assertFalse(out, out.contains("name='phone'"))
        assertFalse(DumpsysFilter.forDisplay(dump, 42).contains("touchableRegion"))
        assertEquals("(nothing mentions display 42)", DumpsysFilter.forDisplay("Nothing relevant\n", 42))
        assertEquals("(no dump)", DumpsysFilter.forDisplay("", 1))
    }
}
